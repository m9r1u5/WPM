package com.m9r1u5.wpm;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String RELEASES = "https://api.github.com/repos/m9r1u5/WPM/releases/latest";
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long downloadId = -1;
    private BroadcastReceiver doneReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(6, 4, 15));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "WPMNative");
        setContentView(web);
        hideBars();
        web.loadUrl("file:///android_asset/index.html");
    }

    private int versionCode() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private void toJs(JSONObject o) {
        final String js = "window.__wpmUpdate&&window.__wpmUpdate(" + o.toString() + ")";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    private void toJs(String status, String message) {
        try { JSONObject o = new JSONObject(); o.put("status", status); if (message != null) o.put("message", message); toJs(o); }
        catch (Exception ignored) { }
    }

    private static int lastNumber(String tag) {
        String[] parts = tag.replaceAll("[^0-9.]", "").split("\\.");
        try { return Integer.parseInt(parts[parts.length - 1]); } catch (Exception e) { return -1; }
    }

    class Bridge {
        @JavascriptInterface
        public String version() { return versionName(); }

        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "WPM-Android");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    if (c.getResponseCode() != 200) { toJs("error", "GitHub answered " + c.getResponseCode() + ". Try again in a few minutes."); return; }
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject rel = new JSONObject(sb.toString());
                    String tag = rel.optString("tag_name", "");
                    String url = null;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                    }
                    JSONObject o = new JSONObject();
                    o.put("latest", tag.replaceFirst("^v", ""));
                    o.put("current", versionName());
                    if (lastNumber(tag) > versionCode() && url != null) { o.put("status", "available"); o.put("url", url); }
                    else o.put("status", "latest");
                    toJs(o);
                } catch (Exception e) {
                    toJs("error", null);
                }
            }).start();
        }

        @JavascriptInterface
        public void installUpdate(String url) {
            ui.post(() -> startDownload(url));
        }
    }

    private void startDownload(String url) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            toJs("permission", null);
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("WPM update");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "WPM-" + System.currentTimeMillis() + ".apk");
            downloadId = dm.enqueue(req);
            listenForDone(dm);
            pollProgress(dm);
        } catch (Exception e) {
            // Fall back to the browser
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            toJs("error", "Opened the download in your browser. Install WPM.apk from there.");
        }
    }

    private void pollProgress(DownloadManager dm) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (downloadId < 0) return;
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                    if (c != null && c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (st == DownloadManager.STATUS_FAILED) { downloadId = -1; toJs("error", "The download failed. Check your connection and try again."); return; }
                        if (st == DownloadManager.STATUS_SUCCESSFUL) return;
                        long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        JSONObject o = new JSONObject();
                        o.put("status", "progress");
                        o.put("pct", total > 0 ? (int) (done * 100 / total) : 0);
                        toJs(o);
                    }
                } catch (Exception ignored) { }
                ui.postDelayed(this, 400);
            }
        }, 300);
    }

    private void listenForDone(DownloadManager dm) {
        if (doneReceiver != null) try { unregisterReceiver(doneReceiver); } catch (Exception ignored) { }
        doneReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                downloadId = -1;
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) { toJs("error", "The download failed. Check your connection and try again."); return; }
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(apk, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(install); toJs("installing", null); }
                catch (Exception e) { toJs("error", "Could not open the installer. Open WPM update from your notifications."); }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(doneReceiver, f);
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() {
        if (doneReceiver != null) try { unregisterReceiver(doneReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
