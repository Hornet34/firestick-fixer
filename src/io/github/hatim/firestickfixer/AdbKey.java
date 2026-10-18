// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The app's own adb key (DECISIONS D42). Made on the stick and kept in the app's private files;
 * the private key never leaves the device. Approved once in adbd with the normal "Allow USB
 * debugging?" prompt (see {@link LocalAdb#approve}).
 *
 * Holds three things adb needs: the RSA key, a self-signed certificate (the TLS client
 * certificate for wireless debugging) and the public key in adb's own format (sent during the
 * one-time approval).
 */
final class AdbKey {

    private static final String KEY_FILE = "adbkey.pk8", CERT_FILE = "adbkey.crt";
    /** SHA-1 DigestInfo prefix: adb signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
            0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    final PrivateKey privateKey;
    final RSAPublicKey publicKey;
    final X509Certificate certificate;

    private AdbKey(PrivateKey priv, X509Certificate cert) {
        privateKey = priv;
        certificate = cert;
        publicKey = (RSAPublicKey) cert.getPublicKey();
    }

    private static File dir(Context c) { return new File(c.getFilesDir(), "adb"); }

    static boolean exists(Context c) {
        return new File(dir(c), KEY_FILE).isFile() && new File(dir(c), CERT_FILE).isFile();
    }

    /** The stored key, or null. */
    static AdbKey load(Context c) {
        if (!exists(c)) return null;
        try {
            File d = dir(c);
            PrivateKey priv = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(new File(d, KEY_FILE).toPath())));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(
                            Files.readAllBytes(new File(d, CERT_FILE).toPath())));
            return new AdbKey(priv, cert);
        } catch (Exception e) {
            RingLog.i(HijackService.TAG, "Fast actions: stored key unreadable: " + e);
            return null;
        }
    }

    /** The stored key, made first if there is none. */
    static synchronized AdbKey loadOrCreate(Context c) throws Exception {
        AdbKey k = load(c);
        if (k != null) return k;
        // The D39-D41 test build kept a key made on a computer here; it isn't used any more.
        new File(dir(c), "key.pk8").delete();
        new File(dir(c), "cert.der").delete();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair kp = g.generateKeyPair();
        byte[] cert = selfSignedCert(kp);
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("mkdir " + d);
        write(new File(d, KEY_FILE), kp.getPrivate().getEncoded());
        write(new File(d, CERT_FILE), cert);
        RingLog.i(HijackService.TAG, "Fast actions: new adb key made");
        return load(c);
    }

    /** Deletes the key (and the files of the D39–D41 test build). The approval in adbd stays. */
    static void forget(Context c) {
        for (String f : new String[]{KEY_FILE, CERT_FILE, "key.pk8", "cert.der"}) {
            new File(dir(c), f).delete();
        }
    }

    private static void write(File f, byte[] b) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    /** Signs adb's AUTH token (RSA PKCS#1 v1.5, token treated as a SHA-1 digest). */
    byte[] signToken(byte[] token) throws Exception {
        byte[] msg = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, msg, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, msg, SHA1_PREFIX.length, token.length);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(privateKey);
        s.update(msg);
        return s.sign();
    }

    /** The public key in adb's format (as in ~/.android/adbkey.pub), with a name, NUL-terminated. */
    byte[] adbPublicKey() {
        final int words = 64; // 2048 bits
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(publicKey.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " FireStickFixer@" + android.os.Build.MODEL;
        byte[] t = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[t.length + 1];
        System.arraycopy(t, 0, out, 0, t.length);
        return out;
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    /** The fingerprint the TV prompt shows (MD5 of the key blob), to check it's ours. */
    String fingerprint() {
        try {
            String s = new String(adbPublicKey(), StandardCharsets.UTF_8);
            byte[] blob = Base64.decode(s.substring(0, s.indexOf(' ')), Base64.DEFAULT);
            byte[] md = java.security.MessageDigest.getInstance("MD5").digest(blob);
            StringBuilder f = new StringBuilder();
            for (int i = 0; i < md.length; i++) f.append(i == 0 ? "" : ":").append(String.format(Locale.US, "%02X", md[i]));
            return f.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- A minimal self-signed X.509 v3 certificate (no extensions), DER by hand ----

    private static byte[] selfSignedCert(KeyPair kp) throws Exception {
        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0c, "FireStickFixer".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 86_400_000L)), time(new Date(now + 100L * 365 * 86_400_000L)));
        byte[] serial = tlv(0x02, new BigInteger(63, new java.security.SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xa0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, sigAlg, name, validity, name, kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, sigAlg, tlv(0x03, bits));
    }

    private static byte[] time(Date d) {
        boolean utc = d.getTime() < 2524608000000L; // before 2050: UTCTime, else GeneralizedTime
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            int n = 0;
            byte[] tmp = new byte[5];
            do { tmp[n++] = (byte) (v & 0x7f); v >>>= 7; } while (v != 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, cat(parts)); }

    private static byte[] set(byte[]... parts) { return tlv(0x31, cat(parts)); }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = v.length;
        if (len < 0x80) {
            o.write(len);
        } else {
            int bytes = len > 0xffff ? 3 : (len > 0xff ? 2 : 1);
            o.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) o.write(len >>> (8 * i));
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }
}
