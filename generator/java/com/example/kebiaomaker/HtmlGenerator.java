package com.example.kebiaomaker;

import java.util.List;

public class HtmlGenerator {

    private static final String[] DAY_KEYS = {"mon","tue","wed","thu","fri","sat","sun"};
    private static final String[] COLORS = {
        "c-wuli","c-gailv","c-python","c-tiyu","c-yingyu","c-shigang",
        "c-wulishy","c-yingyong","c-dili","c-lilun","c-xinxi","c-gongneng"
    };

    public static String generate(MhtmlParser.ParseResult r, String template) {
        String dataJson = buildDataJson(r);
        String extraJson = buildExtraJson(r);
        String html = template;
        html = html.replace("__DATA_JSON__", dataJson);
        html = html.replace("__EXTRA_JSON__", extraJson);
        html = html.replace("__STU_ID__", esc(r.stuId));
        html = html.replace("__CLASS__", esc(r.className));
        html = html.replace("__TERM__", esc(r.term));
        return html;
    }

    private static String buildDataJson(MhtmlParser.ParseResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (int d = 0; d < 7; d++) {
            if (d > 0) sb.append(",");
            sb.append(DAY_KEYS[d]).append(":{");
            for (int p = 0; p < 5; p++) {
                if (p > 0) sb.append(",");
                sb.append(p).append(":[");
                List<Course> list = r.table[d][p];
                if (list != null) {
                    for (int k = 0; k < list.size(); k++) {
                        if (k > 0) sb.append(",");
                        sb.append(courseJson(list.get(k)));
                    }
                }
                sb.append("]");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String courseJson(Course c) {
        StringBuilder sb = new StringBuilder();
        sb.append("{name:").append(q(c.name));
        sb.append(",color:").append(q(colorFor(c.name)));
        sb.append(",place:").append(q(c.place));
        sb.append(",teacher:").append(q(c.teacher));
        sb.append(",start:").append(c.start);
        sb.append(",end:").append(c.end);
        sb.append(",parity:").append(c.parity);
        sb.append(",tags:[");
        if (c.isLab) sb.append("{t:").append(q("实验")).append(",c:").append(q("tag-lab")).append("}");
        else sb.append("{t:").append(q("讲课")).append(",c:").append(q("tag-lecture")).append("}");
        if (c.parity == 2) sb.append(",{t:").append(q("双周")).append(",c:").append(q("tag-even")).append("}");
        else if (c.parity == 1) sb.append(",{t:").append(q("单周")).append(",c:").append(q("tag-even")).append("}");
        if (c.multiTeacher) sb.append(",{t:").append(q("多位老师")).append(",c:").append(q("tag-multi")).append("}");
        sb.append("]}");
        return sb.toString();
    }

    private static String buildExtraJson(MhtmlParser.ParseResult r) {
        String text = r.extraText;
        if (text == null || text.trim().length() == 0) {
            return "{name:'',start:99,end:0,text:''}";
        }
        // 简单周次检测
        int start = 1, end = 4;
        String num = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+)-([0-9]+)").matcher(text);
        if (m.find()) {
            start = Integer.parseInt(m.group(1));
            end = Integer.parseInt(m.group(2));
        }
        return "{name:" + q("未安排课程") + ",start:" + start + ",end:" + end + ",text:" + q(text) + "}";
    }

    private static String colorFor(String name) {
        if (name.indexOf("物理") >= 0 && name.indexOf("实验") >= 0) return "c-wulishy";
        if (name.indexOf("物理") >= 0) return "c-wuli";
        if (name.indexOf("概率") >= 0 || name.indexOf("数理统计") >= 0) return "c-gailv";
        if (name.indexOf("Python") >= 0 || name.indexOf("python") >= 0) return "c-python";
        if (name.indexOf("体育") >= 0) return "c-tiyu";
        if (name.indexOf("英语") >= 0) return "c-yingyu";
        if (name.indexOf("史纲要") >= 0 || name.indexOf("近现代") >= 0) return "c-shigang";
        if (name.indexOf("写作") >= 0) return "c-yingyong";
        if (name.indexOf("地理") >= 0) return "c-dili";
        if (name.indexOf("力学") >= 0) return "c-lilun";
        if (name.indexOf("检索") >= 0) return "c-xinxi";
        if (name.indexOf("功能材料") >= 0 || name.indexOf("材料") >= 0) return "c-gongneng";
        // 兜底：按名称哈希分配颜色
        int h = 0;
        for (int i = 0; i < name.length(); i++) h = (h * 31 + name.charAt(i)) & 0x7fffffff;
        return COLORS[h % COLORS.length];
    }

    private static String q(String s) {
        if (s == null) s = "";
        return "\"" + esc(s) + "\"";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}