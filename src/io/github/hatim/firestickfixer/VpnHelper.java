// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hatim.firestickfixer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the VPN apps on the device and tells whether any VPN is up. Both work for every
 * VPN app, without knowing any of them by name:
 *  - an app is a VPN app when it declares a service for {@code android.net.VpnService}
 *    guarded by {@code BIND_VPN_SERVICE} (the system requires that of every VPN);
 *  - a VPN is up when any network carries the VPN transport.
 * Turning a VPN on or off has no API for other apps (Android allows only one VPN and
 * consent is per app), so {@link HijackService} drives the VPN app's own screen with the
 * button labels listed here.
 */
final class VpnHelper {

    private VpnHelper() {}

    private static final String VPN_ACTION = "android.net.VpnService";

    /** Labels (lower case) of the button that starts a connection, best first. */
    static final String[] CONNECT_LABELS = {
            "quick connect", "connect", "turn on", "start", "enable", "reconnect"};
    /** Labels of the button that ends it. */
    static final String[] DISCONNECT_LABELS = {
            "disconnect", "turn off", "stop", "disable", "pause"};
    /** Buttons of the system's one-time "Connection request" dialog. */
    static final String[] CONSENT_LABELS = {"ok", "allow", "yes"};

    /** Packages with a VPN service, other than this app, in a stable order. */
    static List<String> installedApps(Context ctx) {
        Set<String> pkgs = new LinkedHashSet<String>();
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentServices(new Intent(VPN_ACTION), 0);
        if (found != null) {
            for (ResolveInfo ri : found) {
                if (ri.serviceInfo == null) continue;
                if (!"android.permission.BIND_VPN_SERVICE".equals(ri.serviceInfo.permission)) continue;
                String pkg = ri.serviceInfo.packageName;
                if (!pkg.equals(ctx.getPackageName()) && launchable(pm, pkg)) pkgs.add(pkg);
            }
        }
        return new ArrayList<String>(pkgs);
    }

    /** The intent that opens the VPN app (TV entry first, phone entry as fallback). */
    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(pkg);
        List<ResolveInfo> found = pm.queryIntentActivities(i, 0);
        if (found.isEmpty()) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            found = pm.queryIntentActivities(i, 0);
            if (found.isEmpty()) return null;
        }
        // Explicit component: launcher entries need not carry CATEGORY_DEFAULT, so an
        // implicit startActivity can fail (Proton TV's does).
        ActivityInfo ai = found.get(0).activityInfo;
        i.setComponent(new ComponentName(ai.packageName, ai.name));
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean launchable(PackageManager pm, String pkg) {
        return launchIntent(pm, pkg) != null;
    }

    /**
     * The VPN app to use: the user's pick if it is still installed, else the only
     * installed one, else null (none, or several and none chosen).
     */
    static String resolveApp(Context ctx, Prefs prefs) {
        List<String> apps = installedApps(ctx);
        String chosen = prefs.getVpnApp();
        if (chosen != null && !chosen.isEmpty() && apps.contains(chosen)) return chosen;
        return apps.size() == 1 ? apps.get(0) : null;
    }

    /** True while any VPN network is up, whichever app made it. */
    static boolean isConnected(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network[] nets = cm.getAllNetworks();
        if (nets == null) return false;
        for (Network n : nets) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    /**
     * Whether a screen label is one of {@code labels}. The label has to be the whole
     * text (or start with it, for "Quick connect to Germany"), and the opposite action
     * never matches: "Disconnect" is not a "connect" button.
     */
    static boolean matches(CharSequence text, String[] labels, boolean wantConnect) {
        if (text == null) return false;
        String t = text.toString().trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.length() > 40) return false;
        if (wantConnect && (t.contains("disconnect") || t.contains("turn off")
                || t.contains("not connected"))) {
            // "Not connected" is a status line, not a button; keep the rest out too.
            return false;
        }
        for (String l : labels) {
            if (t.equals(l) || t.startsWith(l + " ")) return true;
        }
        return false;
    }
}
