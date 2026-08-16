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
    /** The one host allowed to run inside the WebView (and reach the JS bridges). */
    public static final String APP_HOST = "cipher-p2p-chat-production.up.railway.app";

    /** Exact-host + https check for anything we keep inside the WebView. */
    public static boolean isTrustedUrl(android.net.Uri url) {
        if (url == null) return false;
        String host = url.getHost();
        String scheme = url.getScheme();
        return APP_HOST.equalsIgnoreCase(host) && "https".equalsIgnoreCase(scheme);
    }
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

    /** True when a page is actually loaded and running. */
    public boolean hasLiveWebView() {
        return webView != null && webView.getUrl() != null;
    }

    /** Force audio routing and the proximity lock back to their idle state. */
    public void resetCallAudio() {
        applyCallAudio(false, false);
    }

    /** Drop a dead WebView so the next launch builds a fresh one. */
    public void discardWebView() {
        main.post(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    if (webView.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) webView.getParent()).removeView(webView);
                    }
                    webView.destroy();
                } catch (Exception ignored) {}
                webView = null;
            }
        });
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

    // Turns the screen off when the phone is held to the ear during a
    // call — same behavior as the system dialer. Only active while on
    // the earpiece; disabled on loudspeaker (you're looking at the phone).
    private android.os.PowerManager.WakeLock proximityLock;

    private void updateProximityLock(boolean wantOn) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (wantOn) {
                if (proximityLock == null) {
                    if (!pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
                    proximityLock = pm.newWakeLock(
                            android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "cipher:proximity");
                    proximityLock.setReferenceCounted(false);
                }
                if (!proximityLock.isHeld()) proximityLock.acquire();
            } else if (proximityLock != null && proximityLock.isHeld()) {
                // Wait for the sensor to report "far" so the screen doesn't
                // flash on while the phone is still against the ear.
                proximityLock.release(android.os.PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Route call audio like a phone: MODE_IN_COMMUNICATION + earpiece by
     * default, loudspeaker only when the user toggles it. Runs on the
     * main thread because JS-interface calls arrive on a WebView thread.
     */
    private void applyCallAudio(final boolean inCall, final boolean speakerOn) {
        main.post(new Runnable() {
            @Override public void run() {
                updateProximityLock(inCall && !speakerOn);
                try {
                    android.media.AudioManager am =
                            (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (inCall) {
                        am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                        if (Build.VERSION.SDK_INT >= 31) {
                            if (speakerOn) {
                                for (android.media.AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                                    if (d.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                        am.setCommunicationDevice(d);
                                        break;
                                    }
                                }
                            } else {
                                am.clearCommunicationDevice(); // default = earpiece/headset
                            }
                        } else {
                            am.setSpeakerphoneOn(speakerOn);
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                        else am.setSpeakerphoneOn(false);
                        am.setMode(android.media.AudioManager.MODE_NORMAL);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /** Called from the page (guarded `window.CipherNative` hooks in index.html). */
    private class NativeBridge {
        @JavascriptInterface
        public void setCallAudio(boolean inCall) {
            // Entering a call defaults to the earpiece (speaker off).
            applyCallAudio(inCall, false);
        }

        @JavascriptInterface
        public void setSpeaker(boolean on) {
            applyCallAudio(true, on);
        }

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
