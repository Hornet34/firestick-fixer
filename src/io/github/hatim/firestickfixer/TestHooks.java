// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.PrintWriter;

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
 * Runs on the app's main thread (that is where dumpsys requests are delivered).
 *
 * Commands:
 *   state                      live configuration and state
 *   log | clearlog             the in-memory log / empty it
 *   key BACK tap|hold          a remote Back press (tap ~80 ms, hold ~1.2 s)
 *   voice <pkg> <words...>     voice hand-off to <pkg> as if Alexa heard <words>
 *   button <pkg>               as if a remote app button just opened <pkg>
 *   set <switch> true|false    flip a feature switch (hijack, doubleTap, backStays,
 *                              backHold, remap, voice); the test script restores them
 *   alexaApp <pkg>             add/remove <pkg> in "Apps that keep Alexa's results"
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
                // dumpsys runs on the app's main thread (ActivityThread posts DUMP_SERVICE to
                // it), the same thread the key logic and its hold timer use. So: press now,
                // schedule the release, return at once. Waiting here would block the very
                // timer under test.
                final int code = KeyEvent.keyCodeFromString("KEYCODE_" + args[1]);
                final long holdMs = "hold".equals(args[2]) ? 1_200L : 80L;
                final long down = System.currentTimeMillis();
                boolean taken = svc.testKey(code, KeyEvent.ACTION_DOWN, 0, down);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        svc.testKey(code, KeyEvent.ACTION_UP, 0, down);
                    }
                }, holdMs);
                pw.println("OK taken=" + taken + " release in " + holdMs + "ms");
            } else if ("voice".equals(cmd) && args.length >= 3) {
                StringBuilder q = new StringBuilder();
                for (int i = 2; i < args.length; i++) q.append(i > 2 ? " " : "").append(args[i]);
                final String query = q.toString();
                svc.testVoice(args[1], query);
                pw.println("OK");
            } else if ("button".equals(cmd) && args.length >= 2) {
                svc.testButton(args[1]);
                pw.println("OK");
            } else if ("set".equals(cmd) && args.length >= 3) {
                boolean v = Boolean.parseBoolean(args[2]);
                Prefs p = new Prefs(svc);
                String k = args[1];
                if ("hijack".equals(k)) p.setHijackEnabled(v);
                else if ("doubleTap".equals(k)) p.setEscapeDoublePress(v);
                else if ("backStays".equals(k)) p.setBackStaysOnLauncher(v);
                else if ("backHold".equals(k)) p.setBackHoldExit(v);
                else if ("remap".equals(k)) p.setLaunchKeyEnabled(v);
                else if ("voice".equals(k)) p.setVoiceSearchToApp(v);
                else { pw.println("ERR unknown switch " + k); return; }
                RingLog.i(HijackService.TAG, "Test: " + k + " set to " + v);
                pw.println("OK");
            } else if ("alexaApp".equals(cmd) && args.length >= 2) {
                pw.println("OK listed=" + new Prefs(svc).toggleAlexaResultsApp(args[1]));
            } else {
                pw.println("ERR unknown command; see TestHooks.java");
            }
        } catch (Exception e) {
            pw.println("ERR " + e);
        }
    }

}
