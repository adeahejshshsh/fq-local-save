import com.fq.localsave.Store;

public class OrderTest {
    static Store.Info mk(String book, String cid, String name, int idx) {
        Store.Info i = new Store.Info();
        i.bookId = book; i.chapterId = cid; i.chapterName = name; i.chapterIndex = idx;
        i.bookName = "测试书"; i.content = "x" + cid;
        return i;
    }
    static void put(String book, String cid, String name, int idx) {
        Store.bufPut(mk(book, cid, name, idx), ("<blk>" + cid + "</blk>").getBytes());
    }
    static void show(String title, String book) {
        Store.Order o = Store.order(book);
        StringBuilder sb = new StringBuilder();
        for (Store.ChapterBuf c : o.list) sb.append(c.chapterId).append(' ');
        System.out.println(title);
        System.out.println("   策略=" + o.strategy + " | " + o.detail + " | 权威=" + o.confident);
        System.out.println("   顺序=" + sb);
    }

    public static void main(String[] a) {
        // 场景 A：宿主自带 index（乱序抓取，靠 index 复原）
        String[] ord = {"c3", "c1", "c5", "c2", "c4"};
        int[] idx = {3, 1, 5, 2, 4};
        for (int i = 0; i < ord.length; i++) put("A", ord[i], "随便起的名" + idx[i], idx[i]);
        show("A) 有宿主 index，乱序抓取 →", "A");

        // 场景 B：无 index，章节名带「第N章」（乱序抓取）
        String[] names = {"第五章 风起", "第一章 开端", "第三章 变故", "第二章 相遇", "第四章 抉择"};
        String[] ids = {"b5", "b1", "b3", "b2", "b4"};
        for (int i = 0; i < names.length; i++) put("B", ids[i], names[i], -1);
        show("B) 无 index，章节名有「第N章」→", "B");

        // 场景 C：无 index，无「第N章」，chapterId 数值与抓取顺序单调
        long[] cid = {1001, 1002, 1003, 1004, 1005};
        for (long c : cid) put("C", String.valueOf(c), "楔子" + c, -1);
        show("C) 只有 chapterId，且单调 →", "C");

        // 场景 D：什么都没有，只能按抓取顺序
        String[] d = {"x9", "x3", "x7", "x1"};
        for (String s : d) put("D", s, "无名" + s, -1);
        show("D) 啥都没有 →", "D");

        // 场景 E：chapterId 存在但明显不单调（模拟「章节调整」过的书）
        long[] bad = {5000, 100, 9000, 300};
        for (long c : bad) put("E", String.valueOf(c), "无名" + c, -1);
        show("E) chapterId 不单调 →", "E");
    }
}
