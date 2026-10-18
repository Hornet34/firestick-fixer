// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * "Fast actions": the app talking to its own stick's adbd (DECISIONS D34–D42).
 *
 * Fire OS 8 drops connections from the stick itself on the classic :5555 port (D36), but not on
 * the wireless-debugging port (D38). So for each burst of actions the app switches wireless
 * debugging on (its own WRITE_SECURE_SETTINGS; the home Wi-Fi was trusted once), finds the
 * port by mDNS, connects to 127.0.0.1 over TLS with its own key, runs the commands and, after
 * {@link #IDLE_MS} without use (or at screen-off), closes and switches wireless debugging off.
 *
 * Only {@link AdbActions} builds commands; this class just carries them. Everything runs on one
 * worker thread, one job at a time.
 */
final class LocalAdb {

    static final String READY = "ready", NOT_SET_UP = "not_set_up", NO_PERMISSION = "no_permission",
            ADB_OFF = "adb_off", WIFI_REFUSED = "wifi_refused", KEY_REJECTED = "key_rejected",
            FAILED = "failed";

    /** Session and wireless debugging stay up this long after the last command. */
    private static final long IDLE_MS = 20_000L;

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_STLS = 0x534c5453,
            A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43;
    private static final int VERSION = 0x01000001, STLS_VERSION = 0x01000000, MAX_DATA = 1 << 20;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** Last known state, for the settings row and the decision to try at all. */
    private static volatile String status = null;
    private static volatile String statusDetail = "";

    private static Handler worker;
    private static Session session;
    private static boolean wifiOnByUs;
    private static final Runnable idleClose = new Runnable() {
        @Override public void run() { closeSession("idle"); }
    };

    private LocalAdb() {}

    interface Job { void run(Session s) throws Exception; }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("fast-actions");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    // ---- State ----

    static String status(Context c) {
        if (!AdbKey.exists(c)) return NOT_SET_UP;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return NO_PERMISSION;
        if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) return ADB_OFF;
        String s = status;
        return s == null ? READY : s;
    }

    static String statusDetail() { return statusDetail; }

    /** A Fast action has worked at least once with the current key (the setup checklist ticks it). */
    static boolean verified(Context c) {
        return AdbKey.exists(c) && c.getSharedPreferences("fast_actions", Context.MODE_PRIVATE)
                .getBoolean("verified", false);
    }

    static void setVerified(Context c, boolean v) {
        c.getSharedPreferences("fast_actions", Context.MODE_PRIVATE).edit().putBoolean("verified", v).apply();
    }

    /** Worth trying: set up, allowed, adb on, and no refusal that needs the owner first. */
    static boolean usable(Context c) {
        String s = status(c);
        return READY.equals(s) || FAILED.equals(s);
    }

    private static void setStatus(String s, String detail) {
        status = s;
        statusDetail = detail == null ? "" : detail;
        RingLog.i(HijackService.TAG, "Fast actions: " + s + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** Clears a remembered refusal, e.g. after the owner allowed the Wi-Fi or re-ran setup. */
    static void resetStatus() { status = null; statusDetail = ""; }

    // ---- Running jobs ----

    /**
     * Runs {@code job} on the worker with a live session. {@code onDone} (main thread) gets
     * true if the job completed.
     */
    static void submit(final Context ctx, final String why, final Job job, final Callback onDone) {
        final Context c = ctx.getApplicationContext();
        worker().post(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                long t0 = android.os.SystemClock.uptimeMillis();
                try {
                    if (!usable(c)) throw new IllegalStateException("not usable: " + status(c));
                    Session s = ensureSession(c);
                    job.run(s);
                    ok = true;
                    if (status != null) resetStatus();
                    if (!verified(c)) setVerified(c, true);
                    RingLog.i(HijackService.TAG, "Fast actions: " + why + " done in "
                            + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
                } catch (Throwable e) {
                    RingLog.i(HijackService.TAG, "Fast actions: " + why + " failed: " + e);
                    if (status == null) setStatus(FAILED, String.valueOf(e.getMessage()));
                    closeSession("error");
                }
                worker().removeCallbacks(idleClose);
                if (session != null) worker().postDelayed(idleClose, IDLE_MS);
                if (onDone != null) {
                    final boolean result = ok;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { onDone.done(result); }
                    });
                }
            }
        });
    }

    interface Callback { void done(boolean ok); }

    /** Closes the session and switches wireless debugging back off (screen off, idle, error). */
    static void closeNow(final String why) {
        if (worker == null) return;
        worker().post(new Runnable() {
            @Override public void run() { closeSession(why); }
        });
    }

    private static void closeSession(String why) {
        worker().removeCallbacks(idleClose);
        if (session != null) {
            session.close();
            session = null;
            RingLog.i(HijackService.TAG, "Fast actions: session closed (" + why + ")");
        }
        if (wifiOnByUs && appContext != null) {
            setWifi(appContext, false);
            wifiOnByUs = false;
        }
    }

    private static Context appContext;

    private static Session ensureSession(Context c) throws Exception {
        appContext = c;
        if (session != null && session.alive()) return session;
        if (session != null) { session.close(); session = null; }
        AdbKey key = AdbKey.load(c);
        if (key == null) { setStatus(NOT_SET_UP, null); throw new IllegalStateException("no key"); }

        if (!wifiOn(c)) {
            setWifi(c, true);
            wifiOnByUs = true;
            // The system resets it to 0 at once (and shows its dialog) on a network that isn't trusted.
            Thread.sleep(250);
            if (!wifiOn(c)) {
                wifiOnByUs = false;
                setStatus(WIFI_REFUSED, null);
                throw new IllegalStateException("wireless debugging refused on this network");
            }
        }
        int port = -1;
        for (int i = 0; i < 3 && port < 0; i++) port = discoverPort(c, 800);
        if (port < 0) port = scanPort();
        if (port < 0) throw new IOException("wireless debugging port not found");
        try {
            session = Session.open(key, port);
        } catch (KeyRejected e) {
            setStatus(KEY_REJECTED, null);
            setVerified(c, false);
            throw e;
        }
        return session;
    }

    private static boolean wifiOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    private static void setWifi(Context c, boolean on) {
        try {
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", on ? 1 : 0);
        } catch (SecurityException e) {
            setStatus(NO_PERMISSION, null);
        }
    }

    // ---- Finding the port ----

    /** mDNS {@code _adb-tls-connect._tcp}, only answers that resolve to this stick. -1 if none. */
    static int discoverPort(Context c, long timeoutMs) throws InterruptedException {
        final NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        final AtomicInteger port = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onServiceResolved(NsdServiceInfo r) {
                        if (r.getHost() != null && isLocalAddress(r.getHost())) {
                            port.compareAndSet(-1, r.getPort());
                            done.countDown();
                        }
                    }
                    // The same service is often reported twice; the second resolve fails (already active).
                    @Override public void onResolveFailed(NsdServiceInfo r, int e) {}
                });
            }
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
        };
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, l);
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) {}
        }
        return port.get();
    }

    private static boolean isLocalAddress(java.net.InetAddress a) {
        try {
            return a.isLoopbackAddress() || java.net.NetworkInterface.getByInetAddress(a) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fallback: adbd binds the port at random in Linux's ephemeral range. Tries each port on
     * 127.0.0.1 (a refused loopback connect costs microseconds) and keeps the one that answers
     * CNXN with STLS. ~0.7 s on the stick (D41).
     */
    static int scanPort() throws InterruptedException {
        final int lo = 32768, hi = 60999, threads = 8;
        final AtomicInteger found = new AtomicInteger(-1);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int start = lo + t;
            ts[t] = new Thread(new Runnable() {
                @Override public void run() {
                    for (int p = start; p <= hi && found.get() < 0; p += threads) {
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress("127.0.0.1", p), 50);
                            s.setSoTimeout(300);
                            send(s.getOutputStream(), A_CNXN, VERSION, MAX_DATA, banner());
                            if (read(s.getInputStream()).cmd == A_STLS) found.compareAndSet(-1, p);
                        } catch (IOException ignored) {
                        } finally {
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    }
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        return found.get();
    }

    // ---- The session ----

    static final class KeyRejected extends IOException {
        KeyRejected(String m) { super(m); }
    }

    /** One TLS connection to adbd; commands run one after another as shell streams. */
    static final class Session {
        private final Socket raw;
        private final SSLSocket tls;
        private final InputStream in;
        private final OutputStream out;
        private int nextId = 1;
        private boolean broken;

        private Session(Socket raw, SSLSocket tls) throws IOException {
            this.raw = raw;
            this.tls = tls;
            in = tls.getInputStream();
            out = tls.getOutputStream();
        }

        static Session open(AdbKey key, int port) throws Exception {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            raw.setSoTimeout(5000);
            try {
                send(raw.getOutputStream(), A_CNXN, VERSION, MAX_DATA, banner());
                Packet p = read(raw.getInputStream());
                if (p.cmd != A_STLS) throw new IOException("expected STLS, got " + p);
                send(raw.getOutputStream(), A_STLS, STLS_VERSION, 0, new byte[0]);
                SSLSocket tls = (SSLSocket) tlsContext(key).getSocketFactory()
                        .createSocket(raw, "127.0.0.1", port, true);
                tls.setUseClientMode(true);
                try {
                    tls.startHandshake();
                    p = read(tls.getInputStream());
                } catch (IOException e) {
                    // adbd ends the handshake when the client certificate's key isn't approved.
                    throw new KeyRejected("adbd refused the app's key: " + e.getMessage());
                }
                if (p.cmd != A_CNXN) throw new IOException("expected CNXN, got " + p);
                return new Session(raw, tls);
            } catch (Exception e) {
                try { raw.close(); } catch (IOException ignored) {}
                throw e;
            }
        }

        boolean alive() { return !broken && !tls.isClosed(); }

        /** Runs one shell command and returns its output. Only {@link AdbActions} calls this. */
        String shell(String command) throws IOException {
            int local = nextId++;
            try {
                send(out, A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder();
                int remote = 0;
                while (true) {
                    Packet p = read(in);
                    if (p.arg1 != local) continue;
                    if (p.cmd == A_OKAY) {
                        remote = p.arg0;
                    } else if (p.cmd == A_WRTE) {
                        result.append(new String(p.data, StandardCharsets.UTF_8));
                        send(out, A_OKAY, local, p.arg0, new byte[0]);
                    } else if (p.cmd == A_CLSE) {
                        if (remote != 0) send(out, A_CLSE, local, remote, new byte[0]);
                        return result.toString();
                    }
                }
            } catch (IOException e) {
                broken = true;
                throw e;
            }
        }

        void close() {
            try { tls.close(); } catch (IOException ignored) {}
            try { raw.close(); } catch (IOException ignored) {}
        }
    }

    private static SSLContext tlsContext(final AdbKey key) throws Exception {
        X509KeyManager km = new X509KeyManager() {
            @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "adb"; }
            @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"adb"}; }
            @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{key.certificate}; }
            @Override public PrivateKey getPrivateKey(String a) { return key.privateKey; }
            @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
            @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        };
        // adbd's certificate is self-signed; the connection only ever goes to 127.0.0.1, the stick's own adbd.
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] ch, String t) {}
            @Override public void checkServerTrusted(X509Certificate[] ch, String t) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, null);
        return ctx;
    }

    // ---- One-time approval of the app's key ----

    /**
     * Asks adbd to approve the app's key with the normal "Allow USB debugging?" prompt. adbd
     * shows it only for connections that don't come from the stick itself (D36), so setup
     * relays this connection through the computer (tools/setup-adb.sh): host:port is the relay.
     * Blocks up to ~90 s while the owner answers the prompt on the TV. Returns a one-line result.
     */
    static String approve(Context c, String host, int port) {
        Socket s = new Socket();
        try {
            AdbKey key = AdbKey.loadOrCreate(c);
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            send(out, A_CNXN, VERSION, MAX_DATA, banner());
            boolean sentSignature = false, sentKey = false;
            long deadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < deadline) {
                Packet p;
                try {
                    p = read(in);
                } catch (java.net.SocketTimeoutException e) {
                    continue; // waiting for the owner to press Allow
                }
                if (p.cmd == A_CNXN) {
                    resetStatus();
                    return "OK approved (fingerprint " + key.fingerprint() + ")";
                }
                if (p.cmd == A_AUTH && p.arg0 == AUTH_TOKEN) {
                    if (!sentSignature) {
                        send(out, A_AUTH, AUTH_SIGNATURE, 0, key.signToken(p.data));
                        sentSignature = true;
                    } else if (!sentKey) {
                        send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey());
                        sentKey = true;
                        RingLog.i(HijackService.TAG, "Fast actions: approval prompt requested, fingerprint "
                                + key.fingerprint());
                    }
                } else if (p.cmd == A_STLS) {
                    return "FAIL the relay reached the wireless-debugging port; it must point at :5555";
                }
            }
            return "FAIL no answer on the TV within 90 s (fingerprint " + key.fingerprint() + ")";
        } catch (Exception e) {
            return "FAIL " + e;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ---- Wire format ----

    private static byte[] banner() { return "host::\0".getBytes(StandardCharsets.UTF_8); }

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
        @Override public String toString() {
            return new String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).array(),
                    StandardCharsets.US_ASCII) + "(" + Integer.toHexString(arg0) + "," + arg1 + ")";
        }
    }

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        o.write(h.array());
        o.flush();
    }

    private static Packet read(InputStream i) throws IOException {
        DataInputStream in = new DataInputStream(i);
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = h.getInt();
        p.arg0 = h.getInt();
        p.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > MAX_DATA) throw new IOException("bad length " + len);
        p.data = new byte[len];
        in.readFully(p.data);
        return p;
    }
}
