package com.rikumi.colorosmod;

import java.io.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

public final class FontOverlayXml {
    public static final class Source {
        public final String path;
        public final List<FontFileInfo.Face> faces;
        public Source(String path, List<FontFileInfo.Face> faces) { this.path = path; this.faces = faces; }
    }
    private static Document parse(byte[] original) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return parser.parse(new ByteArrayInputStream(original));
    }
    public static byte[] rewrite(byte[] original, Map<String, List<Source>> selected) throws Exception {
        Document document = parse(original);
        NodeList families = document.getElementsByTagName("family");
        for (int i = 0; i < families.getLength(); i++) {
            Element family = (Element) families.item(i);
            String name = family.getAttribute("name");
            if (name.isEmpty() && family.getParentNode() instanceof Element)
                name = ((Element) family.getParentNode()).getAttribute("name");
            String language = family.getAttribute("lang");
            String role = null;
            if (Arrays.stream(language.split(",")).anyMatch(lang -> lang.contains("Zsye"))) role = "emoji";
            else if (MonospaceNames.matches(name)) role = "mono";
            else if (name.equals("sans-serif") || name.startsWith("sans-serif-")
                    || name.equals("sys-sans-en") || name.equals("op-sans-en")) role = "latin";
            else if (language.startsWith("zh")) role = "zh";
            List<Element> originals = directFonts(family);
            if (role == null && originals.stream().anyMatch(font -> {
                String text = font.getTextContent().toLowerCase(Locale.ROOT);
                return text.contains("hans") || text.contains("hant");
            })) role = "zh";
            if (role == null || !selected.containsKey(role) || originals.isEmpty()) continue;
            if (role.equals("zh") && originals.stream().allMatch(FontOverlayXml::serif)) continue;
            // Keep separate serif fallbackFor entries in mixed CJK families.
            for (Element font : originals) if (!role.equals("zh") || !serif(font)) family.removeChild(font);
            appendFonts(document, family, selected.get(role), language, role.equals("mono"));
            family.removeAttribute("supportedAxes");
        }
        if (selected.containsKey("mono")) {
            NodeList aliases = document.getElementsByTagName("alias");
            for (int i = 0; i < aliases.getLength(); i++) {
                Element alias = (Element) aliases.item(i);
                if (MonospaceNames.matches(alias.getAttribute("name")) && !alias.getAttribute("name").equals("monospace"))
                    alias.setAttribute("to", "monospace");
            }
        }
        if (!selected.isEmpty()) {
            // Keep the default named text family first. Emoji precedes other fallback families;
            // selected text fonts omit ambiguous emoji from nominal cmap while retaining VS15.
            List<Element> emoji = new ArrayList<>();
            Element firstFallback = null;
            for (int i = 0; i < families.getLength(); i++) {
                Element family = (Element) families.item(i);
                if (family.getParentNode() != document.getDocumentElement()) continue;
                if (family.getAttribute("lang").contains("Zsye")) {
                    if (!family.getAttribute("ignore").equals("true")) emoji.add(family);
                } else if (firstFallback == null && family.getAttribute("name").isEmpty()) firstFallback = family;
            }
            if (firstFallback != null) for (Element family : emoji)
                document.getDocumentElement().insertBefore(family, firstFallback);
        }
        return serialize(document);
    }

    /** Skia prepends the partition's fonts directory, so walk back to / before entering /data. */
    public static byte[] filesystem(byte[] original) throws Exception {
        Document document = parse(original);
        NodeList fonts = document.getElementsByTagName("font");
        for (int i = 0; i < fonts.getLength(); i++) {
            Element font = (Element) fonts.item(i);
            String path = font.getTextContent().trim();
            if (!path.matches("(?:\\.\\./\\.\\./system/fonts/)?colorosmod/[0-9a-f]{64}\\.ttf")) continue;
            String hash = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
            for (Node child = font.getFirstChild(); child != null; child = child.getNextSibling())
                if (child.getNodeType() == Node.TEXT_NODE) child.setNodeValue("");
            font.insertBefore(document.createTextNode("../../data/fonts/files/coloros-mod-webview/" + hash + ".font"),
                    font.getFirstChild());
        }
        return serialize(document);
    }

    private static byte[] serialize(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    /** Insert ownership outside all stock comments, regardless of declaration/comment line breaks. */
    public static byte[] mountSnapshot(byte[] original, String version) throws Exception {
        if (!version.matches("[0-9a-f]{64}")) throw new IOException("Invalid font snapshot version");
        Document document = parse(filesystem(original));
        document.insertBefore(document.createComment(" coloros-mod-fonts:" + version + " "),
                document.getDocumentElement());
        byte[] result = serialize(document);
        // Validate the exact bytes handed to the privileged copier, not just the unmarked XML.
        parse(result);
        return result;
    }
    private static List<Element> directFonts(Element family) {
        List<Element> fonts = new ArrayList<>();
        for (Node node = family.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && node.getNodeName().equals("font")) fonts.add((Element) node);
        return fonts;
    }
    private static boolean serif(Element font) {
        return font.getAttribute("fallbackFor").equals("serif")
                || font.getTextContent().toLowerCase(Locale.ROOT).contains("serif");
    }
    private static void appendFonts(Document document, Element family, List<Source> sources,
                                    String language, boolean mono) throws Exception {
        SortedSet<Integer> weights = new TreeSet<>();
        for (int w = 100; w <= 900; w += 100) weights.add(w);
        for (Source source : sources) for (FontFileInfo.Face face : source.faces) {
            weights.add(face.weight);
            if (face.variable()) { weights.add(Math.round(face.minWeight)); weights.add(Math.round(face.maxWeight)); }
        }
        Set<String> styles = new HashSet<>();
        for (boolean italic : new boolean[]{false, true}) for (int weight : weights) {
            Source bestSource = null; FontFileInfo.Face bestFace = null; int bestScore = Integer.MAX_VALUE;
            for (Source source : sources) {
                List<FontFileInfo.Face> faces = new ArrayList<>(source.faces);
                if (mono) faces.removeIf(face -> !face.monospace);
                if (faces.isEmpty()) continue;
                FontFileInfo.Face face = FontFileInfo.match(faces, weight, italic, language);
                int score = FontFileInfo.matchScore(face, weight, italic, language);
                if (score < bestScore) { bestScore = score; bestFace = face; bestSource = source; }
            }
            if (bestFace == null) throw new IOException("Selected font has no usable faces");
            int actualWeight = bestFace.weightAt(weight);
            if (!styles.add(actualWeight + ":" + bestFace.italic)) continue;
            Element font = document.createElement("font");
            font.setAttribute("weight", String.valueOf(actualWeight));
            font.setAttribute("style", bestFace.italic ? "italic" : "normal");
            font.setAttribute("index", String.valueOf(bestFace.index));
            font.appendChild(document.createTextNode(bestSource.path));
            if (bestFace.variable()) {
                Element axis = document.createElement("axis");
                axis.setAttribute("tag", "wght"); axis.setAttribute("stylevalue", String.valueOf(actualWeight));
                font.appendChild(axis);
            }
            family.appendChild(font);
        }
    }

    public static final class MonospaceNames {
        public static boolean matches(String name) {
            return name != null && Arrays.asList("monospace", "sans-serif-monospace", "serif-monospace",
                    "monaco", "courier", "courier new").contains(name.toLowerCase(Locale.ROOT));
        }
    }
    private FontOverlayXml() {}
}
