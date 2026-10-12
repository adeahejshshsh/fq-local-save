import com.fq.localsave.Xhtml;

public class DupTest {
    static int pass = 0, fail = 0;

    static void dup(String title, String first, boolean want) {
        boolean got = Xhtml.dupTitle(title, first);
        ok("dupTitle(\"" + title + "\", \"" + first + "\")", want, got);
    }

    static void ok(String what, boolean want, boolean got) {
        if (want == got) { pass++; System.out.println("  ✔ " + what + " = " + got); }
        else { fail++; System.out.println("  ✘ " + what + " 期望 " + want + " 实际 " + got); }
    }

    public static void main(String[] a) {
        System.out.println("【应判为重复】");
        dup("第三章 开端", "第三章 开端", true);
        dup("第三章 开端", "第3章　开端", true);          // 中文数字 + 全角空格
        dup("第3章", "第3章 开端", true);                // 一边只有「第N章」
        dup("开端", "第3章 开端", true);                 // 标题没前缀，首块有
        dup("第3章 开端", "第3章", true);
        dup("第三章 开端", "第三章开端", true);           // 空格差异
        dup("第12章 风起云涌", "第十二章 风起云涌", true);

        System.out.println("【不应判为重复】");
        dup("第三章 开端", "正文第一段，主角醒了过来。", false);
        dup("第三章 开端", "第4章 别的东西", false);      // 章号不同
        dup("开端", "这是一个很长的正文段落，绝对不是标题，而且长度远超十个字。", false);
        // 标题只有「第N章」、首块是「第N章 xxx」→ 确实同一个标题，该合并
        dup("第3章", "第3章 完全不同的另一个标题名字", true);
        dup("", "第3章 开端", false);                     // 标题为空由调用方另走一支
        dup("楔子", "第一章 开端", false);

        System.out.println("【首块像不像标题】");
        ok("looksLikeChapterTitle(\"第三章 开端\")", true, Xhtml.looksLikeChapterTitle("第三章 开端"));
        ok("looksLikeChapterTitle(\"第 7 节\")", true, Xhtml.looksLikeChapterTitle("第 7 节"));
        ok("looksLikeChapterTitle(\"楔子\")", false, Xhtml.looksLikeChapterTitle("楔子"));
        ok("looksLikeChapterTitle(长段落)", false,
           Xhtml.looksLikeChapterTitle("他推开门，外面下着雨，街上没有一个人，风把广告牌吹得咣当作响，这一切都不太对劲。"));

        System.out.println("【同标题时取信息更全的那个】");
        ok("richerTitle(第3章, 第3章 开端) = 第3章 开端",
           "第3章 开端".equals(Xhtml.richerTitle("第3章", "第3章 开端")), true);
        ok("richerTitle(第三章 开端, 第3章) = 第三章 开端",
           "第三章 开端".equals(Xhtml.richerTitle("第三章 开端", "第3章")), true);
        ok("richerTitle(开端, 第3章 开端) = 第3章 开端",
           "第3章 开端".equals(Xhtml.richerTitle("开端", "第3章 开端")), true);

        System.out.println("【标题键】");
        System.out.println("  titleKey(第三章 开端) = " + Xhtml.titleKey("第三章 开端"));
        System.out.println("  titleKey(第3章　开端) = " + Xhtml.titleKey("第3章　开端"));
        System.out.println("  titleKey(楔子)        = " + Xhtml.titleKey("楔子"));

        System.out.println("\n结果: 通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
