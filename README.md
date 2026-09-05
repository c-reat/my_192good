# 内农大课程表（25农水2）

内蒙古农业大学 25农水2 班级课表工具套件：**网页抓取器 + 课表生成器 + 展示壳**。

## 目录结构

```
├── generator/       # kebiaomaker：网页抓取器 + 课表生成器（Android 原生）
├── shell/           # kebiao：课表展示壳（WebView 程序，含提醒/天气桥）
├── assets/          # 核心资产：课表模板 index_template.html + 新版壳 template.apk
└── docs/            # 综合功能报告
```

## 功能亮点

- 从教务系统 MHT/HTML 网页一键解析课表，生成 APK 安装包
- 开屏小黑板：12 门课程结课进度徽章墙 + 剩余节数统计
- SVG 天气场景引擎（呼和浩特实时天气，wttr.in 数据源）
- 自定义工具沙盒（可加自定义功能按钮）
- 上课/下课提醒（AlarmManager + 通知）

## 构建

详见 docs/ 报告。签名使用 apksigner（keystore: 123456 / alias: kebiao）。
