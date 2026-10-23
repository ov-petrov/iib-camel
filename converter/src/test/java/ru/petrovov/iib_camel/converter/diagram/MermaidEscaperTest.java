package ru.petrovov.iib_camel.converter.diagram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MermaidEscaperTest {

    @Test
    void escapesSpecialCharacters() {
        assertEquals("a&quot;b", MermaidEscaper.escape("a\"b"));
        assertEquals("a&num;b", MermaidEscaper.escape("a#b"));
        assertEquals("a&#124;b", MermaidEscaper.escape("a|b"));
        assertEquals("a&lt;b&gt;c", MermaidEscaper.escape("a<b>c"));
    }

    @Test
    void collidesPipesAndHashesWithoutCorruption() {
        assertEquals("&num;&#124;", MermaidEscaper.escape("#|"));
    }

    @Test
    void replacesLineBreaksAndTabsWithSpaces() {
        assertEquals("a b c", MermaidEscaper.escape("a\nb\tc"));
        assertEquals("a b", MermaidEscaper.escape("a\rb"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", MermaidEscaper.escape(null));
        assertEquals("", MermaidEscaper.escape(""));
    }

    @Test
    void leavesPlainTextUntouched() {
        assertEquals("Adapter Input", MermaidEscaper.escape("Adapter Input"));
    }
}
