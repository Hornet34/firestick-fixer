// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 FireStick Fixer contributors
package io.github.hatim.firestickfixer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The only commands Fast actions can run (DECISIONS D34, D42). Each is a fixed template; the one
 * variable part, a package name, must pass {@link #whyNotStoppable} first. There is no way to
 * send anything else: no uninstall, clear, disable, suspend, settings, reboot, rm or input.
 */
final class AdbActions {

    private static final Pattern PACKAGE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");

    private AdbActions() {}

    /** Read-only: the model name; used to check that setup works. */
    static String model(LocalAdb.Session s) throws IOException {
        return run(s, "getprop ro.product.model").trim();
    }

    /** Force-stops one app. {@code cmd} talks to the system by binder: ~20 ms, no JVM start like {@code am}. */
    static void forceStop(Context c, LocalAdb.Session s, String pkg) throws IOException {
        String why = whyNotStoppable(c, pkg);
        if (why != null) throw new IllegalArgumentException(pkg + ": " + why);
        run(s, "cmd activity force-stop " + pkg);
    }

    /** Read-only: package names with a running process (process names "pkg" or "pkg:sub"). */
    static Set<String> runningPackages(LocalAdb.Session s) throws IOException {
        Set<String> out = new LinkedHashSet<String>();
        for (String line : run(s, "ps -A -o NAME").split("\n")) {
            String n = line.trim();
            int colon = n.indexOf(':');
            if (colon > 0) n = n.substring(0, colon);
            if (PACKAGE.matcher(n).matches()) out.add(n);
        }
        return out;
    }

    /** Read-only: apps whose media session is playing (state 3), so Free up memory leaves them alone. */
    static Set<String> playingPackages(LocalAdb.Session s) throws IOException {
        Set<String> out = new LinkedHashSet<String>();
        String pkg = null;
        for (String line : run(s, "dumpsys media_session").split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) pkg = t.substring("package=".length()).trim();
            else if (pkg != null && t.contains("state=PlaybackState {state=3")) out.add(pkg);
        }
        return out;
    }

    private static String run(LocalAdb.Session s, String command) throws IOException {
        RingLog.i(HijackService.TAG, "Fast actions: $ " + command);
        return s.shell(command);
    }

    /**
     * Null if the app may be force-stopped, else the reason not to. Never: an invalid or unknown
     * name, a system or Amazon app, this app, the launcher, the keyboard, an app with an enabled
     * accessibility service or notification listener, or the VPN app while a VPN is up.
     */
    static String whyNotStoppable(Context c, String pkg) {
        if (pkg == null || !PACKAGE.matcher(pkg).matches()) return "not a package name";
        if (pkg.equals(c.getPackageName())) return "this app";
        if (pkg.startsWith("com.amazon.") || pkg.startsWith("android") || pkg.startsWith("com.android.")) {
            return "system";
        }
        ApplicationInfo ai;
        try {
            ai = c.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return "system";
        Prefs p = new Prefs(c);
        if (pkg.equals(p.getTargetPackage())) return "the launcher";
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return "the keyboard";
        // A force-stop unbinds these and Android doesn't bind them again by itself.
        for (String key : new String[]{Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "enabled_notification_listeners"}) {
            String v = Settings.Secure.getString(c.getContentResolver(), key);
            if (v != null && (":" + v).contains(":" + pkg + "/")) return "runs an enabled system service";
        }
        if (pkg.equals(VpnHelper.resolveApp(c, p)) && VpnHelper.isConnected(c)) return "the VPN app while connected";
        return null;
    }

    /**
     * Free up memory: force-stops every running app that may be stopped, except {@code keep}
     * (the app on screen) and apps playing media. Returns the names it stopped.
     */
    static List<String> freeMemory(Context c, LocalAdb.Session s, String keep) throws IOException {
        Set<String> running = runningPackages(s);
        Set<String> playing = playingPackages(s);
        List<String> stopped = new ArrayList<String>();
        for (String pkg : running) {
            if (pkg.equals(keep) || playing.contains(pkg) || whyNotStoppable(c, pkg) != null) continue;
            run(s, "cmd activity force-stop " + pkg);
            stopped.add(pkg);
        }
        return stopped;
    }
}
