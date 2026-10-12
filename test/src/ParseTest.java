import com.fq.localsave.Xhtml;
import java.util.List;

public class ParseTest {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<chapter><h1>第3章 开端</h1>"
            + "<blk>第一段正文。</blk>"
            + "<blk>第二段 &amp; 带转义 &lt;符号&gt;。</blk>"
            + "<div class=\"image\"><img src=\"https://p6-novel-pic-r/abc123.jpg?x=1\"/><blk>图注一</blk></div>"
            + "<blk>第三段。</blk>"
            + "<div class=\"image\"><img src=\"https://p9-novel-pic-r/deadbeef.png\"/></div>"
            + "<blk>第四段。</blk></chapter>";
        List<Xhtml.Blk> bs = Xhtml.extractBlocks(xml, null);
        for (Xhtml.Blk b : bs) {
            System.out.println("  " + (b.kind == Xhtml.Blk.IMG ? "IMG" : "TXT")
                + " | " + b.text + " | key=" + b.imgKey);
        }
        int src = Xhtml.rawBlkLen(xml), out = Xhtml.outChars(bs);
        System.out.println("块数=" + bs.size() + "  titleOf=" + Xhtml.titleOf(xml));
        System.out.println("校对口径: 源<blk>字符=" + src + "  导出字符=" + out + "  偏差=" + (out - src));
        System.out.println("ordinal: " + Xhtml.ordinal("第一百二十三章") + " / " + Xhtml.ordinal("第42章")
                + " / " + Xhtml.ordinal("第 7 节") + " / " + Xhtml.ordinal("楔子"));
        System.out.println("cn2int(一万零二十四)=" + Xhtml.cn2int("一万零二十四"));
        System.out.println("imgKeyOf=" + Xhtml.imgKeyOf("https://p6-novel-pic-r/abc123.jpg?x=1"));
    }
}
