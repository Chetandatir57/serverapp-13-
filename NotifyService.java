package dev.mccontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Keeps a light connection open to the event-news channel and shows every announcement in the phone's notification bar. */
public class NotifyService extends Service {
    private static final String CH_LIVE = "listen";
    private static final String CH_NEWS = "news";
    private volatile boolean running = false;
    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        Notification n = builder(CH_LIVE)
                .setContentTitle("MC Control")
                .setContentText("Listening for tournament news")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "NotifyLoop");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel live = new NotificationChannel(CH_LIVE, "Background connection", NotificationManager.IMPORTANCE_MIN);
        live.setShowBadge(false);
        nm.createNotificationChannel(live);
        NotificationChannel news = new NotificationChannel(CH_NEWS, "Tournament news", NotificationManager.IMPORTANCE_HIGH);
        news.enableVibration(true);
        nm.createNotificationChannel(news);
    }

    private String topic() {
        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        String code = p.getString("url", getString(R.string.default_url)).trim();
        if (code.isEmpty() || code.startsWith("http")) return "";
        return code + "-n";
    }

    private void loop() {
        SharedPreferences prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        int wait = 3000;
        while (running) {
            String topic = topic();
            if (topic.isEmpty()) {
                sleep(30000);
                continue;
            }
            try {
                String since = prefs.getString("nlast", "");
                if (since.isEmpty()) since = String.valueOf(System.currentTimeMillis() / 1000);
                URL url = new URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8") + "/json?since=" + URLEncoder.encode(since, "UTF-8"));
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(90000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                wait = 3000;
                String line;
                while (running && (line = r.readLine()) != null) {
                    try {
                        JSONObject o = new JSONObject(line);
                        if (!"message".equals(o.optString("event"))) continue;
                        String id = o.optString("id");
                        if (!id.isEmpty()) prefs.edit().putString("nlast", id).apply();
                        show(o.optString("title", "MC Control"), o.optString("message", ""));
                    } catch (Exception ignored) {
                    }
                }
                r.close();
            } catch (Exception e) {
                // network dropped: try again below
            }
            sleep(wait);
            wait = Math.min(wait * 2, 60000);
        }
    }

    private void show(String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification n = builder(CH_NEWS)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build();
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
        } catch (Exception ignored) {
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
