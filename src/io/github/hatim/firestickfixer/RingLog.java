// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.util.Log;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * The service's recent log lines, kept in memory. Fire OS keeps app logcat for only
 * ~14 seconds (a 256 KB buffer Amazon's own apps fill continuously), so a problem from a
 * minute ago is gone by the time anyone looks. These lines stay until the service stops
 * and can be read at any time with:
 *   adb shell dumpsys activity service io.github.hatim.firestickfixer/.HijackService
 */
final class RingLog {

    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>(MAX_LINES);
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private RingLog() {}

    /** Logcat line plus a copy in the ring. */
    static void i(String tag, String msg) {
        Log.i(tag, msg);
        synchronized (LINES) {
            if (LINES.size() >= MAX_LINES) LINES.removeFirst();
            LINES.addLast(TIME.format(new Date()) + " " + msg);
        }
    }

    static void dump(PrintWriter pw) {
        synchronized (LINES) {
            for (String line : LINES) pw.println(line);
        }
    }
}
