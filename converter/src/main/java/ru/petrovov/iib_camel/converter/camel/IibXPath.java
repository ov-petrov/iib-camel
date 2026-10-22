package ru.petrovov.iib_camel.converter.camel;

import javax.xml.XMLConstants;
import javax.xml.xpath.XPathFactory;

final class IibXPath {
    private IibXPath() {
    }

    static String translate(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("filterPattern не задан");
        }
        var translated = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < pattern.length();) {
            char c = pattern.charAt(i);
            if (quote != 0) {
                translated.append(c);
                if (c == quote) {
                    quote = 0;
                }
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
                translated.append(c);
                i++;
            } else if (c == '$') {
                int end = i + 1;
                while (end < pattern.length() && (Character.isLetterOrDigit(pattern.charAt(end))
                        || "_.-".indexOf(pattern.charAt(end)) >= 0)) {
                    end++;
                }
                String variable = pattern.substring(i, end);
                if (variable.equals("$Root")) {
                    String domain = pattern.startsWith("/XMLNSC", end) ? "/XMLNSC" : "/XMLNS";
                    if (!pattern.startsWith(domain, end)) {
                        throw new IllegalArgumentException("поддерживается только $Root/XMLNSC или $Root/XMLNS");
                    }
                    end += domain.length();
                    if (end < pattern.length() && (Character.isLetterOrDigit(pattern.charAt(end))
                            || "_.-:".indexOf(pattern.charAt(end)) >= 0)) {
                        throw new IllegalArgumentException("неподдерживаемый домен $Root");
                    }
                } else if (!variable.equals("$Body")) {
                    throw new IllegalArgumentException("неподдерживаемая переменная XPath: " + variable);
                }
                // IIB's parser-domain node corresponds to the serialized XML document node.
                if (end == pattern.length() || pattern.charAt(end) != '/') {
                    translated.append('/');
                }
                i = end;
            } else {
                if (c == ':' || (c == '.' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '.')) {
                    throw new IllegalArgumentException("пространства имён и переход к родителю XPath пока не поддерживаются");
                }
                translated.append(c);
                i++;
            }
        }
        try {
            var factory = XPathFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newXPath().compile(translated.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("некорректный или неподдерживаемый XPath: " + e.getMessage(), e);
        }
        return translated.toString();
    }
}
