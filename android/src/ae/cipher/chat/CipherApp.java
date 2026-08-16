package ae.cipher.chat;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Owns a single app-scoped WebView so the page (and its WebSocket +
 * WebRTC mesh) keeps running while the activity is closed, as long as
 * KeepAliveService holds the process in the foreground. MainActivity
 * attaches/detaches this WebView from its window.
 */
public class CipherApp extends Application {

    public static final String APP_URL = "https://cipher-p2p-chat-production.up.railway.app/";
    public static final String CH_SERVICE = "cipher_service";
    public static final String CH_MESSAGES = "cipher_messages";

    private static CipherApp instance;
    private WebView webView;
    // True while MainActivity is between onResume and onPause — used to
    // suppress notifications when the user is already looking at the chat.
    public volatile boolean activityVisible = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    public static CipherApp get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel svc = new NotificationChannel(CH_SERVICE,
                    "Connection", NotificationManager.IMPORTANCE_MIN);
            svc.setShowBadge(false);
            nm.createNotificationChannel(svc);
            NotificationChannel msgs = new NotificationChannel(CH_MESSAGES,
                    "Messages & calls", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(msgs);
        }
    }

    /** Lazily build the shared WebView (must be called on the main thread). */
    public WebView getWebView() {
        if (webView != null) return webView;
        WebView wv = new WebView(this);
        wv.setBackgroundColor(0xFF0A0D0C);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        wv.addJavascriptInterface(new BlobSaver(), "CipherBlob");
        wv.addJavascriptInterface(new NativeBridge(), "CipherNative");
        // Keep JS timers running even when the view is detached/backgrounded.
        wv.resumeTimers();
        webView = wv;
        return wv;
    }

    public void toast(final String text) {
        main.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(CipherApp.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void notifyUser(int id, String title, String body) {
        if (activityVisible) return; // user is already looking at the chat
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_MESSAGES)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(id, b.build()); } catch (Exception ignored) {}
    }

    /** Called from the page (guarded `window.CipherNative` hooks in index.html). */
    private class NativeBridge {
        @JavascriptInterface
        public void onMessage(String sender, String body) {
            String preview = body == null ? "" : (body.length() > 80 ? body.substring(0, 80) + "…" : body);
            notifyUser(2, sender == null ? "New message" : sender, preview);
        }

        @JavascriptInterface
        public void onCallInvite(String sender) {
            notifyUser(3, "Incoming voice call",
                    (sender == null ? "Someone" : sender) + " is inviting you to voice");
        }
    }

    /** Saves blob: downloads handed over from the page (see MainActivity's DownloadListener). */
    private class BlobSaver {
        @JavascriptInterface
        public void saveBase64(String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String ext = mime != null && mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : "bin";
                if (ext.length() > 5 || ext.isEmpty()) ext = "bin";
                String name = "cipher_" + System.currentTimeMillis() + "." + ext;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        try { os.write(bytes); } finally { os.close(); }
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream fos = new FileOutputStream(new File(dir, name));
                    try { fos.write(bytes); } finally { fos.close(); }
                }
                toast("Saved to Downloads: " + name);
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }
}
