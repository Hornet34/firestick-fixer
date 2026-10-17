// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious, FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows the system's activity-start log to see what no accessibility event shows:
 *
 *  - every Home press, exactly once: {@code START u0 {act=android.intent.action.MAIN
 *    cat=[android.intent.category.HOME] ... HomeActivity_vNext ...} from uid 0}. The
 *    window events Amazon home fires instead repeat for up to ~1 s per press, which is
 *    why a double-press could not be told from one press reliably without this.
 *  - every remote app button: Amazon routes it through
 *    {@code com.amazon.venezia/.pdi.AppLaunchActivity} with a {@code clickStreamReftag=
 *    r_btn_app_NN} tag (from uid 1000), then starts the app itself from uid 1000. The
 *    app key never reaches an accessibility service (measured on Fire OS 8.1.8.5), so
 *    this is the only way to tell a button press from the user opening the same app.
 *
 * Needs {@code android.permission.READ_LOGS}, a development permission granted once via
 * {@code adb shell pm grant <pkg> android.permission.READ_LOGS}. Without it logcat only
 * returns our own lines, {@link #isAvailable} is false and the service falls back to its
 * window-event heuristics. Read-only: nothing is written or sent anywhere.
 */
final class LogWatcher {

    interface Listener {
        /** A Home press reached the system (one call per press). */
        void onHomePress(long at);

        /** A remote app button opened {@code pkg}; {@code button} is Amazon's tag, e.g. r_btn_app_33. */
        void onButtonLaunch(String button, String pkg, long at);
    }

    private static final String TAG = HijackService.TAG;

    private static final Pattern START = Pattern.compile("START u0 \\{(.*)\\} from uid (\\d+)");
    private static final Pattern PKG = Pattern.compile("(?:pkg|cmp)=([A-Za-z0-9_.]+)");
    private static final Pattern BUTTON_TAG = Pattern.compile("clickStreamReftag=(r_btn_[A-Za-z0-9_]+)");

    /** The app start that follows a button's venezia hop lands within a few ms; allow slack. */
    private static final long BUTTON_TO_APP_MS = 1_500L;
    /** Back-off before restarting a logcat that died, so a broken setup cannot spin. */
    private static final long RESTART_DELAY_MS = 5_000L;

    private final Context ctx;
    private final Handler main;
    private final Listener listener;
    private volatile boolean running;
    private volatile Process proc;
    private Thread thread;

    private String pendingButton;
    private long pendingButtonAt;

    LogWatcher(Context ctx, Handler main, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.listener = listener;
    }

    /** True when the one-time READ_LOGS grant is in place. */
    static boolean isAvailable(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running || !isAvailable(ctx)) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "FireStickFixer-logs");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "Log watcher started (precise Home and button detection)");
    }

    void stop() {
        running = false;
        Process p = proc;
        if (p != null) p.destroy();
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                // -T 1: only lines from now on. Only ActivityTaskManager, at Info, which
                // is all we parse, keeps the pipe quiet.
                // The system buffer only: measured on Fire OS 8.1.8.5, every
                // ActivityTaskManager START line lands there and none in main, which
                // would only double the lines logcat has to read past.
                proc = new ProcessBuilder("logcat", "-b", "system", "-v", "brief",
                        "-T", "1", "ActivityTaskManager:I", "*:S")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    parse(line);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "Log watcher: " + e);
            } finally {
                Process p = proc;
                if (p != null) p.destroy();
                proc = null;
            }
            if (!running) break;
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void parse(String line) {
        Matcher m = START.matcher(line);
        if (!m.find()) return;
        final String intent = m.group(1);
        final String uid = m.group(2);
        final long now = System.currentTimeMillis();

        if (intent.contains("cat=[android.intent.category.HOME]") && "0".equals(uid)) {
            main.post(new Runnable() {
                @Override public void run() { listener.onHomePress(now); }
            });
            return;
        }

        Matcher b = BUTTON_TAG.matcher(intent);
        if (b.find() && "1000".equals(uid)) {
            pendingButton = b.group(1);
            pendingButtonAt = now;
            return;
        }

        if (pendingButton != null && "1000".equals(uid)
                && now - pendingButtonAt < BUTTON_TO_APP_MS) {
            Matcher p = PKG.matcher(intent);
            if (p.find()) {
                String pkg = p.group(1);
                // cmp=pkg/.Activity: keep the package part only.
                int slash = pkg.indexOf('/');
                if (slash > 0) pkg = pkg.substring(0, slash);
                if (!pkg.equals("com.amazon.venezia")) {
                    final String button = pendingButton;
                    final String app = pkg;
                    pendingButton = null;
                    main.post(new Runnable() {
                        @Override public void run() { listener.onButtonLaunch(button, app, now); }
                    });
                }
            }
        }
    }
}
