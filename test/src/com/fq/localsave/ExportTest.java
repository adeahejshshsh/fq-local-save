package com.fq.localsave;

import java.lang.reflect.Field;
import java.util.List;

/** 端到端验证「单章解析」：喂真形状的 XHTML，看标题会不会重复出现两行。 */
public class ExportTest {
    static int pass = 0, fail = 0;

    static Store.ChapterBuf chap(String title, String xml) {
        Store.ChapterBuf c = new Store.ChapterBuf(-1, -1, 1, "1001", title, xml.getBytes(Files.UTF8));
        return c;
    }

    static void check(String name, String title, String xml, String wantTitle, int wantBlocks) {
        Exporter.ChapDoc d = Exporter.prepareChapter(chap(title, xml), null, 0);
        // 模拟 txt 写出：标题一行 + 各块
        StringBuilder sb = new StringBuilder();
        sb.append(d.title).append('\n');
        for (Xhtml.Blk b : d.blks) {
            if (b.kind == Xhtml.Blk.TEXT) sb.append(b.text).append('\n');
            else sb.append("［插图］").append(b.text == null ? "" : b.text).append('\n');
        }
        String out = sb.toString();
        int titleLines = 0;
        for (String line : out.split("\n")) {
            if (line.trim().equals(wantTitle)) titleLines++;
        }
        boolean okTitle = wantTitle.equals(d.title) && d.blks.size() == wantBlocks;
        boolean okDup = titleLines == 1;
        boolean okChars = d.srcChars == d.outChars;
        if (okTitle && okDup && okChars) {
            pass++;
            System.out.println("  ✔ " + name);
        } else {
            fail++;
            System.out.println("  ✘ " + name
                    + " | 标题=" + d.title + "(期望 " + wantTitle + ")"
                    + " 块数=" + d.blks.size() + "(期望 " + wantBlocks + ")"
                    + " 标题出现行数=" + titleLines
                    + " 源字=" + d.srcChars + " 出字=" + d.outChars);
        }
        System.out.println("    输出预览: " + out.replace("\n", " ⏎ "));
    }

    public static void main(String[] a) {
        System.out.println("【首块=章标题：必须只剩一行标题】");
        check("宿主章名与首块完全一致",
              "第三章 开端",
              "<chapter><blk>第三章 开端</blk><blk>他睁开眼。</blk></chapter>",
              "第三章 开端", 1);
        check("宿主章名中文数字，首块阿拉伯数字",
              "第三章 开端",
              "<chapter><blk>第3章 开端</blk><blk>他睁开眼。</blk></chapter>",
              "第三章 开端", 1);
        check("宿主章名只有「第N章」，首块带副标题 → 升级为更全的",
              "第3章",
              "<chapter><blk>第3章 开端</blk><blk>他睁开眼。</blk></chapter>",
              "第3章 开端", 1);
        check("宿主没给章名，首块是标题",
              "",
              "<chapter><blk>第三章 开端</blk><blk>他睁开眼。</blk></chapter>",
              "第三章 开端", 1);
        check("XHTML 里有 h1 且首块重复",
              "",
              "<chapter><h1>第三章 开端</h1><blk>第三章 开端</blk><blk>他睁开眼。</blk></chapter>",
              "第三章 开端", 1);

        System.out.println("【首块不是标题：不许误吃正文】");
        check("首块就是正文",
              "第三章 开端",
              "<chapter><blk>他睁开眼，天已经亮了。</blk><blk>第二段。</blk></chapter>",
              "第三章 开端", 2);
        check("宿主没给章名、首块是普通段落 → 用合成标题",
              "",
              "<chapter><blk>他睁开眼，天已经亮了。</blk></chapter>",
              "第1章", 1);
        check("章节名与首块章号不同，不许吃",
              "第三章 开端",
              "<chapter><blk>第四章 变故</blk><blk>正文。</blk></chapter>",
              "第三章 开端", 2);

        System.out.println("\n结果: 通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
