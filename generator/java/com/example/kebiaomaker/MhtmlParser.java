package com.example.kebiaomaker;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
public class MhtmlParser {
    public static class ParseResult {
        public String className = "";
        public String stuId = "";
        public String term = "";
        public List<Course>[][] table = new List[7][5];
        public String extraText = "";
    }
    // 解码 quoted-printable 字节流
    public static byte[] decodeQuotedPrintable(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int i = 0;
        while (i < data.length) {
            byte b = data[i];
            if (b == '=') {
                // 软换行：= 后跟换行
                if (i + 1 < data.length && (data[i + 1] == '\r' || data[i + 1] == '\n')) {
                    if (data[i + 1] == '\r' && i + 2 < data.length && data[i + 2] == '\n') {
                        i += 3;
                    } else {
                        i += 2;
                    }
                    continue;
                }
                // =XX 十六进制
                if (i + 2 < data.length) {
                    int hi = hexVal(data[i + 1]);
                    int lo = hexVal(data[i + 2]);
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi << 4) | lo);
                        i += 3;
                        continue;
                    }
                }
            }
            out.write(b & 0xFF);
            i++;
        }
        return out.toByteArray();
    }

    private static int hexVal(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        return -1;
    }
    // HTML 实体解码
    private static String decodeEntities(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("<wbr>", "").replace("<WBR>", "");
    }
    // 去掉所有标签（<br> 转为换行；只识别真正的 HTML 标签，避免误删 <<课程>>）
    private static String stripTags(String s) {
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        StringBuilder sb = new StringBuilder();
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                char n = (i + 1 < s.length()) ? s.charAt(i + 1) : ' ';
                if (n == '<') {
                    sb.append("<<");
                    i++;
                    continue;
                }
                if (n == '/' || n == '!' || n == '?' || (n >= 'a' && n <= 'z') || (n >= 'A' && n <= 'Z')) {
                    inTag = true;
                    continue;
                } else {
                    sb.append(c);
                    continue;
                }
            }
            if (c == '>') {
                if (inTag) { inTag = false; continue; }
                else { sb.append(c); continue; }
            }
            if (!inTag) sb.append(c);
        }
        return sb.toString();
    }

    // 主入口：MHT 文件（GBK + quoted-printable）
    public static ParseResult parse(byte[] raw) throws Exception {
        int htmlStart = indexOfAscii(raw, "<html");
        if (htmlStart < 0) htmlStart = indexOfAscii(raw, "<HTML");
        if (htmlStart < 0) htmlStart = 0;
        byte[] qp = new byte[raw.length - htmlStart];
        System.arraycopy(raw, htmlStart, qp, 0, qp.length);
        byte[] decoded = decodeQuotedPrintable(qp);
        String html = new String(decoded, "GBK");
        return parseFromHtml(html);
    }

    // 新增：HTML 文件（UTF-8，兼容抓取器保存的 JSON 转义格式）
    public static ParseResult parseHtml(byte[] data) throws Exception {
        String s = new String(data, "UTF-8");
        String t = s.trim();
        // 抓取器保存的可能是 JSON 字符串字面量（外层引号 + 转义），先还原
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            try {
                Object o = new org.json.JSONTokener(t).nextValue();
                if (o != null) s = o.toString();
            } catch (Exception e) {
                s = t;
            }
        }
        return parseFromHtml(s);
    }

    // 公共：从已解码的 HTML 文本解析（MHT 与 HTML 两种文件共用，保证效果一致）
    private static ParseResult parseFromHtml(String html) throws Exception {
        ParseResult r = new ParseResult();
        html = decodeEntities(html);
        // 提取学号 / 班级 / 学期
        r.stuId = find(html, "学生课表[：:]?\\s*([0-9]+)");
        r.className = find(html, "班级[：:]\\s*([^\\s<&]+)");
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("([0-9]{4})\\s*(春|秋)").matcher(html);
        if (tm.find()) r.term = tm.group(1) + tm.group(2) + "季";
        if (r.term.length() == 0) r.term = "2026秋季";
        // 提取主课表
        String table = between(html, "<table id=\"timetable\"", "</table>");
        if (table.length() > 0) {
            parseTimetable(table, r.table);
        }
        // 提取未安排课程
        String noArr = between(html, "id=\"noArrangement\"", "</table>");
        if (noArr.length() > 0) {
            r.extraText = parseExtra(noArr);
        }
        return r;
    }
    private static void parseTimetable(String table, List<Course>[][] out) {
        // 切分行
        List<String> rows = splitByTag(table, "<tr");
        int period = -1;
        for (String row : rows) {
            if (row.indexOf("<th") >= 0 && row.indexOf("<td") < 0) {
                continue; // 表头行
            }
            if (row.indexOf("<td") < 0) continue;
            // 该行的 th 是节次，td 是7天
            String thText = stripTags(between(row, "<th", "</th>"));
            if (thText.indexOf("大节") < 0 && thText.indexOf("小节") < 0) {
                // 不是节次行
                continue;
            }
            period++;
            if (period >= 5) break;
            List<String> tds = splitByTag(row, "<td");
            int day = 0;
            for (int t = 0; t < tds.size() && day < 7; t++) {
                String td = tds.get(t);
                if (td.indexOf("id=") < 0) continue;
                String text = stripTags(td);
                List<Course> courses = parseCell(text);
                out[day][period] = courses;
                day++;
            }
        }
    }
    // 解析单个 td 单元格文本
    private static List<Course> parseCell(String text) {
        List<Course> list = new ArrayList<>();
        String[] lines = text.split("\\s*\\n\\s*");
        // 过滤空行
        List<String> segs = new ArrayList<>();
        for (String ln : lines) {
            String t = ln.trim();
            if (t.length() > 0 && !t.equals(" ")) segs.add(t);
        }
        if (segs.isEmpty()) return list;
        String name = segs.get(0);
        if (name.startsWith("<<")) {
            name = name.substring(2);
        }
        int close = name.indexOf(">>");
        if (close >= 0) name = name.substring(0, close);
        name = name.replace("<<", "").replace(">>", "").trim();
        // 去掉 ;数字 课序号
        int semi = name.indexOf(';');
        if (semi >= 0) name = name.substring(0, semi).trim();

        if (name.length() == 0) return list;
        Course c = new Course();
        c.name = name;
        if (segs.size() >= 2) c.place = segs.get(1).trim();
        if (segs.size() >= 3) {
            c.teacher = segs.get(2).trim();
            if (c.teacher.indexOf('，') >= 0 || c.teacher.indexOf(',') >= 0) {
                c.multiTeacher = true;
            }
        }
        if (segs.size() >= 4) parseWeek(segs.get(3), c);
        if (segs.size() >= 5) {
            String t = segs.get(4);
            if (t.indexOf("实验") >= 0) c.isLab = true;
        }
        list.add(c);
        return list;
    }
    private static void parseWeek(String w, Course c) {
        String s = w.trim();
        c.parity = 0;
        if (s.indexOf("双") >= 0) c.parity = 2;
        else if (s.indexOf("单") >= 0) c.parity = 1;
        // 提取数字范围
        int dash = s.indexOf('-');
        if (dash < 0) {
            String num = find(s, "([0-9]+)");
            if (num.length() > 0) {
                c.start = c.end = Integer.parseInt(num);
            }
            return;
        }
        String a = s.substring(0, dash).replaceAll("[^0-9]", "");
        String b = s.substring(dash + 1).replaceAll("[^0-9]", "");
        if (a.length() > 0) c.start = Integer.parseInt(a);
        if (b.length() > 0) c.end = Integer.parseInt(b);
    }
    private static String parseExtra(String noArr) {
        StringBuilder sb = new StringBuilder();
        List<String> rows = splitByTag(noArr, "<tr");
        boolean first = true;
        for (String row : rows) {
            if (first) { first = false; continue; } // 表头
            String text = stripTags(row);
            text = text.trim();
            if (text.length() > 0) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(text.replaceAll("[ \\t]+", " "));
            }
        }
        return sb.toString();
    }
    // ===== 工具方法 =====
    private static int indexOfAscii(byte[] data, String ascii) {
        byte[] pat = ascii.getBytes();
        outer:
        for (int i = 0; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        if (i < 0) return "";
        i += start.length();
        int j = s.indexOf(end, i);
        if (j < 0) return "";
        return s.substring(i, j);
    }
    private static String find(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        if (m.find()) return m.group(1) == null ? "" : m.group(1);
        return "";
    }
    private static String extract(String s, String prefix, String numRegex) {
        int i = s.indexOf("学生课表");
        if (i < 0) return "";
        String sub = s.substring(i, Math.min(s.length(), i + 80));
        return find(sub, "([0-9]+)");
    }
    private static List<String> splitByTag(String s, String tag) {
        List<String> list = new ArrayList<>();
        int idx = s.indexOf(tag);
        while (idx >= 0) {
            int next = s.indexOf(tag, idx + 1);
            if (next < 0) next = s.length();
            list.add(s.substring(idx, next));
            idx = s.indexOf(tag, next);
        }
        return list;
    }
}