package com.example.kebiao;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全屏闹钟式提醒页：锁屏/熄屏时直接弹出到屏幕中央，静音+震动，
 * 用户点「知道了」或直接把它关掉才消失（对应通知保留在通知栏可上滑划掉）。
 */
public class ReminderActivity extends Activity {
    private Vibrator vib;
    private long[] PAT = new long[]{0, 500, 300, 500, 300, 1000};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 锁屏上也显示、点亮屏幕、防止误触锁屏键盘
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        final int notifId = getIntent().getIntExtra(MainActivity.EXTRA_ID, 0);
        String title = getIntent().getStringExtra(MainActivity.EXTRA_TITLE);
        String text = getIntent().getStringExtra(MainActivity.EXTRA_TEXT);
        if (title == null) title = "上课提醒";
        if (text == null) text = "";

        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try { if (vib != null && vib.hasVibrator()) vib.vibrate(PAT, 1); } catch (Exception ignored) {}

        // —— 构建全屏 UI（黑底橙卡，居中大号字）——
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(36, 48, 36, 48);
        root.setBackgroundColor(Color.parseColor("#CC1a0e00"));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(48, 44, 48, 44);
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        TextView ico = new TextView(this);
        ico.setText("⏰");
        ico.setTextSize(72);
        ico.setGravity(Gravity.CENTER);
        card.addView(ico);

        TextView head = new TextView(this);
        head.setText("上课提醒！");
        head.setTextSize(34);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(Color.parseColor("#C0392B"));
        head.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = 18;
        card.addView(head, headLp);

        TextView course = new TextView(this);
        course.setText(title);
        course.setTextSize(26);
        course.setTypeface(null, Typeface.BOLD);
        course.setTextColor(Color.parseColor("#8A4B1D"));
        course.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams courseLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        courseLp.topMargin = 22;
        card.addView(course, courseLp);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(18);
        body.setTextColor(Color.parseColor("#444444"));
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(6, 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = 18;
        card.addView(body, bodyLp);

        Button ok = new Button(this);
        ok.setText("知 道 了");
        ok.setTextSize(18);
        ok.setAllCaps(false);
        ok.setTextColor(Color.WHITE);
        ok.setBackgroundColor(Color.parseColor("#E67E22"));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                dp(240), dp(54));
        okLp.topMargin = 30;
        okLp.gravity = Gravity.CENTER_HORIZONTAL;
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss(notifId);
            }
        });
        card.addView(ok, okLp);

        TextView tip = new TextView(this);
        tip.setText("静音 · 震动提醒 · 通知栏保留，可上滑/左右滑划掉");
        tip.setTextSize(12);
        tip.setTextColor(Color.parseColor("#999999"));
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = 14;
        card.addView(tip, tipLp);

        root.addView(card);
        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void dismiss(int notifId) {
        stopVibrate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(notifId & 0xFFFFFF);
        } catch (Exception ignored) {}
        finish();
    }

    private void stopVibrate() {
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVibrate();
    }
}