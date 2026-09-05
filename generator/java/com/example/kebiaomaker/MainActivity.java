package com.example.kebiaomaker;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_AVATAR = 2;
    private static final int REQ_PERM = 100;

    // ===== maker 字段 =====
    private EditText etAppName;
    private TextView tvMakerStatus;
    private ImageView ivAvatar;
    private byte[] avatarBytes;
    private MhtmlParser.ParseResult parseResult;
    private String generatedHtml;
    private File lastApk;
    private Button btnInstall;
    private Button btnOpenFolder;
    private File currentFileDir;
    private String loadStatus = "🔄 载入中…";
    private String parseStatus = "⏳ 解析待开始";
    private String infoLine = "";
    private StringBuilder makerLog = new StringBuilder();

    // ===== scraper 字段 =====
    private WebView webView;
    private EditText etUrl;
    private EditText etFileName;
    private TextView tvScraperStatus;
    private ScrollView scraperLogScroll;
    private StringBuilder scraperLog = new StringBuilder();

    // ===== 页面容器与 tab =====
    private View makerPage;
    private View scraperPage;
    private Button tabMaker;
    private Button tabScraper;

    private static final String C_MAIN = "#2563EB";
    private static final String C_ORANGE = "#F59E0B";
    private static final String C_BORDER = "#E5E7EB";
    private static final String C_BG = "#F5F7FA";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StrictMode.VmPolicy.Builder vb = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(vb.build());
        setContentView(buildUi());
        initWebView();
        requestPerm();
        switchTab(false); // 默认显示网页抓取器
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    // ============ 全局布局：固定标题 + tab + 内容容器 ============
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(C_BG));
        root.setPadding(dp(14), dp(4), dp(14), dp(10));

        // tab 切换栏（网页抓取器在左，课表输出工具在右）
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        tblp.bottomMargin = dp(10);
        tabBar.setLayoutParams(tblp);

        tabScraper = new Button(this);
        tabScraper.setText("网页抓取器");
        tabScraper.setTextSize(12);
        tabScraper.setAllCaps(false);
        tabScraper.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(false); }
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        tabScraper.setLayoutParams(sblp);
        tabBar.addView(tabScraper);

        tabMaker = new Button(this);
        tabMaker.setText("课表输出工具");
        tabMaker.setTextSize(13);
        tabMaker.setAllCaps(false);
        tabMaker.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(true); }
        });
        LinearLayout.LayoutParams mblp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        tabMaker.setLayoutParams(mblp);
        tabBar.addView(tabMaker);

        root.addView(tabBar);

        // 内容容器
        FrameLayout container = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        container.setLayoutParams(clp);

        scraperPage = buildScraperPage();
        makerPage = buildMakerPage();
        container.addView(scraperPage);
        container.addView(makerPage);

        root.addView(container);
        return root;
    }

    private void switchTab(boolean toMaker) {
        makerPage.setVisibility(toMaker ? View.VISIBLE : View.GONE);
        scraperPage.setVisibility(toMaker ? View.GONE : View.VISIBLE);

        String active = toMaker ? C_MAIN : C_ORANGE;
        String inactive = "#D1D5DB";
        tabMaker.setBackground(makeRoundDrawable(
                Color.parseColor(toMaker ? C_MAIN : inactive),
                toMaker ? C_MAIN : inactive, 20.0f, 0));
        tabMaker.setTextColor(toMaker ? Color.WHITE : Color.parseColor("#6B7280"));
        tabScraper.setBackground(makeRoundDrawable(
                Color.parseColor(toMaker ? inactive : C_ORANGE),
                toMaker ? inactive : C_ORANGE, 20.0f, 0));
        tabScraper.setTextColor(toMaker ? Color.parseColor("#6B7280") : Color.WHITE);
    }

    // ============ maker 页面 ============
    private View buildMakerPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(4), dp(4), dp(16));
        content.setBackgroundColor(Color.parseColor(C_BG));
        scroll.addView(content);

        // 课表文件
        LinearLayout cardFile = makeCard("📁 课表文件（HTML/MHT）");
        Button btnAuto = makeBtn("一键载入", C_MAIN, true);
        btnAuto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { autoLoad(); }
        });
        cardFile.addView(btnAuto);
        Button btnManual = makeBtn("手动载入", C_ORANGE, true);
        btnManual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        cardFile.addView(btnManual);
        content.addView(cardFile);

        // 应用信息
        LinearLayout cardInfo = makeCard("🎨 应用信息");
        TextView tvName = new TextView(this);
        tvName.setText("应用名称（可自定义，最长 12 字）");
        tvName.setTextSize(12);
        tvName.setTextColor(Color.parseColor("#333333"));
        cardInfo.addView(tvName);

        etAppName = new EditText(this);
        etAppName.setHint("例如：我的课表");
        etAppName.setTextSize(14);
        etAppName.setSingleLine(true);
        etAppName.setPadding(dp(14), dp(11), dp(14), dp(11));
        etAppName.setBackground(makeRoundDrawable(Color.WHITE, C_BORDER, 12.0f, 1));
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etlp.topMargin = dp(6);
        etAppName.setLayoutParams(etlp);
        cardInfo.addView(etAppName);

        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        avatarRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(10);
        avatarRow.setLayoutParams(arlp);

        ivAvatar = new ImageView(this);
        int a = dp(48);
        LinearLayout.LayoutParams ivlp = new LinearLayout.LayoutParams(a, a);
        ivlp.rightMargin = dp(12);
        ivAvatar.setLayoutParams(ivlp);
        ivAvatar.setBackground(makeRoundDrawable(Color.parseColor("#F3F4F6"), C_BORDER, 14.0f, 1));
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarRow.addView(ivAvatar);

        Button btnAvatar = makeBtn("选择头像照片", C_ORANGE, true);
        btnAvatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAvatar(); }
        });
        LinearLayout.LayoutParams bavlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bavlp.topMargin = 0;
        btnAvatar.setLayoutParams(bavlp);
        avatarRow.addView(btnAvatar);
        cardInfo.addView(avatarRow);
        content.addView(cardInfo);

        // 生成与安装
        LinearLayout cardGen = makeCard("🚀 生成与安装");
        Button btnGen = makeBtn("生成 APK", "#8B5CF6", true);
        btnGen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { generateApk(); }
        });
        cardGen.addView(btnGen);

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER);

        btnInstall = makeBtn("一键安装", "#22C55E", true);
        btnInstall.setEnabled(false);
        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { installApk(); }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ilp.rightMargin = dp(8);
        btnInstall.setLayoutParams(ilp);
        actRow.addView(btnInstall);

        btnOpenFolder = makeBtn("打开文件夹", "#06B6D4", true);
        btnOpenFolder.setEnabled(false);
        btnOpenFolder.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFolder(); }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        olp.leftMargin = dp(8);
        btnOpenFolder.setLayoutParams(olp);
        actRow.addView(btnOpenFolder);
        cardGen.addView(actRow);
        content.addView(cardGen);

        // 状态
        LinearLayout cardStatus = makeCard("📋 状态");
        tvMakerStatus = new TextView(this);
        tvMakerStatus.setTextSize(13);
        tvMakerStatus.setTextColor(Color.parseColor("#333333"));
        tvMakerStatus.setPadding(dp(6), dp(10), dp(6), dp(10));
        tvMakerStatus.setLineSpacing(2.0f, 1.1f);
        tvMakerStatus.setMinHeight(dp(200));
        tvMakerStatus.setTextIsSelectable(true);
        cardStatus.addView(tvMakerStatus);
        content.addView(cardStatus);

        renderMakerStatus();
        return scroll;
    }

    // ============ scraper 页面 ============
    private View buildScraperPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(4), dp(4), dp(4), dp(4));

        // 地址栏行
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        etUrl = new EditText(this);
        etUrl.setHint("粘贴课表页链接");
        etUrl.setTextSize(14);
        etUrl.setSingleLine(true);
        etUrl.setPadding(dp(10), 0, dp(10), 0);
        etUrl.setBackground(makeRoundDrawable(Color.WHITE, C_BORDER, 22.0f, 1));
        LinearLayout.LayoutParams etp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        etp.rightMargin = dp(8);
        urlRow.addView(etUrl, etp);
        Button btnGo = makeBtn("前往", C_MAIN, true);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(72), dp(42));
        urlRow.addView(btnGo, gp);
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoUrl(); }
        });
        page.addView(urlRow);

        // WebView 占主要空间
        webView = new WebView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        wp.topMargin = dp(10);
        wp.bottomMargin = dp(10);
        page.addView(webView, wp);

        // 自定义输出文件名
        etFileName = new EditText(this);
        etFileName.setHint("自定义文件名（可选，不填默认课表_时间戳）");
        etFileName.setTextSize(13);
        etFileName.setSingleLine(true);
        etFileName.setPadding(dp(10), 0, dp(10), 0);
        etFileName.setBackground(makeRoundDrawable(Color.WHITE, C_BORDER, 22.0f, 1));
        LinearLayout.LayoutParams fnp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        page.addView(etFileName, fnp);

        // 抓取按钮
        Button btnGrab = makeBtn("抓取并保存到下载目录", C_ORANGE, true);
        LinearLayout.LayoutParams grp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        page.addView(btnGrab, grp);
        btnGrab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { grab(); }
        });

        // 状态日志（固定高度滚动窗口，带滚动条，不挤压上方）
        scraperLogScroll = new ScrollView(this);
        scraperLogScroll.setBackgroundColor(Color.parseColor("#FFFFFF"));
        scraperLogScroll.setVerticalScrollBarEnabled(true);
        scraperLogScroll.setScrollbarFadingEnabled(false);
        tvScraperStatus = new TextView(this);
        tvScraperStatus.setTextSize(12);
        tvScraperStatus.setTextColor(Color.parseColor("#374151"));
        tvScraperStatus.setPadding(dp(8), dp(8), dp(8), dp(8));
        scraperLogScroll.addView(tvScraperStatus);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(100));
        page.addView(scraperLogScroll, sp);

        return page;
    }

    // ============ 通用 UI 工具 ============
    private LinearLayout makeCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        card.setBackground(makeRoundDrawable(Color.WHITE, C_BORDER, 16.0f, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Color.parseColor(C_MAIN));
        t.setGravity(Gravity.LEFT);
        t.setPadding(0, 0, 0, dp(12));
        card.addView(t);
        return card;
    }

    private Button makeBtn(String text, String color, boolean whiteText) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setBackground(makeRoundDrawable(Color.parseColor(color), color, 22.0f, 0));
        if (whiteText) {
            b.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable makeRoundDrawable(int color, String stroke, float radius, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, Color.parseColor(stroke));
        }
        return d;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ============ maker 状态与日志 ============
    private void renderMakerStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(loadStatus).append("\n");
        sb.append(parseStatus);
        if (infoLine != null && infoLine.length() > 0) {
            sb.append("\n").append(infoLine);
        }
        if (makerLog != null && makerLog.length() > 0) {
            sb.append("\n——操作日志——\n").append(makerLog.toString().trim());
        }
        tvMakerStatus.setText(sb.toString());
    }

    private void addMakerLog(String msg) {
        makerLog.append("·").append(msg).append("\n");
        renderMakerStatus();
    }

    private void addScraperLog(String msg) {
        scraperLog.append(msg).append("\n");
        tvScraperStatus.setText("—— 操作日志 ——\n" + scraperLog.toString());
        scraperLogScroll.post(new Runnable() {
            @Override public void run() {
                scraperLogScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ============ maker 文件扫描与加载 ============
    private String[] getCandidateDirs() {
        String manu = Build.MANUFACTURER.toLowerCase();
        ArrayList<String> dirs = new ArrayList<>();
        if (manu.contains("honor")) {
            dirs.add("/storage/emulated/0/Download/HonorBrowser");
        } else if (manu.contains("huawei")) {
            dirs.add("/storage/emulated/0/Download/HuaweiBrowser");
            dirs.add("/storage/emulated/0/Download/Browser");
        }
        dirs.add("/storage/emulated/0/Download");
        dirs.add("/storage/emulated/0/Download/Quark");
        dirs.add("/storage/emulated/0/Download/Baidu");
        dirs.add("/storage/emulated/0/Download/QQBrowser");
        dirs.add("/storage/emulated/0/UCDownloads");
        dirs.add("/storage/emulated/0/QQBrowser");
        dirs.add("/storage/emulated/0/Download/Browser");
        return dirs.toArray(new String[0]);
    }

    private File getDefaultDownloadDir() {
        String manu = Build.MANUFACTURER.toLowerCase();
        File honor = new File("/storage/emulated/0/Download/HonorBrowser");
        File huawei = new File("/storage/emulated/0/Download/HuaweiBrowser");
        if (manu.contains("honor") && honor.exists()) return honor;
        if (manu.contains("huawei") && huawei.exists()) return huawei;
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private void autoScan() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String[] dirs = getCandidateDirs();
                final String[] keywords = new String[]{"课程表", "课表", "学生"};
                if (scanOnce(dirs, keywords, new String[]{".html", ".htm"})) return;
                scanOnce(dirs, keywords, new String[]{".mht"});
            }
        }).start();
    }

    private boolean scanOnce(String[] dirs, String[] keywords, String[] exts) {
        for (String d : dirs) {
            File dir = new File(d);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String lower = name.toLowerCase();
                boolean hitKw = false;
                for (String k : keywords) {
                    if (name.contains(k)) { hitKw = true; break; }
                }
                if (!hitKw) continue;
                boolean hitExt = false;
                for (String e : exts) {
                    if (lower.endsWith(e)) { hitExt = true; break; }
                }
                if (!hitExt) continue;
                final File found = f;
                runOnUiThread(new Runnable() {
                    @Override public void run() { loadFile(found, true); }
                });
                return true;
            }
        }
        return false;
    }

    private void autoLoad() {
        addMakerLog("🔍 正在扫描浏览器下载目录…");
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<File> found = new java.util.ArrayList<>();
                String[] dirs = getCandidateDirs();
                String[] keywords = new String[]{"课程表", "课表", "学生"};
                String[] exts = new String[]{".html", ".htm", ".mht"};
                for (String d : dirs) {
                    File dir = new File(d);
                    if (!dir.exists() || !dir.isDirectory()) continue;
                    File[] files = dir.listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        if (!f.isFile()) continue;
                        String name = f.getName();
                        String lower = name.toLowerCase();
                        boolean kw = false;
                        for (String k : keywords) {
                            if (name.contains(k)) { kw = true; break; }
                        }
                        if (!kw) continue;
                        boolean ext = false;
                        for (String e : exts) {
                            if (lower.endsWith(e)) { ext = true; break; }
                        }
                        if (!ext) continue;
                        found.add(f);
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (found.isEmpty()) {
                            addMakerLog("❌ 未找到课表文件，请用「手动载入」");
                            Toast.makeText(MainActivity.this, "未找到课表文件，请手动选择", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (found.size() == 1) {
                            addMakerLog("✅ 找到 1 个课表文件，正在载入");
                            loadFile(found.get(0), false);
                        } else {
                            addMakerLog("✅ 找到 " + found.size() + " 个课表文件，请选择");
                            showFileList(found);
                        }
                    }
                });
            }
        }).start();
    }

    private void showFileList(final java.util.List<File> files) {
        String[] items = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            items[i] = files.get(i).getAbsolutePath();
        }
        new AlertDialog.Builder(this)
                .setTitle("找到 " + files.size() + " 个课表文件，点击载入")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        loadFile(files.get(which), false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                File dir = getDefaultDownloadDir();
                String rel = dir.getAbsolutePath()
                        .replace("/storage/emulated/0/", "")
                        .replace("/storage/emulated/0", "");
                Uri initial = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"
                        + rel.replace("/", "%2F"));
                intent.putExtra("android.provider.extra.INITIAL_URI", initial);
            } catch (Exception e) {
            }
        }
        startActivityForResult(Intent.createChooser(intent, "选择课表文件"), REQ_FILE);
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择头像图片"), REQ_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        try {
            if (requestCode == REQ_FILE) {
                String name = queryName(uri);
                String path = resolvePath(uri);
                loadStatus = "🔄 载入中…";
                renderMakerStatus();
                byte[] bytes = readUri(uri);
                loadStatus = "✅ 载入成功";
                addMakerLog("载入文件：" + path);
                processBytes(bytes, name);
            } else if (requestCode == REQ_AVATAR) {
                avatarBytes = readUri(uri);
                ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length));
                addMakerLog("头像已选择");
                Toast.makeText(this, "头像已选择", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            if (requestCode == REQ_FILE) {
                loadStatus = "❌ 载入失败";
                addMakerLog("载入失败：" + e.getMessage());
            }
            Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadFile(final File f, boolean auto) {
        loadStatus = "🔄 载入中…";
        renderMakerStatus();
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] bytes = readAll(fis);
            fis.close();
            currentFileDir = f.getParentFile();
            loadStatus = "✅ 载入成功";
            addMakerLog("载入文件：" + f.getAbsolutePath());
            processBytes(bytes, f.getName());
            if (auto) {
                Toast.makeText(this, "已自动载入：" + f.getName(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            loadStatus = "❌ 载入失败";
            addMakerLog("载入失败：" + e.getMessage());
        }
    }

    private void processBytes(final byte[] bytes, final String fileName) {
        parseStatus = "🔄 解析中…";
        renderMakerStatus();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String lower = (fileName != null ? fileName : "").toLowerCase();
                    final MhtmlParser.ParseResult r;
                    if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                        r = MhtmlParser.parseHtml(bytes);
                    } else {
                        r = MhtmlParser.parse(bytes);
                    }
                    final String html = HtmlGenerator.generate(r, readAsset("index_template.html"));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            parseResult = r;
                            generatedHtml = html;
                            if (etAppName.getText().toString().trim().length() == 0) {
                                String auto = (r.className != null && r.className.length() > 0)
                                        ? (r.className + "课表") : "学生课表";
                                etAppName.setText(auto);
                            }
                            parseStatus = "✅ 解析成功";
                            infoLine = "班级：" + r.className + "\n学号：" + r.stuId + "\n学期：" + r.term;
                            addMakerLog("HTML 已生成，可点「生成 APK」");
                            Toast.makeText(MainActivity.this, "载入成功，解析成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            parseStatus = "❌ 解析失败";
                            addMakerLog("解析失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void generateApk() {
        if (generatedHtml == null) {
            Toast.makeText(this, "请先载入并解析课表文件", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            Toast.makeText(this, "请先授权存储权限", Toast.LENGTH_LONG).show();
            return;
        }
        String tmp = etAppName.getText().toString().trim();
        final String name;
        if (tmp.length() == 0) name = "学生课表";
        else if (tmp.length() > 12) name = tmp.substring(0, 12);
        else name = tmp;
        final byte[] avatar = avatarBytes;
        addMakerLog("开始生成 APK…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    final File apk = ApkBuilder.build(MainActivity.this, generatedHtml, name, avatar, dir);
                    lastApk = apk;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            btnInstall.setEnabled(true);
                            btnOpenFolder.setEnabled(true);
                            addMakerLog("APK 生成成功：" + name + "（头像：" + (avatar != null ? "自定义" : "默认") + "）");
                            addMakerLog("已保存到 Download：" + apk.getName());
                            addMakerLog("可点下方「一键安装」或「打开文件夹」");
                            Toast.makeText(MainActivity.this, "APK 生成成功", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            addMakerLog("生成失败：" + e.getMessage());
                            Toast.makeText(MainActivity.this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void installApk() {
        if (lastApk == null || !lastApk.exists()) {
            Toast.makeText(this, "APK 文件不存在", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    Toast.makeText(this, "请允许「安装未知应用」后再点一键安装", Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception e) {
                Toast.makeText(this, "请在系统设置里允许安装未知应用", Toast.LENGTH_LONG).show();
                return;
            }
        }
        try {
            Uri uri = Uri.parse("content://com.example.kebiaomaker.fileprovider/apk/"
                    + Uri.encode(lastApk.getName()));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动安装器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openFolder() {
        File dir = (currentFileDir != null && currentFileDir.exists())
                ? currentFileDir : getDefaultDownloadDir();
        try {
            String abs = dir.getAbsolutePath();
            String rel = abs.replace("/storage/emulated/0/", "").replace("/storage/emulated/0", "");
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/root/primary/"
                    + rel.replace("/", "%2F"));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "请用文件管理器打开：\n" + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private String resolvePath(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, new String[]{"_data"}, null, null, null);
            if (c != null) {
                String path = null;
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex("_data");
                    if (idx >= 0) path = c.getString(idx);
                }
                c.close();
                if (path != null && path.length() > 0) return path;
            }
        } catch (Exception e) {
        }
        return uri.toString();
    }

    private String queryName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri,
                    new String[]{"_display_name"}, null, null, null);
            if (c != null) {
                String name = null;
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex("_display_name");
                    if (idx >= 0) name = c.getString(idx);
                }
                c.close();
                return name;
            }
        } catch (Exception e) {
        }
        return null;
    }

    private byte[] readUri(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        byte[] b = readAll(is);
        is.close();
        return b;
    }

    private byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private String readAsset(String name) throws Exception {
        InputStream is = getAssets().open(name);
        byte[] b = readAll(is);
        is.close();
        return new String(b, "UTF-8");
    }

    // ============ 权限 ============
    private void requestPerm() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                }, REQ_PERM);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                addScraperLog("✅ 存储权限已授予，可保存文件");
            } else {
                addScraperLog("❌ 未授予存储权限，保存会失败");
            }
        }
    }

    // ============ scraper 功能 ============
    private void initWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return false;
                }
                addScraperLog("⏭ 已拦截非网页跳转：" + url);
                return true;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return false;
                }
                addScraperLog("⏭ 已拦截非网页跳转：" + url);
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                addScraperLog("🔄 页面已加载：" + url);
                if (url != null && !url.isEmpty()) {
                    etUrl.setText(url);
                }
            }
            @Override
            public void onReceivedSslError(WebView view,
                                           android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                addScraperLog("⚠️ SSL 证书不被信任，已放行继续加载");
                handler.proceed();
            }
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                addScraperLog("❌ 网页打不开：" + description + "（错误码 " + errorCode + "）");
                addScraperLog("   地址：" + failingUrl);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                WebView newWebView = new WebView(MainActivity.this);
                WebSettings ns = newWebView.getSettings();
                ns.setJavaScriptEnabled(true);
                ns.setDomStorageEnabled(true);
                ns.setSupportMultipleWindows(true);
                newWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        addScraperLog("🔄 拦截弹窗，跳转到：" + url);
                        webView.loadUrl(url);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private void gotoUrl() {
        String u = etUrl.getText().toString().trim();
        if (u.isEmpty()) {
            Toast.makeText(this, "请先粘贴课表页链接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        addScraperLog("🔄 正在打开：" + u);
        webView.loadUrl(u);
    }

    private void grab() {
        addScraperLog("🔄 正在抓取当前页面…");
        webView.evaluateJavascript("JSON.stringify(document.documentElement.outerHTML)",
                new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                try {
                    Object o = new JSONTokener(value).nextValue();
                    String html = (o != null) ? o.toString() : "";
                    if (html == null || html.length() == 0) {
                        addScraperLog("❌ 抓取失败：页面为空，请确认课表已加载出来");
                        return;
                    }
                    saveHtml(html);
                } catch (Exception e) {
                    addScraperLog("❌ 抓取结果解析失败：" + e.getMessage());
                }
            }
        });
    }

    private void saveHtml(final String html) {
        final String custom = etFileName.getText().toString().trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String fileName;
                    if (custom.length() > 0) {
                        fileName = custom.toLowerCase().endsWith(".html") ? custom : custom + ".html";
                    } else {
                        fileName = "课表_" + ts + ".html";
                    }
                    File f = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(html.getBytes("UTF-8"));
                    fos.close();
                    final String path = f.getAbsolutePath();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            addScraperLog("✅ 已保存：" + path);
                            addScraperLog("文件大小：" + new File(path).length() + " 字节");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            addScraperLog("❌ 保存失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }
}
