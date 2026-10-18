package com.ricodevvv.codinject;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny DOM helpers to read POMs and {@code maven-metadata.xml} without pulling any library.
 */
final class Xml {

    private Xml() {
    }

    static Element parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.toString());
        }
    }

    static Element parse(byte[] data, String source) throws IOException {
        return parse(new ByteArrayInputStream(data), source);
    }

    private static Element parse(InputStream in, String source) throws IOException {
        try {
            Document document = builder().parse(in);
            return document.getDocumentElement();
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("Malformed XML in " + source, e);
        }
    }

    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        disable(factory, "http://xml.org/sax/features/external-general-entities");
        disable(factory, "http://xml.org/sax/features/external-parameter-entities");
        disable(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd");
        return factory.newDocumentBuilder();
    }

    private static void disable(DocumentBuilderFactory factory, String feature) {
        try {
            factory.setFeature(feature, false);
        } catch (ParserConfigurationException ignored) {
        }
    }

    static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && (name == null || name.equals(node.getNodeName()))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    static String text(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }
}
