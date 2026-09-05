package com.example.kebiao;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String CHANNEL_ID = "kb_remind";
    static final String PREFS = "kb_reminders";
    static final String EXTRA_ID = "id";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TEXT = "text";
    static final String ACTION_REMIND = "com.example.kebiao.REMIND";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "KBNative");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("每节课上课前提醒（静音+震动）");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 800});
        ch.setSound(null, null); // 绝对无声
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // 锁屏界面可见
        nm.createNotificationChannel(ch);
    }

    // ===== JS 桥 =====
    class Bridge {
        @JavascriptInterface
        public String version() { return "kb1"; }
        // 原生请求 wttr.in 实时天气（含IP定位城市与温度），结果回调 window.__kbWeather(json)
        @JavascriptInterface
        public void fetchWeather(final String callbackVar) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String res = weatherSync();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try {
                                webView.evaluateJavascript("window." + callbackVar + "(" + res + ");", null);
                            } catch (Exception ignored) {}
                        }
                    });
                }
            }).start();
        }
        private String weatherSync() {
            String json = "{\"ok\":false}";
            InputStream is = null;
            try {
                URL url = new URL("https://wttr.in/Hohhot?format=j1&lang=zh");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(9000);
                c.setReadTimeout(9000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
                int code = c.getResponseCode();
                if (code == 200) {
                    is = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    JSONObject cc = root.getJSONArray("current_condition").getJSONObject(0);
                    String city = "呼和浩特";   // 用户所在城市固定显示
                    String temp = cc.optString("temp_C", "");
                    String codeStr = cc.optString("weatherCode", "");
                    String text = cc.optString("lang_zh", "");
                    try {
                        JSONArray lz = cc.getJSONArray("lang_zh");
                        if (lz.length() > 0) text = lz.getJSONObject(0).optString("value", "");
                    } catch (Exception e) {}
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("city", city);
                    out.put("temp", temp);
                    out.put("code", codeStr);
                    out.put("text", text);
                    json = out.toString();
                }
                c.disconnect();
            } catch (Exception e) {
                json = "{\"ok\":false,\"err\":\"" + String.valueOf(e).replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            } finally {
                try { if (is != null) is.close(); } catch (Exception e) {}
            }
            return json;
        }
        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
                    }
                }
            });
        }

        @JavascriptInterface
        public void vibrate(long ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(ms);
            } catch (Exception ignored) {}
        }

        // 立即发一条系统通知（测试用）
        @JavascriptInterface
        public void notifyNow(int id, String title, String text) {
            postNotification(MainActivity.this, id, title, text);
        }

        // 批量注册提醒：json = [{"id":123,"at":epochMs,"title":"..","text":".."}, ...]
        @JavascriptInterface
        public void scheduleReminders(String json) {
            try {
                List<Reminder> list = new ArrayList<>();
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Reminder r = new Reminder();
                    r.id = o.optInt("id", i);
                    r.at = o.optLong("at", 0);
                    r.title = o.optString("title", "上课提醒");
                    r.text = o.optString("text", "");
                    list.add(r);
                }
                cancelAllInternal(MainActivity.this);
                scheduleList(MainActivity.this, list);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void cancelAll() {
            cancelAllInternal(MainActivity.this);
        }
    }

    // ===== 提醒 =====
    static class Reminder {
        int id;
        long at;
        String title;
        String text;
    }

    static PendingIntent pending(Context ctx, int id) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(ACTION_REMIND);
        i.putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, id & 0xFFFFFF, i, flags);
    }

    static void scheduleList(Context ctx, List<Reminder> list) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        for (Reminder r : list) {
            if (r.at <= now) continue;
            Intent i = new Intent(ctx, ReminderReceiver.class);
            i.setAction(ACTION_REMIND);
            i.putExtra(EXTRA_ID, r.id);
            i.putExtra(EXTRA_TITLE, r.title);
            i.putExtra(EXTRA_TEXT, r.text);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r.id));
            } catch (Exception e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r.id));
            }
        }
        savePrefs(ctx, list);
    }

    static void cancelAllInternal(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            for (Reminder r : loadPrefs(ctx)) {
                try { am.cancel(pending(ctx, r.id)); } catch (Exception ignored) {}
            }
            clearPrefs(ctx);
        } catch (Exception ignored) {}
    }

    static void postNotification(Context ctx, int id, String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) return;
            }
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentPi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // 全屏闹钟式提醒：触发时直接弹全屏页（锁屏也可显示）
            Intent full = new Intent(ctx, ReminderActivity.class);
            full.putExtra(EXTRA_ID, id);
            full.putExtra(EXTRA_TITLE, title);
            full.putExtra(EXTRA_TEXT, text);
            full.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent fullPi = PendingIntent.getActivity(ctx, 1, full,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long[] vib = new long[]{0, 500, 250, 500, 250, 900};
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
             .setContentTitle(title)
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setCategory(Notification.CATEGORY_ALARM)
             .setAutoCancel(false)
             .setContentIntent(contentPi)
             .setFullScreenIntent(fullPi, true)
             .setVisibility(Notification.VISIBILITY_PUBLIC)
             .setPriority(Notification.PRIORITY_MAX)
             .setDefaults(Notification.DEFAULT_VIBRATE) // 只震动，不默认声音
             .setVibrate(vib)                            // 通知级震动：确保打扰
             .setSound(null);                            // 绝对无声
            Notification n = b.build();
            nm.notify(id & 0xFFFFFF, n);
        } catch (Exception ignored) {}
    }

    // ===== 持久化 =====
    static void savePrefs(Context ctx, List<Reminder> list) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>();
        for (Reminder r : list) {
            set.add(r.id + "|" + r.at + "|" + r.title + "|" + r.text);
        }
        sp.edit().putStringSet("list", set).apply();
    }

    static List<Reminder> loadPrefs(Context ctx) {
        List<Reminder> list = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = sp.getStringSet("list", new HashSet<String>());
        for (String line : set) {
            String[] p = line.split("\\|", 4);
            if (p.length < 4) continue;
            try {
                Reminder r = new Reminder();
                r.id = Integer.parseInt(p[0]);
                r.at = Long.parseLong(p[1]);
                r.title = p[2];
                r.text = p[3];
                list.add(r);
            } catch (Exception ignored) {}
        }
        return list;
    }

    static void clearPrefs(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ===== 广播接收（提醒触发 + 开机恢复） =====
    public static class ReminderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.BOOT_COMPLETED".equals(action)) {
                reRegister(ctx);
                return;
            }
            if (ACTION_REMIND.equals(action)) {
                int id = intent.getIntExtra(EXTRA_ID, 0);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (title == null) title = "上课提醒";
                if (text == null) text = "";
                postNotification(ctx, id, title, text);
            }
        }
    }

    static void reRegister(Context ctx) {
        try {
            List<Reminder> list = loadPrefs(ctx);
            scheduleList(ctx, list);
        } catch (Exception ignored) {}
    }
}