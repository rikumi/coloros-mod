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
    public static byte[] rewrite(byte[] original, Map<String, List<Source>> selected) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = parser.parse(new ByteArrayInputStream(original));
        NodeList families = document.getElementsByTagName("family");
        for (int i = 0; i < families.getLength(); i++) {
            Element family = (Element) families.item(i);
            String name = family.getAttribute("name");
            if (name.isEmpty() && family.getParentNode() instanceof Element)
                name = ((Element) family.getParentNode()).getAttribute("name");
            String language = family.getAttribute("lang");
            String role = null;
            if (MonospaceNames.matches(name)) role = "mono";
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
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
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
                int score = Math.abs(face.weightAt(weight) - weight) + (face.italic == italic ? 0 : 2000);
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
