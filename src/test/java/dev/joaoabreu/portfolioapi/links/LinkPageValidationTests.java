package dev.joaoabreu.portfolioapi.links;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkPageValidationTests {
    static LinkPage page(String title, String url, String icon) {
        return new LinkPage(List.of(new LinkPage.Link(title, url, icon)));
    }

    static String field(LinkPage page) {
        return assertThrows(LinkPage.Invalid.class, page::validated).field;
    }

    @Test
    void defaultsAreValid() {
        assertEquals(LinkPage.DEFAULTS, LinkPage.DEFAULTS.validated());
    }

    @Test
    void trimsTitlesAndAddresses() {
        var link = page("  GitHub ", " https://github.com/jg-abreu ", "github").validated().links().get(0);
        assertEquals(new LinkPage.Link("GitHub", "https://github.com/jg-abreu", "github"), link);
        assertEquals("mailto:someone@example.com", page("Mail", "mailto:someone@example.com", "mail").validated().links().get(0).url());
    }

    @Test
    void rejectsUnsafeOrMalformedAddresses() {
        for (String url : List.of("http://example.com", "javascript:alert(1)", "https://user@example.com",
                "https://example.com:8443/", "https://localhost/", "https://exa mple.com", "//example.com",
                "mailto:not-an-email", "mailto:a@b.com?bcc=c@d.com", "https://example.com/" + "a".repeat(300), ""))
            assertEquals("links[0].url", field(page("Title", url, "link")), url);
    }

    @Test
    void rejectsEmptyLongOrControlTitles() {
        for (String title : List.of(" ", "T".repeat(41), "line\nbreak", "zero​width"))
            assertEquals("links[0].title", field(page(title, "https://example.com", "link")));
        assertDoesNotThrow(() -> page("Résumé ✨", "https://example.com", "link").validated());
    }

    @Test
    void iconsAreShortNames() {
        for (String icon : List.of("", "House", "<svg>", "a".repeat(33)))
            assertEquals("links[0].icon", field(page("Title", "https://example.com", icon)));
        assertEquals("links[0].icon", field(page("Title", "https://example.com", null)));
    }

    @Test
    void holdsOneToTwentyLinks() {
        assertEquals("links", field(new LinkPage(List.of())));
        assertEquals("links", field(new LinkPage(null)));
        var many = new ArrayList<>(Collections.nCopies(21, new LinkPage.Link("T", "https://example.com", "link")));
        assertEquals("links", field(new LinkPage(many)));
        assertDoesNotThrow(() -> new LinkPage(many.subList(0, 20)).validated());
    }
}
