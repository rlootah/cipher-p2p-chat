package ae.cipher.chat;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_FILE_CHOOSER = 2;

    private WebView webView;
    private PermissionRequest pendingWebPermissionRequest;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = CipherApp.get().getWebView();
        // The shared WebView may still be attached to a previous activity
        // instance (rotation, relaunch from notification) — detach first.
        if (webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        setContentView(webView);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // A reload wipes the JS state that owns the call, so it can
                // never send setCallAudio(false). Without this the phone
                // stays in communication mode with the proximity sensor
                // blanking the screen indefinitely.
                CipherApp.get().resetCallAudio();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // The render process died; the WebView object is unusable.
                // Release audio/proximity and rebuild on next launch.
                CipherApp.get().resetCallAudio();
                CipherApp.get().discardWebView();
                finish();
                return true; // handled — do not let the process be killed
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                // Exact host match only. A substring test (APP_URL.contains(host))
                // also accepted "railway.app" and "up.railway.app", handing the
                // native CipherBlob/CipherNative bridges to any page hosted there.
                if (CipherApp.isTrustedUrl(url)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (Exception ignored) {}
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (hasMicPermission()) {
                            request.grant(request.getResources());
                        } else {
                            pendingWebPermissionRequest = request;
                            requestNeededPermissions();
                        }
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select file"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimetype, long contentLength) {
                if (url.startsWith("blob:")) {
                    String js = "(function(){var x=new XMLHttpRequest();x.open('GET','" + url + "',true);" +
                            "x.responseType='blob';x.onload=function(){var r=new FileReader();" +
                            "r.onloadend=function(){CipherBlob.saveBase64(r.result.split(',')[1]," +
                            "x.response.type||'application/octet-stream');};r.readAsDataURL(x.response);};x.send();})();";
                    webView.evaluateJavascript(js, null);
                    Toast.makeText(MainActivity.this, "Saving file…", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                        ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                    } catch (Exception ignored) {}
                }
            }
        });

        requestNeededPermissions();

        // Only load when the shared WebView has no page yet. The flag used
        // to live on the Activity, so every relaunch (back-press then
        // tapping a notification) reloaded the app and destroyed the room,
        // mesh and any in-progress call that the service had kept alive.
        if (webView.getUrl() == null) {
            webView.loadUrl(CipherApp.APP_URL);
        }

        // Keep the process (and the mesh) alive while backgrounded.
        try {
            Intent svc = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception ignored) {}
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> wanted = new ArrayList<>();
        if (!hasMicPermission()) {
            wanted.add(android.Manifest.permission.RECORD_AUDIO);
            wanted.add(android.Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMISSIONS && pendingWebPermissionRequest != null) {
            if (hasMicPermission()) {
                pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
            } else {
                pendingWebPermissionRequest.deny();
            }
            pendingWebPermissionRequest = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CipherApp.get().activityVisible = true;
        webView.resumeTimers();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        CipherApp.get().activityVisible = false;
        // Deliberately NOT calling webView.onPause()/pauseTimers() — the
        // whole point is to keep the page running in the background under
        // the foreground service.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Detach but do not destroy — the WebView lives in CipherApp and
        // keeps the room connection while the service is up.
        if (webView != null && webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
