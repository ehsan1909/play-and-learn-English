package com.playandlearn.english;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int REQUEST_RECORD_AUDIO = 41;

    private WebView webView;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;

    private String pendingWord = null;
    private String lastRecognizedText = "";
    private boolean modelReady = false;
    private boolean pageReady = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (modelReady) {
                    sendStatus("موتور تشخیص گفتار آفلاین آماده است.");
                } else {
                    sendStatus("در حال آماده‌سازی موتور تشخیص گفتار آفلاین...");
                }
            }
        });
        webView.addJavascriptInterface(new VoskBridge(), "VoskBridge");
        webView.loadUrl("file:///android_asset/index.html");

        LibVosk.setLogLevel(LogLevel.WARN);
        loadOfflineModel();
    }

    private void loadOfflineModel() {
        sendStatus("در حال آماده‌سازی مدل انگلیسی آفلاین...");
        StorageService.unpack(this, "model-en-us", "vosk-model",
                loadedModel -> {
                    model = loadedModel;
                    modelReady = true;
                    sendStatus("موتور تشخیص گفتار آفلاین آماده است.");
                    if (pendingWord != null) startWhenReady();
                },
                exception -> {
                    modelReady = false;
                    sendError("بارگذاری مدل گفتار ناموفق بود: " + exception.getMessage());
                });
    }

    private class VoskBridge {
        @JavascriptInterface
        public void startListening(String word) {
            pendingWord = word == null ? "" : word.trim();
            runOnUiThread(() -> {
                if (pendingWord.isEmpty()) {
                    sendError("واژه‌ای برای تشخیص دریافت نشد.");
                    return;
                }
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                    sendStatus("برای استفاده از میکروفون، اجازه ضبط صدا را تأیید کن.");
                    return;
                }
                startWhenReady();
            });
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> {
                pendingWord = null;
                stopRecognition();
            });
        }
    }

    private void startWhenReady() {
        if (pendingWord == null) return;
        if (!modelReady || model == null) {
            sendStatus("مدل گفتار هنوز آماده نشده؛ چند لحظه صبر کن...");
            return;
        }

        stopRecognition();
        lastRecognizedText = "";
        try {
            // Full English recognition is used so the app checks what was actually spoken,
            // rather than forcing the recognizer to return the displayed word.
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            sendStatus("در حال گوش دادن؛ حالا واژه را تلفظ کن...");
            speechService.startListening(this, 5000);
        } catch (IOException | RuntimeException e) {
            stopRecognition();
            sendError("شروع میکروفون ممکن نشد: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWhenReady();
            } else {
                sendError("اجازه میکروفون داده نشد. برای بازی تلفظ باید اجازه ضبط صدا را فعال کنی.");
            }
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text = extractText(hypothesis);
        if (!text.isEmpty()) lastRecognizedText = text;
    }

    @Override
    public void onPartialResult(String hypothesis) {
        sendStatus("صدایت دریافت شد؛ کمی صبر کن...");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        String text = extractText(hypothesis);
        if (!text.isEmpty()) lastRecognizedText = text;
        finishWithResult(lastRecognizedText);
    }

    @Override
    public void onTimeout() {
        String text = lastRecognizedText;
        try {
            if (recognizer != null) {
                String finalJson = recognizer.getFinalResult();
                String finalText = extractText(finalJson);
                if (!finalText.isEmpty()) text = finalText;
            }
        } catch (Exception ignored) { }
        finishWithResult(text);
    }

    @Override
    public void onError(Exception e) {
        stopRecognition();
        sendError("خطا در تشخیص صدا: " + (e == null ? "نامشخص" : e.getMessage()));
    }

    private String extractText(String json) {
        try {
            return new JSONObject(json).optString("text", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void finishWithResult(String text) {
        stopRecognition();
        pendingWord = null;
        if (text == null || text.trim().isEmpty()) {
            sendError("صدایی تشخیص داده نشد. دوباره تلاش کن.");
        } else {
            sendResult(text.trim());
        }
    }

    private void stopRecognition() {
        if (speechService != null) {
            try {
                speechService.stop();
                speechService.shutdown();
            } catch (Exception ignored) { }
            speechService = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void runJs(String js) {
        runOnUiThread(() -> {
            if (webView != null && pageReady) {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void sendStatus(String message) {
        runJs("if(window.onVoskStatus)window.onVoskStatus(" + JSONObject.quote(message) + ");");
    }

    private void sendError(String message) {
        runJs("if(window.onVoskError)window.onVoskError(" + JSONObject.quote(message) + ");");
    }

    private void sendResult(String text) {
        runJs("if(window.onVoskResult)window.onVoskResult(" + JSONObject.quote(text) + ");");
    }

    @Override
    protected void onDestroy() {
        stopRecognition();
        if (model != null) {
            try { model.close(); } catch (Exception ignored) { }
            model = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("VoskBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
