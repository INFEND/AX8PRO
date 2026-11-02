package ru.xarkon.ax8;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

/** Background metadata checker, never silently installs an APK. */
public final class UpdateJob extends JobService {
    private final JobRunGuard runGuard = new JobRunGuard();

    @Override public boolean onStartJob(JobParameters params) {
        final long run = runGuard.begin();
        new Thread(() -> {
            try {
                UpdateManager.Release release = UpdateManager.fetchLatest();
                if (!runGuard.isCurrent(run)) return;
                if (release.versionCode <= UpdateManager.currentCode(this)) return;

                SharedPreferences prefs = getSharedPreferences("ax8_control", MODE_PRIVATE);
                if (release.versionCode <= prefs.getLong("ota_notified_code", 0) || !canNotify()) return;

                // JobScheduler may stop a job while the HTTPS request is in flight.
                // Serialize notification delivery with cancellation so a stopped
                // worker cannot show a stale update or mark it as notified.
                runGuard.runIfCurrent(run, () -> {
                    NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                    manager.createNotificationChannel(new NotificationChannel(
                        "ax8pro_ota", "AX 8 PRO · Обновления", NotificationManager.IMPORTANCE_DEFAULT));
                    Intent intent = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    PendingIntent open = PendingIntent.getActivity(this, 123, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    Notification notification = new Notification.Builder(this, "ax8pro_ota")
                        .setSmallIcon(R.drawable.ic_app)
                        .setContentTitle("AX 8 PRO · обновление " + release.versionName)
                        .setContentText("Откройте приложение, чтобы установить новую версию")
                        .setContentIntent(open).setAutoCancel(true).build();
                    manager.notify(123, notification);
                    prefs.edit().putLong("ota_notified_code", release.versionCode).apply();
                });
            } catch (Exception ignored) {
                // A later scheduled check will retry transient network failures.
            } finally {
                // Do not finish an already stopped job or an older replaced run.
                runGuard.runIfCurrent(run, () -> jobFinished(params, false));
            }
        }, "ax8pro-ota-job").start();
        return true;
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public boolean onStopJob(JobParameters params) {
        runGuard.cancel();
        return true;
    }

    @Override public void onDestroy() {
        runGuard.cancel();
        super.onDestroy();
    }
}
