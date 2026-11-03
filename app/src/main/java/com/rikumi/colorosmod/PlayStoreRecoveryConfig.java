package com.rikumi.colorosmod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Changes only the recovery-list entries in the vendor's persistent data configuration. */
public final class PlayStoreRecoveryConfig {
    public static final String TAG = "special_safe_mode_package";

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        var parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        if (bytes.length == 0) {
            Document document = parser.newDocument();
            document.appendChild(document.createElement("config"));
            return document;
        }
        Document document = parser.parse(new ByteArrayInputStream(bytes));
        if (document.getDoctype() != null) throw new IllegalArgumentException("Unexpected PMS configuration doctype");
        return document;
    }

    public static List<String> entries(byte[] bytes) throws Exception {
        NodeList nodes = parse(bytes).getElementsByTagName(TAG);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent());
        return result;
    }

    public static Set<String> protectedPackages(byte[] original, Collection<String> defaults) throws Exception {
        return protectedPackages(original, defaults, name -> name.length() <= 255
                && name.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*"));
    }

    public static Set<String> protectedPackages(byte[] original, Collection<String> defaults,
                                                 Predicate<String> validator) throws Exception {
        List<String> entries = entries(original);
        Set<String> packages = new LinkedHashSet<>();
        boolean valid = !entries.isEmpty();
        for (String entry : entries) {
            String name = entry.trim();
            // The native parser rejects the entire override if any entry is invalid.
            if (!validator.test(name)) valid = false;
            packages.add(name);
        }
        if (!valid) packages = new LinkedHashSet<>(defaults);
        packages.remove(PlayStoreUpdatePolicy.PACKAGE);
        if (packages.isEmpty()) throw new IllegalArgumentException("An empty list restores the vendor defaults");
        for (String name : packages) if (!validator.test(name))
            throw new IllegalArgumentException("Invalid native recovery package: " + name);
        return packages;
    }

    public static byte[] rewrite(byte[] original, Collection<String> entries) throws Exception {
        Document document = parse(original);
        NodeList nodes = document.getElementsByTagName(TAG);
        while (nodes.getLength() != 0) {
            Node node = nodes.item(0);
            node.getParentNode().removeChild(node);
        }
        for (String value : entries) {
            Element element = document.createElement(TAG);
            element.setTextContent(value);
            document.getDocumentElement().appendChild(element);
        }
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private PlayStoreRecoveryConfig() {}
}
