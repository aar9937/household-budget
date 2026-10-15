package com.openai.ourhomebudget.v21;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 9001;
    private static final int PDF_CHOOSER = 9102;
    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private boolean freshInstall;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        freshInstall = !getSharedPreferences("ourhome", MODE_PRIVATE).contains("data");
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectV21();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String type = "application/json";
                String[] accepts = params != null ? params.getAcceptTypes() : null;
                if (accepts != null) {
                    for (String a : accepts) if (a != null && a.contains("pdf")) type = "application/pdf";
                }
                i.setType(type);
                if ("application/json".equals(type)) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json","text/plain","application/octet-stream"});
                startActivityForResult(i, FILE_CHOOSER);
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(this), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void injectV21() {
        try (InputStream is = getAssets().open("v21.js");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            webView.evaluateJavascript(new String(bos.toByteArray(), StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void launchPdfPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        startActivityForResult(i, PDF_CHOOSER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        if (requestCode == PDF_CHOOSER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            processPdf(data.getData());
        }
    }

    private void jsCall(String function, String jsonStringArgument) {
        final String code = function + "(" + JSONObject.quote(jsonStringArgument) + ");";
        runOnUiThread(() -> webView.evaluateJavascript(code, null));
    }

    private void jsProgress(int done, int total) {
        final String code = "window.v21PdfProgress&&window.v21PdfProgress("+done+","+total+");";
        runOnUiThread(() -> webView.evaluateJavascript(code, null));
    }

    private void processPdf(Uri uri) {
        runOnUiThread(() -> webView.evaluateJavascript("window.v21PdfProgress&&window.v21PdfProgress(0,1);", null));
        ocrExecutor.execute(() -> {
            TextRecognizer recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                JSONArray pages = new JSONArray();
                int total = renderer.getPageCount();
                for (int i = 0; i < total; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    int srcW = Math.max(1, page.getWidth());
                    float scale = Math.min(3.0f, Math.max(1.7f, 1800f / srcW));
                    int w = Math.max(1, Math.round(page.getWidth() * scale));
                    int h = Math.max(1, Math.round(page.getHeight() * scale));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();

                    Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)));
                    JSONObject pageJson = new JSONObject();
                    pageJson.put("page", i + 1);
                    pageJson.put("width", w);
                    pageJson.put("height", h);
                    pageJson.put("text", result.getText());
                    JSONArray lines = new JSONArray();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            JSONObject o = new JSONObject();
                            o.put("text", line.getText());
                            Rect r = line.getBoundingBox();
                            if (r != null) {
                                o.put("left", r.left);
                                o.put("top", r.top);
                                o.put("right", r.right);
                                o.put("bottom", r.bottom);
                            } else {
                                o.put("left", 0);
                                o.put("top", 0);
                                o.put("right", 0);
                                o.put("bottom", 0);
                            }
                            lines.put(o);
                        }
                    }
                    pageJson.put("lines", lines);
                    pages.put(pageJson);
                    bmp.recycle();
                    jsProgress(i + 1, total);
                }
                JSONObject payload = new JSONObject();
                payload.put("pages", pages);
                payload.put("pageCount", total);
                payload.put("engine", "MLKit Korean OCR bundled");
                jsCall("window.v21PdfResult", payload.toString());
            } catch (Exception e) {
                jsCall("window.v21PdfError", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            } finally {
                try { recognizer.close(); } catch (Exception ignored) {}
            }
        });
    }

    public class Bridge {
        private final MainActivity activity;
        Bridge(MainActivity a) { activity = a; }

        @JavascriptInterface public String getStoredData() {
            return activity.getSharedPreferences("ourhome", MODE_PRIVATE).getString("data", "");
        }
        @JavascriptInterface public void setStoredData(String data) {
            activity.getSharedPreferences("ourhome", MODE_PRIVATE).edit().putString("data", data).apply();
        }
        @JavascriptInterface public String getRecoveryData() {
            return activity.getSharedPreferences("ourhome", MODE_PRIVATE).getString("recovery", "");
        }
        @JavascriptInterface public boolean isFreshInstall() { return freshInstall; }

        @JavascriptInterface public void saveBackup(String json) {
            try {
                activity.getSharedPreferences("ourhome", MODE_PRIVATE).edit().putString("recovery", json).apply();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "우리집_가계부_백업_" + System.currentTimeMillis() + ".json");
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
                Uri out = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (out != null) try (OutputStream os = activity.getContentResolver().openOutputStream(out)) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public boolean isNotificationAccessEnabled() {
            try {
                String enabled = Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
                return enabled != null && enabled.contains(activity.getPackageName());
            } catch (Exception e) {
                return false;
            }
        }
        @JavascriptInterface public void openNotificationAccess() {
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception e) {
                    activity.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                }
            });
        }
        @JavascriptInterface public String getNotificationQueue() {
            return NotificationCaptureService.getQueue(activity).toString();
        }
        @JavascriptInterface public void clearNotificationQueue() {
            NotificationCaptureService.clearQueue(activity);
        }
        @JavascriptInterface public void removeNotifications(String jsonKeys) {
            NotificationCaptureService.removeKeys(activity, jsonKeys);
        }
        @JavascriptInterface public void openPdfPicker() {
            activity.runOnUiThread(activity::launchPdfPicker);
        }
    }

    @Override protected void onDestroy() {
        ocrExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
