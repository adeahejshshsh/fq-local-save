package com.fq.localsave;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 整本导出。
 *
 * 输入：抓取层缓存的「已解密明文 XHTML」。
 * 输出：Download/番茄本地/<书名>/
 *          <书名>.txt        纯文本（插图处留 ［插图］ 占位 + 文件名指引）
 *          <书名>.html       图文混排（插图内嵌相对路径，手机浏览器可直接看）
 *          插图/i0001.jpg    插图本体
 *          _导出报告.txt     逐章统计与校对结论
 *
 * 不实现任何解密：明文完全来自宿主自己的解密例程。
 */
public final class Exporter {

    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /** 进度回调；可为 null */
    public interface Prog {
        void set(int percent, String text);
    }

    private Exporter() {
    }

    /** 单章解析结果 */
    static final class ChapDoc {
        String title;
        List<Xhtml.Blk> blks;
        int srcChars;
        int outChars;
        int outImgs;
        /** 是否把「首块=章标题」合并掉了 */
        boolean dupRemoved;
    }

    public static String export(String bookId, String bookName, Store.Order order, Prog prog) {
        if (order == null || order.list.isEmpty()) return "没有可导出的章节";
        List<Store.ChapterBuf> chapters = order.list;
        String book = Files.notEmpty(bookName) ? bookName : "_未命名";
        String rel = Files.ROOT + "/" + Files.safe(book, 60);
        String base = Files.safe(book, 80);
        String stamp = TS.format(new Date());
        int total = chapters.size();

        // ── 1) 逐章解析 ──────────────────────────────────────
        if (prog != null) prog.set(0, "解析章节 0/" + total);
        List<ChapDoc> docs = new ArrayList<ChapDoc>(total);
        int dupRemoved = 0;
        for (int i = 0; i < total; i++) {
            ChapDoc d = prepareChapter(chapters.get(i), bookId, i);
            if (d.dupRemoved) dupRemoved++;
            docs.add(d);
            if (prog != null) prog.set(50 * (i + 1) / Math.max(1, total),
                    "解析章节 " + (i + 1) + "/" + total);
        }

        // ── 2) 插图去重收集：key → i0001.jpg ─────────────────
        boolean wantImg = Store.cfgImages();
        LinkedHashMap<String, String> keyToFile = new LinkedHashMap<String, String>();
        LinkedHashMap<String, byte[]> imgData = new LinkedHashMap<String, byte[]>();
        LinkedHashMap<String, String> imgMime = new LinkedHashMap<String, String>();
        if (wantImg) {
            int n = 0;
            for (ChapDoc d : docs) {
                for (Xhtml.Blk b : d.blks) {
                    if (b.kind != Xhtml.Blk.IMG || b.img == null || b.imgKey == null) continue;
                    if (keyToFile.containsKey(b.imgKey)) continue;
                    boolean png = Files.isPng(b.img);
                    String fn = String.format(Locale.US, "i%04d", ++n) + (png ? ".png" : ".jpg");
                    keyToFile.put(b.imgKey, fn);
                    imgData.put(fn, b.img);
                    imgMime.put(fn, png ? "image/png" : "image/jpeg");
                }
            }
            if (prog != null) prog.set(55, "收集插图 " + imgData.size() + " 张");
        }

        // ── 3) 正文 + 图文 ───────────────────────────────────
        StringBuilder txt = new StringBuilder(1 << 16);
        txt.append("《").append(book).append("》\n")
                .append("导出时间 ").append(stamp)
                .append("　共 ").append(total).append(" 章\n")
                .append("──────────────────────────────\n\n");

        StringBuilder html = new StringBuilder(1 << 16);
        html.append("<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
                .append("<title>").append(Xhtml.esc(book)).append("</title>\n<style>\n")
                .append("body{max-width:42em;margin:2em auto;padding:0 1.2em;line-height:1.9;")
                .append("font-family:-apple-system,BlinkMacSystemFont,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;color:#222;}\n")
                .append("h1{font-size:1.5em;}h2{font-size:1.2em;margin:2.2em 0 .8em;padding-bottom:.3em;border-bottom:1px solid #e5e5e5;}\n")
                .append("p{margin:.9em 0;text-indent:2em;}\n")
                .append("img{max-width:100%;height:auto;display:block;margin:1.2em auto;}\n")
                .append(".cap{text-align:center;color:#999;font-size:.88em;text-indent:0;}\n")
                .append(".meta{color:#999;font-size:.85em;text-indent:0;}\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<h1>").append(Xhtml.esc(book)).append("</h1>\n")
                .append("<p class=\"meta\">导出时间 ").append(stamp)
                .append("　共 ").append(total).append(" 章</p>\n");

        int ok = 0, imgOk = 0, imgMissing = 0;
        for (int i = 0; i < docs.size(); i++) {
            ChapDoc d = docs.get(i);
            if (d.blks.isEmpty()) continue;
            txt.append(d.title).append('\n');
            html.append("<h2>").append(Xhtml.esc(d.title)).append("</h2>\n");
            int imgs = 0;
            for (Xhtml.Blk b : d.blks) {
                if (b.kind == Xhtml.Blk.TEXT) {
                    if (Files.notEmpty(b.text)) {
                        txt.append(b.text).append('\n');
                        html.append("<p>").append(Xhtml.esc(b.text)).append("</p>\n");
                    }
                } else {
                    String fn = b.imgKey == null ? null : keyToFile.get(b.imgKey);
                    if (fn != null && imgData.containsKey(fn)) {
                        html.append("<img src=\"插图/").append(fn).append("\" alt=\"插图\" loading=\"lazy\">\n");
                        imgs++;
                    } else if (b.imgKey != null) {
                        imgMissing++;
                    }
                    if (Files.notEmpty(b.text)) {
                        txt.append("［插图］").append(b.text).append('\n');
                        html.append("<p class=\"cap\">").append(Xhtml.esc(b.text)).append("</p>\n");
                    } else {
                        txt.append("［插图］\n");
                    }
                }
            }
            txt.append('\n');
            d.outImgs = imgs;
            imgOk += imgs;
            ok++;
            if (prog != null) prog.set(55 + 30 * (i + 1) / Math.max(1, docs.size()),
                    "写入正文 " + (i + 1) + "/" + docs.size());
        }
        html.append("</body>\n</html>\n");

        // ── 4) 落盘 ──────────────────────────────────────────
        if (prog != null) prog.set(88, "写入文件…");
        boolean wTxt = Files.write(rel, base + ".txt", txt.toString().getBytes(Files.UTF8), "text/plain");
        boolean wHtml = true;
        if (Store.cfgHtml()) {
            wHtml = Files.write(rel, base + ".html", html.toString().getBytes(Files.UTF8), "text/html");
        }
        int imgWritten = 0;
        if (wantImg) {
            for (java.util.Map.Entry<String, byte[]> e : imgData.entrySet()) {
                if (Files.write(rel + "/插图", e.getKey(), e.getValue(), imgMime.get(e.getKey()))) {
                    imgWritten++;
                }
            }
        }

        // ── 5) 报告 ──────────────────────────────────────────
        int srcTotal = 0, outTotal = 0;
        StringBuilder detail = new StringBuilder();
        for (ChapDoc d : docs) {
            srcTotal += d.srcChars;
            outTotal += d.outChars;
            detail.append(String.format(Locale.US, "%-40s 源 %6d 字  出 %6d 字  图 %d%s%n",
                    cut(d.title, 38), d.srcChars, d.outChars, d.outImgs,
                    d.blks.isEmpty() ? "   ⚠ 解析为空" : ""));
        }
        int dev = outTotal - srcTotal;
        StringBuilder rep = new StringBuilder();
        rep.append("《").append(book).append("》导出报告\n")
                .append("时间 ").append(stamp).append('\n')
                .append("──────────────────────────────\n")
                .append("输出目录   ").append("/sdcard/").append(rel).append('\n')
                .append("章节顺序   ").append(order.strategy)
                .append("  (").append(order.detail).append(')')
                .append(order.confident ? "  ✔ 权威" : "  ⚠ 推定").append('\n')
                .append("章节       ").append(ok).append(" / ").append(total).append('\n')
                .append("标题去重   ").append(dupRemoved).append(" 章（首块=章标题，已合并，不再出现两行标题）").append('\n')
                .append("正文       ").append(outTotal).append(" 字  |  原始 XHTML ")
                .append(srcTotal).append(" 字  |  偏差 ").append(dev > 0 ? "+" : "").append(dev).append('\n')
                .append("插图       正文引用 ").append(imgOk).append(" 张 · 落盘 ")
                .append(imgWritten).append(" 张");
        if (imgMissing > 0) rep.append(" · 未取到 ").append(imgMissing).append(" 张");
        rep.append('\n')
                .append("文件       ").append(base).append(".txt")
                .append(Store.cfgHtml() ? (" + " + base + ".html") : "")
                .append(wantImg ? " + 插图/" : "").append('\n')
                .append("──────────────────────────────\n")
                .append("结论       ")
                .append(dev == 0 ? "✔ 与原始排版字数一致，未发现丢字/丢块"
                        : (dev < 0 ? "⚠ 比原文少 " + (-dev) + " 字，可能有块被吞（看下方逐章明细）"
                        : "ℹ 比原文多 " + dev + " 字（通常是图注被计入正文，属正常）"))
                .append('\n')
                .append(order.confident ? ""
                        : "⚠ 章节顺序是推定值（宿主没给权威序号）。若顺序仍不对，\n"
                        + "  请把 _运行日志.txt 里「章节顺序」那几行发出来，就能定位到该用哪个字段。\n")
                .append("写盘       txt=").append(wTxt).append(" html=").append(wHtml)
                .append(" 插图=").append(imgWritten).append('/').append(imgData.size())
                .append('\n')
                .append("──────────────────────────────\n")
                .append(detail);
        Files.write(rel, "_导出报告.txt", rep.toString().getBytes(Files.UTF8), "text/plain");

        if (prog != null) prog.set(100, "完成");
        Logs.i("导出完成 《" + book + "》 " + ok + "/" + total + " 章 · 插图 " + imgWritten
                + " 张 · -> " + rel);
        Logs.flush(true);

        return "✔ 已保存《" + book + "》\n"
                + "章节 " + ok + "/" + total + " · 插图 " + imgWritten + " 张"
                + (dupRemoved > 0 ? " · 标题去重 " + dupRemoved + " 章" : "") + "\n"
                + "章节顺序 " + order.strategy + (order.confident ? " ✔" : " ⚠推定") + "\n"
                + "位置 /sdcard/" + rel + "/\n"
                + "文件 " + base + ".txt" + (Store.cfgHtml() ? " / .html" : "")
                + (wTxt ? "" : "\n⚠ txt 写入失败，看 _运行日志.txt");
    }

    /**
     * 单章解析：抽正文块 → 处理「首块就是章标题」→ 定标题 → 算校对字数。
     *
     * 抽成独立方法是为了能脱离 Android 运行时做端到端验证
     * （见 test/src/ExportTest.java），而不是只测几个零散函数。
     */
    static ChapDoc prepareChapter(Store.ChapterBuf c, String bookId, int i) {
        String xml = c == null || c.xml == null ? "" : new String(c.xml, Files.UTF8);
        ChapDoc d = new ChapDoc();
        d.blks = Xhtml.extractBlocks(xml, bookId);
        String t = (c != null && Files.notEmpty(c.title)) ? c.title.trim() : Xhtml.titleOf(xml);

        // ── 章标题去重 ──
        // 番茄的章节 XHTML 第一个 <blk> 往往就是章标题本身：
        //     <blk>第三章 开端</blk><blk>正文第一段……</blk>
        // 不去掉的话，正文前面就会出现「两个标题」。
        int dropped = 0;
        if (!d.blks.isEmpty() && d.blks.get(0).kind == Xhtml.Blk.TEXT) {
            String first = d.blks.get(0).text;
            if (Files.notEmpty(t)) {
                if (Xhtml.dupTitle(t, first)) {
                    // 首块可能比宿主给的章名更全（带副标题），先升级再吃掉
                    t = Xhtml.richerTitle(t, first);
                    d.blks.remove(0);
                    dropped = Xhtml.nonWsLen(first);
                    d.dupRemoved = true;
                }
            } else if (Xhtml.looksLikeChapterTitle(first)) {
                // 宿主没给章名、正文里也没有 <h1>：直接拿首块当标题
                t = first.trim();
                d.blks.remove(0);
                dropped = Xhtml.nonWsLen(first);
                d.dupRemoved = true;
            }
        }
        d.title = Files.notEmpty(t) ? t : ("第" + (i + 1) + "章");
        // 校对口径：源侧只数 <blk>（真正会进正文的那部分），
        // 拿整篇比会把 <h1> 章名算进去，每章都假报警；
        // 被当作标题吃掉的那块也要扣掉，否则每章都少算一个标题的字数
        d.srcChars = Math.max(0, Xhtml.rawBlkLen(xml) - dropped);
        d.outChars = Xhtml.outChars(d.blks);
        return d;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
