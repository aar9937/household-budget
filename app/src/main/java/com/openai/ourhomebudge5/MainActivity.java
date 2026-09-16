package com.openai.ourhomebudge5;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 9001;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json","text/plain","application/octet-stream"});
                startActivityForResult(i, FILE_CHOOSER);
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(this), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    public static class Bridge {
        private final Context context;
        Bridge(Context c) { context = c; }
        @JavascriptInterface public String getStoredData() { return context.getSharedPreferences("ourhome", MODE_PRIVATE).getString("data", ""); }
        @JavascriptInterface public void setStoredData(String data) { context.getSharedPreferences("ourhome", MODE_PRIVATE).edit().putString("data", data).apply(); }
        @JavascriptInterface public String getRecoveryData() { return context.getSharedPreferences("ourhome", MODE_PRIVATE).getString("recovery", ""); }
        @JavascriptInterface public void saveBackup(String json) {
            try {
                context.getSharedPreferences("ourhome", MODE_PRIVATE).edit().putString("recovery", json).apply();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "우리집_가계부_백업_" + System.currentTimeMillis() + ".json");
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) try (OutputStream os = context.getContentResolver().openOutputStream(uri)) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) {}
        }
    }

    @Override public void onBackPressed() { if (webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
}
