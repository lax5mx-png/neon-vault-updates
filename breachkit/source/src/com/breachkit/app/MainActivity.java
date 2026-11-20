package com.breachkit.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Breach Kit shell.
 * The game is one HTML file. It is always loaded with the same base URL
 * (file:///android_asset/index.html) so localStorage (the save) never changes origin,
 * whether the HTML comes from the APK or from a downloaded update.
 *
 * Updates come from breachkit/update.json in github.com/lax5mx-png/neon-vault-updates:
 *  - "content": new game HTML. Downloaded, SHA-256 checked, applied when no job is running. Silent.
 *  - "apk": new app build. Installed with PackageInstaller. Android asks once; after the app
 *    becomes its own installer (Android 12+), later updates install silently when the app is closed.
 */
public class MainActivity extends Activity {
  static final String UPDATE_URL = "https://raw.githubusercontent.com/lax5mx-png/neon-vault-updates/main/breachkit/update.json";
  static final String BASE = "file:///android_asset/index.html";
  static final long CHECK_EVERY = 30L * 60 * 1000;

  WebView web;
  SharedPreferences prefs;
  final Handler ui = new Handler(Looper.getMainLooper());
  volatile boolean pageReady = false, contentPending = false, checking = false;
  volatile File apkReady = null;
  volatile String apkVn = null;
  long lastCheck = 0;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("bk", MODE_PRIVATE);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    web = new WebView(this);
    web.setBackgroundColor(0xFF12080C);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(true);
    s.setTextZoom(100);
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    web.setWebViewClient(new WebViewClient());
    web.setWebChromeClient(new WebChromeClient());
    web.addJavascriptInterface(new Bridge(this), "BK");
    setContentView(web);
    loadGame();
    checkUpdates(false);
  }

  // ---------- content ----------
  int bundledContent() {
    try (InputStream in = getAssets().open("content_version.txt")) { return Integer.parseInt(new String(readAll(in), "UTF-8").trim()); }
    catch (Exception e) { return 0; }
  }
  File webFile() { return new File(getFilesDir(), "web/index.html"); }
  boolean usingDownloaded() { return prefs.getInt("cv", 0) > bundledContent() && webFile().exists(); }
  int contentVersion() { return usingDownloaded() ? prefs.getInt("cv", 0) : bundledContent(); }

  void loadGame() {
    pageReady = false;
    final boolean dl = usingDownloaded();
    String html;
    try {
      if (dl) { try (InputStream in = new FileInputStream(webFile())) { html = new String(readAll(in), "UTF-8"); } }
      else { try (InputStream in = getAssets().open("index.html")) { html = new String(readAll(in), "UTF-8"); } }
    } catch (Exception e) { web.loadUrl(BASE); return; }
    web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
    if (dl) {
      // Safety net: if a downloaded build never reports ready, fall back to the bundled game.
      final int v = prefs.getInt("cv", 0);
      ui.postDelayed(() -> {
        if (!pageReady && usingDownloaded() && prefs.getInt("cv", 0) == v) {
          prefs.edit().putInt("badcv", v).putInt("cv", 0).apply();
          loadGame();
        }
      }, 10000);
    }
  }

  void tryApplyContent() {
    if (!contentPending) return;
    web.evaluateJavascript("(window.__canReload&&window.__canReload())?'1':'0'", v -> {
      if (v != null && v.contains("1")) { contentPending = false; loadGame(); }
      else ui.postDelayed(this::tryApplyContent, 4000);
    });
  }

  // ---------- update check ----------
  void js(String code) { ui.post(() -> web.evaluateJavascript(code, null)); }
  static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

  void checkUpdates(final boolean manual) {
    if (checking) return;
    checking = true;
    lastCheck = System.currentTimeMillis();
    new Thread(() -> {
      String result = "You’re on the latest version";
      try {
        JSONObject j = new JSONObject(new String(get(UPDATE_URL + "?t=" + System.currentTimeMillis()), "UTF-8"));
        String notes = j.optString("notes", "");
        JSONObject c = j.optJSONObject("content");
        if (c != null) {
          int v = c.getInt("v");
          if (v > contentVersion() && v != prefs.getInt("badcv", -1)) {
            if (manual) js("window.__toast&&__toast('Downloading game update…')");
            byte[] data = get(c.getString("url"));
            if (!sha256(data).equalsIgnoreCase(c.getString("sha256"))) throw new Exception("update file failed its security check");
            File f = webFile(); f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(data); }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new Exception("could not save update"); }
            prefs.edit().putInt("cv", v).putString("notes", notes).apply();
            contentPending = true;
            ui.post(this::tryApplyContent);
            result = "Game update downloaded. It applies as soon as you’re not in a job.";
          }
        }
        JSONObject a = j.optJSONObject("apk");
        if (a != null && a.getLong("vc") > myVersionCode()) {
          long vc = a.getLong("vc");
          File f = new File(getCacheDir(), "update-" + vc + ".apk");
          String sha = a.getString("sha256");
          if (!(f.exists() && sha256(readFileBytes(f)).equalsIgnoreCase(sha))) {
            byte[] data = get(a.getString("url"));
            if (!sha256(data).equalsIgnoreCase(sha)) throw new Exception("app update failed its security check");
            try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
          }
          apkReady = f; apkVn = a.optString("vn", "");
          if (canInstallSilently()) result = "App update " + apkVn + " downloaded. It installs automatically when you leave the app.";
          else { js("window.__apkReady&&__apkReady(" + q(apkVn) + ")"); result = null; }
        }
      } catch (Exception e) {
        result = manual ? "Couldn’t check for updates: " + e.getMessage() : null;
      } finally { checking = false; }
      if (manual && result != null) js("window.__toast&&__toast(" + q(result) + ")");
    }).start();
  }

  long myVersionCode() {
    try {
      PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
      return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    } catch (Exception e) { return 0; }
  }
  String myVersionName() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
  }

  // ---------- APK install ----------
  boolean canInstallSilently() {
    if (Build.VERSION.SDK_INT < 31) return false;
    try {
      String ins = getPackageManager().getInstallSourceInfo(getPackageName()).getInstallingPackageName();
      return getPackageName().equals(ins) && getPackageManager().canRequestPackageInstalls();
    } catch (Exception e) { return false; }
  }

  void installApk(File f) {
    if (f == null || !f.exists()) return;
    try {
      PackageInstaller pi = getPackageManager().getPackageInstaller();
      PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      p.setAppPackageName(getPackageName());
      if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
      int id = pi.createSession(p);
      PackageInstaller.Session s = pi.openSession(id);
      try (OutputStream o = s.openWrite("base.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        s.fsync(o);
      }
      Intent i = new Intent(this, InstallReceiver.class);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
      s.commit(PendingIntent.getBroadcast(this, id, i, flags).getIntentSender());
      s.close();
    } catch (Exception e) {
      js("window.__toast&&__toast(" + q("Install failed: " + e.getMessage()) + ")");
    }
  }

  // ---------- lifecycle ----------
  @Override protected void onResume() {
    super.onResume(); web.onResume();
    if (System.currentTimeMillis() - lastCheck > CHECK_EVERY) checkUpdates(false);
    tryApplyContent();
  }
  @Override protected void onPause() { super.onPause(); web.onPause(); }
  @Override protected void onStop() {
    super.onStop();
    if (apkReady != null && canInstallSilently()) { File f = apkReady; apkReady = null; installApk(f); }
  }
  @Override public void onBackPressed() {
    web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", v -> { if (v == null || !v.contains("1")) MainActivity.super.onBackPressed(); });
  }

  // ---------- bridge ----------
  static class Bridge {
    final MainActivity a;
    Bridge(MainActivity a) { this.a = a; }
    @JavascriptInterface public void ready() { a.pageReady = true; }
    @JavascriptInterface public String info() { return "App " + a.myVersionName() + " \u00b7 game build " + a.contentVersion(); }
    @JavascriptInterface public void check() { a.ui.post(() -> a.checkUpdates(true)); }
    @JavascriptInterface public void installNow() { a.ui.post(() -> a.installApk(a.apkReady)); }
    @JavascriptInterface public String takeNotes() { String n = a.prefs.getString("notes", ""); a.prefs.edit().remove("notes").apply(); return n; }
  }

  // ---------- io ----------
  static byte[] get(String u) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
    c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
    c.setRequestProperty("Cache-Control", "no-cache");
    int code = c.getResponseCode();
    if (code != 200) throw new Exception("server said " + code);
    try (InputStream in = c.getInputStream()) { return readAll(in); } finally { c.disconnect(); }
  }
  static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
    while ((n = in.read(b)) > 0) o.write(b, 0, n);
    return o.toByteArray();
  }
  static byte[] readFileBytes(File f) throws Exception { try (InputStream in = new FileInputStream(f)) { return readAll(in); } }
  static String sha256(byte[] d) throws Exception {
    byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
    StringBuilder sb = new StringBuilder(); for (byte x : h) sb.append(String.format("%02x", x)); return sb.toString();
  }
}
