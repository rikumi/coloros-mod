import com.rikumi.colorosmod.FontFileInfo;
import com.rikumi.colorosmod.FontOverlayXml;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class FontOverlayXmlTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static Element family(Document d, String name) {
        NodeList list = d.getElementsByTagName("family");
        for (int i = 0; i < list.getLength(); i++) if (((Element) list.item(i)).getAttribute("name").equals(name)) return (Element) list.item(i);
        throw new AssertionError("No family " + name);
    }
    static FontFileInfo.Face face(int index, boolean mono) {
        FontFileInfo.Face f = new FontFileInfo.Face(); f.index = index; f.monospace = mono; f.weight = 400; return f;
    }
    public static void main(String[] args) throws Exception {
        FontFileInfo.Face proportional = face(0, false), mono = face(2, true), italic = face(3, true);
        italic.italic = true; mono.minWeight = 200; mono.maxWeight = 800;
        FontOverlayXml.Source source = new FontOverlayXml.Source("colorosmod/mono.ttf", List.of(proportional, mono, italic));
        Map<String, List<FontOverlayXml.Source>> selected = new HashMap<>();
        selected.put("mono", List.of(source));
        selected.put("latin", List.of(new FontOverlayXml.Source("colorosmod/latin.ttf", List.of(proportional))));
        String xml = "<familyset version='23'><family name='sans-serif'><font>Original.ttf</font></family>"
                + "<family name='monospace'><font>Wrong.ttf</font></family>"
                + "<family name='sans-serif-monospace'><font>WrongLatin.ttf</font></family>"
                + "<family name='serif-monospace'><font>CutiveMono.ttf</font></family>"
                + "<family lang='zh-Hans'><font>SysSans-Hans-Regular.ttf</font><font fallbackFor='serif'>NotoSerifCJK.ttc</font></family>"
                + "<family lang='und-Zsye'><font>NotoColorEmoji.ttf</font></family>"
                + "<alias name='courier new' to='sans-serif'/></familyset>";
        byte[] transformed = FontOverlayXml.rewrite(xml.getBytes(StandardCharsets.UTF_8), selected);
        Document d = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(transformed));
        check(family(d,"sans-serif").getTextContent().contains("latin.ttf"), "Latin replacement");
        for (String name : List.of("monospace", "sans-serif-monospace", "serif-monospace")) {
            Element f = family(d, name);
            check(f.getTextContent().contains("mono.ttf") && !f.getTextContent().contains("latin.ttf"), "Monospace priority: " + name);
            NodeList fonts = f.getElementsByTagName("font");
            for (int i = 0; i < fonts.getLength(); i++) {
                Element font = (Element) fonts.item(i);
                check(!font.getAttribute("index").equals("0"), "Skip proportional TTC member");
                check(!font.getTextContent().startsWith("/"), "Relative paths for native XML parsers");
                if (font.getAttribute("index").equals("2")) {
                    int weight = Integer.parseInt(font.getAttribute("weight"));
                    check(weight >= 200 && weight <= 800, "Variable weight clamp");
                    check(font.getElementsByTagName("axis").getLength() == 1, "Weight axis emitted");
                }
            }
        }
        check(d.getElementsByTagName("alias").item(0).getAttributes().getNamedItem("to").getNodeValue().equals("monospace"), "Repair monospace alias");
        String text = new String(transformed, StandardCharsets.UTF_8);
        check(text.contains("NotoColorEmoji.ttf"), "Preserve emoji fallback");
        check(text.contains("SysSans-Hans-Regular.ttf") && text.contains("NotoSerifCJK.ttc"), "Preserve unchanged Chinese and serif fallbacks");
        selected.put("zh", List.of(new FontOverlayXml.Source("colorosmod/chinese.ttf", List.of(proportional))));
        text = new String(FontOverlayXml.rewrite(xml.getBytes(StandardCharsets.UTF_8), selected), StandardCharsets.UTF_8);
        check(text.contains("chinese.ttf") && !text.contains("SysSans-Hans-Regular.ttf") && text.contains("NotoSerifCJK.ttc"), "Replace CJK without replacing serif fallbackFor");
        System.out.println("PASS: " + checks + " native font XML checks");
    }
}
