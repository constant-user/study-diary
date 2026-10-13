package com.constantuser.studydiary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Дневник — локальная Android-обёртка над веб-приложением (WebView).
 *
 * Безопасность:
 *  • Приложение не запрашивает НИ ОДНОГО разрешения (в манифесте нет uses-permission).
 *    Доступа в сеть нет на уровне системы: данные дневника никуда не уходят.
 *  • HTML/JS/CSS раздаются из assets по зарезервированному домену
 *    appassets.androidplatform.net через shouldInterceptRequest — это рекомендованный
 *    Google подход вместо file://, с корректной моделью одинакового происхождения.
 *  • Любой запрос, отличный от локальных assets, отбрасывается пустым ответом:
 *    ни один внешний адрес в приложении не открывается.
 *  • setAllowFileAccess / allowContentAccess выключены — скрипты не могут читать
 *    файлы устройства.
 *  • Экспорт файлов идёт через узкий мост saveFile в папку «Загрузки»
 *    (MediaStore, без разрешений); имя файла очищается от опасных символов.
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final int FILE_CHOOSER_REQUEST = 4214;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);              // приложение — локальный JS
        settings.setDomStorageEnabled(true);              // localStorage дневника
        settings.setAllowFileAccess(false);               // файлы устройства недоступны
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setTextZoom(100);                        // собственный размер шрифта
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient() {
            /* Раздача локальных файлов из assets; всё остальное блокируется */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("https".equals(url.getScheme()) && ASSET_HOST.equals(url.getHost())) {
                    WebResourceResponse response = serveAsset(url.getPath());
                    if (response != null) return response;
                }
                return emptyResponse();
            }

            /* Запрещаем открывать любые внешние адреса */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                return !("https".equals(url.getScheme()) && ASSET_HOST.equals(url.getHost()));
            }
        });

        /* Диалог выбора файла — нужен для импорта резервной копии */
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        /* Мост экспорта: записывает файл в «Загрузки/Дневник» без разрешений */
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void saveFile(String filename, String content) {
                saveToDownloads(filename, content);
            }
        }, "DiaryAndroid");

        if (savedInstanceState == null) {
            webView.loadUrl("https://" + ASSET_HOST + ASSET_PREFIX + "index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    /* ------------------------- assets ------------------------------ */

    private WebResourceResponse serveAsset(String path) {
        if (path == null || !path.startsWith(ASSET_PREFIX)) return null;
        String assetPath = path.substring(ASSET_PREFIX.length());
        if (assetPath.isEmpty() || assetPath.contains("..")) return null;
        try {
            InputStream stream = getAssets().open(assetPath);
            return new WebResourceResponse(mimeFor(assetPath), "UTF-8", stream);
        } catch (IOException e) {
            return emptyResponse();
        }
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "html": return "text/html";
                case "css": return "text/css";
                case "js": return "text/javascript";
                case "svg": return "image/svg+xml";
                case "png": return "image/png";
                case "jpg":
                case "jpeg": return "image/jpeg";
                case "json": return "application/json";
                case "md": return "text/markdown";
                default: break;
            }
        }
        return "application/octet-stream";
    }

    /* ------------------------- экспорт в «Загрузки» --------------- */

    private void saveToDownloads(final String filename, final String content) {
        if (filename == null || content == null) return;

        /* Имя файла очищаем: только безопасные символы, без путей */
        String clean = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isEmpty() || clean.length() > 120) return;

        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, clean);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeFor(clean));
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Дневник");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            notifyResult("Не удалось сохранить файл", false);
            return;
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("нет потока");
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            notifyResult("Сохранено в «Загрузки/Дневник»: " + clean, true);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            notifyResult("Не удалось сохранить файл", false);
        }
    }

    private void notifyResult(final String message, final boolean ok) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message,
                        ok ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* ------------------------- выбор файла (импорт) --------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST) return;

        if (filePathCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{data.getData()};
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    /* ------------------------- системная кнопка «Назад» ----------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            /* Сначала закрываем открытый диалог или поиск внутри страницы */
            webView.evaluateJavascript(
                    "(function(){"
                            + "var d=document.querySelector('dialog[open]');"
                            + "if(d){d.close();return '1';}"
                            + "var s=document.getElementById('searchBar');"
                            + "if(s&&!s.hidden){document.getElementById('searchBtn').click();return '1';}"
                            + "return '0';"
                            + "})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!"\"1\"".equals(value)) {
                                finish();
                            }
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /* ------------------------- жизненный цикл --------------------- */

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
