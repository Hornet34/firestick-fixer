// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Commands for the automated device test (tools/device-test.sh), reached through
 *   adb shell dumpsys activity service io.github.hatim.firestickfixer/.HijackService <cmd> ...
 *
 * Why this exists: remote keys injected with "adb shell input" never pass through an
 * accessibility service's key filter, and Alexa's page cannot be opened by adb, so the
 * features built on those (Back handling, hold-Back, voice search, button remapping) could
 * only be tested by hand with the remote. These hooks drive the very same code paths.
 *
 * Safety: dumpsys requires android.permission.DUMP, which only the shell and the system
 * hold, so no app on the device can reach these commands.
 *
 * Commands:
 *   state                      live configuration and state
 *   log | clearlog             the in-memory log / empty it
 *   key BACK tap|hold          a remote Back press (tap ~80 ms, hold ~1.2 s)
 *   voice <pkg> <words...>     voice hand-off to <pkg> as if Alexa heard <words>
 *   button <pkg>               as if a remote app button just opened <pkg>
 */
final class TestHooks {

    private TestHooks() {}

    static void run(final HijackService svc, PrintWriter pw, final String[] args) {
        String cmd = args[0];
        try {
            if ("state".equals(cmd)) {
                svc.testState(pw);
            } else if ("log".equals(cmd)) {
                RingLog.dump(pw);
            } else if ("clearlog".equals(cmd)) {
                RingLog.clear();
                pw.println("OK");
            } else if ("key".equals(cmd) && args.length >= 3) {
                final int code = KeyEvent.keyCodeFromString("KEYCODE_" + args[1]);
                final long holdMs = "hold".equals(args[2]) ? 1_200L : 80L;
                final long down = System.currentTimeMillis();
                final boolean[] taken = new boolean[1];
                onMain(new Runnable() {
                    @Override public void run() {
                        taken[0] = svc.testKey(code, KeyEvent.ACTION_DOWN, 0, down);
                    }
                });
                sleep(holdMs);
                onMain(new Runnable() {
                    @Override public void run() {
                        svc.testKey(code, KeyEvent.ACTION_UP, 0, down);
                    }
                });
                pw.println("OK taken=" + taken[0]);
            } else if ("voice".equals(cmd) && args.length >= 3) {
                StringBuilder q = new StringBuilder();
                for (int i = 2; i < args.length; i++) q.append(i > 2 ? " " : "").append(args[i]);
                final String query = q.toString();
                onMain(new Runnable() {
                    @Override public void run() { svc.testVoice(args[1], query); }
                });
                pw.println("OK");
            } else if ("button".equals(cmd) && args.length >= 2) {
                onMain(new Runnable() {
                    @Override public void run() { svc.testButton(args[1]); }
                });
                pw.println("OK");
            } else {
                pw.println("ERR unknown command; see TestHooks.java");
            }
        } catch (Exception e) {
            pw.println("ERR " + e);
        }
    }

    /** dumpsys calls in on a binder thread; the service's state lives on the main thread. */
    private static void onMain(final Runnable r) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } finally {
                    done.countDown();
                }
            }
        });
        done.await(3, TimeUnit.SECONDS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
