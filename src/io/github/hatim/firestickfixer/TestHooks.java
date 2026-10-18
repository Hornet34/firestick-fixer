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
 *                              backHold, backHoldForce, remap, voice); the test script restores them
 *   alexaApp <pkg>             add/remove <pkg> in "Apps that keep Alexa's results"
 *   prefs export|import <b64>  back up / restore all settings (reinstall tests)
 *   adb <sub> ...              Fast actions: status, setup, test, forcestop, check, free (see fastActions)
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
                else if ("backHoldForce".equals(k)) p.setBackHoldForceStop(v);
                else if ("remap".equals(k)) p.setLaunchKeyEnabled(v);
                else if ("voice".equals(k)) p.setVoiceSearchToApp(v);
                else { pw.println("ERR unknown switch " + k); return; }
                RingLog.i(HijackService.TAG, "Test: " + k + " set to " + v);
                pw.println("OK");
            } else if ("route".equals(cmd) && args.length >= 3) {
                Prefs p = new Prefs(svc);
                if ("clear".equals(args[2])) p.clearVoiceRoute(args[1]); else p.setVoiceRouteKind(args[1], args[2]);
                pw.println("OK route=" + p.getVoiceRouteKind(args[1]));
            } else if ("failcard".equals(cmd) && args.length >= 2) {
                svc.testFailCard(args[1]);
                pw.println("OK");
            } else if ("startintent".equals(cmd) && args.length >= 2) {
                // startintent <action> [data-uri]  - starts it from THIS app's uid, to see what a normal app may do.
                android.content.Intent i = new android.content.Intent(args[1]);
                if (args.length >= 3) i.setData(android.net.Uri.parse(args[2]));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                android.content.pm.ResolveInfo ri = svc.getPackageManager().resolveActivity(i, 0);
                pw.println("resolves to: " + (ri == null ? "nothing" : ri.activityInfo.packageName + "/" + ri.activityInfo.name));
                try {
                    svc.startActivity(i);
                    pw.println("OK started");
                } catch (Exception e) {
                    pw.println("REFUSED: " + e);
                }
            } else if ("vpn".equals(cmd)) {
                svc.testVpn(pw, args.length >= 2 ? args[1] : "state");
            } else if ("prefs".equals(cmd) && args.length >= 2) {
                prefsBackup(svc, pw, args);
            } else if ("adb".equals(cmd) && args.length >= 2) {
                fastActions(svc, pw, args);
            } else if ("alexaApp".equals(cmd) && args.length >= 2) {
                pw.println("OK listed=" + new Prefs(svc).toggleAlexaResultsApp(args[1]));
            } else {
                pw.println("ERR unknown command; see TestHooks.java");
            }
        } catch (Exception e) {
            pw.println("ERR " + e);
        }
    }

    /**
     * Fast actions (DECISIONS D42):
     *   adb status                 state, key fingerprint, wireless debugging
     *   adb setup <host> <port>    approve the app's key through the computer relay (tools/setup-adb.sh)
     *   adb test                   one read-only command (getprop ro.product.model)
     *   adb forcestop <pkg>        the hold-Back action on <pkg> (same checks)
     *   adb check <pkg>            why <pkg> may or may not be force-stopped
     *   adb free [keep-pkg]        Free up memory
     *   adb close | forget         end the session / delete the app's key
     *   adb result                 the outcome of the last setup/test/forcestop/free
     * Network work runs off the main thread (dumpsys gives up after 10 s); read it with "adb result".
     */
    private static void fastActions(final HijackService svc, final PrintWriter pw, final String[] args) {
        String sub = args[1];
        if ("status".equals(sub)) {
            AdbKey k = AdbKey.load(svc);
            pw.println("status=" + LocalAdb.status(svc) + " detail=" + LocalAdb.statusDetail()
                    + " key=" + (k == null ? "none" : k.fingerprint())
                    + " adb_wifi_enabled=" + android.provider.Settings.Global.getInt(
                            svc.getContentResolver(), "adb_wifi_enabled", 0)
                    + " holdBackForceStop=" + new Prefs(svc).isBackHoldForceStop());
        } else if ("result".equals(sub)) {
            pw.println(fastResult);
        } else if ("check".equals(sub) && args.length >= 3) {
            String why = AdbActions.whyNotStoppable(svc, args[2]);
            pw.println(why == null ? "OK may be force-stopped" : "NO " + why);
        } else if ("close".equals(sub)) {
            LocalAdb.closeNow("test hook");
            pw.println("OK");
        } else if ("forget".equals(sub)) {
            LocalAdb.closeNow("key deleted");
            AdbKey.forget(svc);
            LocalAdb.setVerified(svc, false);
            LocalAdb.resetStatus();
            pw.println("OK key deleted (adbd still lists it until the keys are cleared)");
        } else if ("setup".equals(sub) && args.length >= 4) {
            final String host = args[2];
            final int port = Integer.parseInt(args[3]);
            fastResult = "RUNNING (answer the prompt on the TV)";
            new Thread(new Runnable() {
                @Override public void run() {
                    fastResult = LocalAdb.approve(svc, host, port);
                    RingLog.i(HijackService.TAG, "Fast actions setup: " + fastResult);
                }
            }, "fast-setup").start();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            AdbKey k = AdbKey.load(svc);
            pw.println("STARTED; key fingerprint " + (k == null ? "(being made)" : k.fingerprint())
                    + "; read it with: adb result");
        } else if ("test".equals(sub) || ("forcestop".equals(sub) && args.length >= 3) || "free".equals(sub)) {
            fastResult = "RUNNING";
            LocalAdb.resetStatus();
            final long t0 = android.os.SystemClock.uptimeMillis();
            final String[] out = {""};
            LocalAdb.submit(svc, "test hook " + sub, new LocalAdb.Job() {
                @Override public void run(LocalAdb.Session s) throws Exception {
                    if ("test".equals(args[1])) {
                        out[0] = AdbActions.model(s);
                    } else if ("forcestop".equals(args[1])) {
                        AdbActions.forceStop(svc, s, args[2]);
                        out[0] = "stopped " + args[2];
                    } else {
                        out[0] = "stopped " + AdbActions.freeMemory(svc, s, args.length >= 3 ? args[2] : null);
                    }
                }
            }, new LocalAdb.Callback() {
                @Override public void done(boolean ok) {
                    fastResult = (ok ? "OK " + out[0] : "FAIL " + LocalAdb.status(svc) + " " + LocalAdb.statusDetail())
                            + " in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms";
                }
            });
            pw.println("STARTED; read it with: adb result");
        } else {
            pw.println("ERR adb status|setup <host> <port>|test|forcestop <pkg>|check <pkg>|free [keep]|close|forget|result");
        }
    }

    /**
     * prefs export            all settings as one base64 line (JSON of every SharedPreferences file)
     * prefs import <base64>   writes them back (replaces each file's contents)
     * For reinstall tests: export, uninstall, install, import. Fast actions' own state is left out,
     * since a reinstall makes a new key.
     */
    private static void prefsBackup(HijackService svc, PrintWriter pw, String[] args) throws Exception {
        java.io.File dir = new java.io.File(svc.getApplicationInfo().dataDir, "shared_prefs");
        if ("export".equals(args[1])) {
            org.json.JSONObject all = new org.json.JSONObject();
            String[] files = dir.list();
            if (files != null) for (String f : files) {
                if (!f.endsWith(".xml") || f.equals("fast_actions.xml")) continue;
                String name = f.substring(0, f.length() - 4);
                org.json.JSONObject one = new org.json.JSONObject();
                for (java.util.Map.Entry<String, ?> e : svc.getSharedPreferences(name, 0).getAll().entrySet()) {
                    Object v = e.getValue();
                    org.json.JSONObject t = new org.json.JSONObject();
                    if (v instanceof java.util.Set) {
                        t.put("t", "set").put("v", new org.json.JSONArray((java.util.Set<?>) v));
                    } else {
                        t.put("t", v.getClass().getSimpleName()).put("v", v);
                    }
                    one.put(e.getKey(), t);
                }
                all.put(name, one);
            }
            pw.println(android.util.Base64.encodeToString(all.toString().getBytes("UTF-8"), android.util.Base64.NO_WRAP));
        } else if ("import".equals(args[1]) && args.length >= 3) {
            org.json.JSONObject all = new org.json.JSONObject(new String(
                    android.util.Base64.decode(args[2], android.util.Base64.DEFAULT), "UTF-8"));
            int n = 0;
            java.util.Iterator<String> files = all.keys();
            while (files.hasNext()) {
                String name = files.next();
                org.json.JSONObject one = all.getJSONObject(name);
                android.content.SharedPreferences.Editor ed = svc.getSharedPreferences(name, 0).edit().clear();
                java.util.Iterator<String> keys = one.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    org.json.JSONObject t = one.getJSONObject(k);
                    String type = t.getString("t");
                    if ("Boolean".equals(type)) ed.putBoolean(k, t.getBoolean("v"));
                    else if ("Integer".equals(type)) ed.putInt(k, t.getInt("v"));
                    else if ("Long".equals(type)) ed.putLong(k, t.getLong("v"));
                    else if ("Float".equals(type)) ed.putFloat(k, (float) t.getDouble("v"));
                    else if ("set".equals(type)) {
                        java.util.Set<String> set = new java.util.HashSet<String>();
                        org.json.JSONArray a = t.getJSONArray("v");
                        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                        ed.putStringSet(k, set);
                    } else ed.putString(k, t.getString("v"));
                    n++;
                }
                ed.commit();
            }
            pw.println("OK imported " + n + " values; restart the app to load them everywhere");
        } else {
            pw.println("ERR prefs export|import <base64>");
        }
    }

    private static volatile String fastResult = "none yet";

}
