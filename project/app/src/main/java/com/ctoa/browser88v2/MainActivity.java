package com.ctoa.browser88v2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSIONS  = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        // Core capabilities. Every one of these matters for app-like HTML.
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // The two flags below are CRITICAL for file uploads and modern JS.
        // Without them, libraries that read local files silently fail and
        // <input type="file"> appears to do nothing on many pages.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        // JS bridge for saveAPK / notify from the web layer.
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        // ------------------------------------------------
        // WebChromeClient: THE part that makes file uploads work.
        // onShowFileChooser is called when the page taps an
        // <input type="file">. We must return true and later
        // feed the result back via filePathCallback.
        // ------------------------------------------------
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                cameraOutputUri = null;

                Intent contentIntent = params.createIntent();
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);

                // Multiple selection is signaled by the file chooser params.
                boolean allowMultiple = false;
                try {
                    allowMultiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                } catch (Throwable t) { /* older APIs */ }

                Intent chooser;
                String[] accept = params.getAcceptTypes();
                boolean imageOnly = accept != null && accept.length > 0;
                if (imageOnly) {
                    // Best-effort: keep only image/video MIME types so we
                    // can safely offer a camera option alongside the picker.
                    List<String> mimes = new ArrayList<>();
                    for (String a : accept) {
                        if (a == null) continue;
                        if (a.startsWith("image") || a.startsWith("video")) mimes.add(a);
                    }
                    if (!mimes.isEmpty()) contentIntent.setType(mimes.get(0));
                }

                if (allowMultiple) contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                chooser = Intent.createChooser(contentIntent, "Select file");
                try {
                    startActivityForResult(chooser, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker available", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            // Camera + microphone permission requests from getUserMedia.
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try { request.grant(request.getResources()); }
                    catch (Exception e) { request.deny(); }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                android.util.Log.d("CtoA-WebView", cm.message() + " @ " + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String u, String m, final android.webkit.JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(m)
                    .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                    .setCancelable(false)
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String u, String m, final android.webkit.JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(m)
                    .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                    .setCancelable(false)
                    .show();
                return true;
            }
        });

        // External links open in the system browser.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    if (uri.toString().startsWith("file://")) return false;
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception e) { return false; }
                }
                return false;
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(webView);
        setContentView(root);

        requestRuntimePermissions();
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ------------------------------------------------------------
    // File chooser result. Handles single files, multiple files,
    // and camera-captured images uniformly.
    // ------------------------------------------------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        if (requestCode == REQ_FILE_CHOOSER){
            if (filePathCallback == null){
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK){
                if (data == null || (data.getData() == null && data.getClipData() == null)){
                    if (cameraOutputUri != null) results = new Uri[]{ cameraOutputUri };
                } else if (data.getClipData() != null){
                    ClipData clip = data.getClipData();
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++){
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null){
                    results = new Uri[]{ data.getData() };
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            cameraOutputUri = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------
    // Runtime permissions. Different on each Android version.
    // ------------------------------------------------------------
    private void requestRuntimePermissions(){
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33){
            if (!hasPermission("android.permission.READ_MEDIA_IMAGES")) needed.add("android.permission.READ_MEDIA_IMAGES");
            if (!hasPermission("android.permission.READ_MEDIA_VIDEO"))  needed.add("android.permission.READ_MEDIA_VIDEO");
            if (!hasPermission("android.permission.READ_MEDIA_AUDIO"))  needed.add("android.permission.READ_MEDIA_AUDIO");
        } else if (Build.VERSION.SDK_INT >= 23){
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE))  needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) && Build.VERSION.SDK_INT <= 28){
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasPermission(Manifest.permission.CAMERA)){
            needed.add(Manifest.permission.CAMERA);
        }
        if (!needed.isEmpty()){
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private boolean hasPermission(String p){
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS){
            for (int i = 0; i < permissions.length; i++){
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED){
                    android.util.Log.w("CtoA", "permission denied: " + permissions[i]);
                }
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()){
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause(){ super.onPause(); if (webView != null) webView.onPause(); }
    @Override protected void onResume(){ super.onResume(); if (webView != null) webView.onResume(); }
    @Override protected void onDestroy(){ if (webView != null){ webView.destroy(); webView = null; } super.onDestroy(); }

    // ------------------------------------------------------------
    // JavaScript bridge. Optional helpers for CtoA-style pages.
    // A plain HTML app that never calls these still works fine.
    // ------------------------------------------------------------
    public class AndroidBridge {

        @JavascriptInterface
        public String getVersion(){ return "1.0"; }

        @JavascriptInterface
        public void notify(final String title, final String body){
            runOnUiThread(() -> {
                try { Toast.makeText(MainActivity.this, title + ": " + body, Toast.LENGTH_SHORT).show(); }
                catch (Exception ignored){}
            });
        }

        @JavascriptInterface
        public void saveAPK(final String base64, final String filename){
            runOnUiThread(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    String clean = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
                    if (!clean.toLowerCase().endsWith(".apk")) clean += ".apk";

                    java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File out = new java.io.File(dir, clean);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    fos.write(bytes);
                    fos.close();

                    Toast.makeText(MainActivity.this, "Saved: " + clean, Toast.LENGTH_LONG).show();

                    try {
                        Uri uri = Uri.fromFile(out);
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(uri, "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(install);
                    } catch (Exception e){
                        Toast.makeText(MainActivity.this, "Open it from Downloads", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e){
                    Toast.makeText(MainActivity.this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openExternal(final String url){
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored){}
            });
        }
    }
}
