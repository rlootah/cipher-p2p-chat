package ae.cipher.chat;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that keeps the process (and the shared WebView's
 * WebSocket + WebRTC mesh) alive while the activity is backgrounded or
 * swiped away. Holds a partial wake lock so Doze doesn't freeze the
 * connection. Battery cost is real — this is the same trade VoIP apps make.
 */
public class KeepAliveService extends Service {

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CipherApp.CH_SERVICE)
                : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Cipher connected")
                .setContentText("Receiving messages in the background")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= 30) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                // Lets WebRTC keep using the mic during calls with the
                // screen off / app backgrounded (required on Android 11+).
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(1, n, types);
        } else {
            startForeground(1, n);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cipher:keepalive");
        wakeLock.setReferenceCounted(false);
        try { wakeLock.acquire(); } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY can restart us in a brand-new process where no
        // WebView exists — the page (and therefore the connection) is gone.
        // Claiming "Cipher connected" then would be a lie, so stand down
        // and let the user relaunch instead.
        if (flags != 0 && !CipherApp.get().hasLiveWebView()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY; // restart if the system kills us
    }

    @Override
    public void onDestroy() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
