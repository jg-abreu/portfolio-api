package dev.joaoabreu.portfolioapi.profile;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Accent;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Block;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Emphasis;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Kind;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Line;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Theme;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Width;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;

class ProfileCardRendererTests {
    final GitHubProfileSource.Data data = new GitHubProfileSource.Data(1234, "synthetic-project");
    final Instant at = Instant.parse("2026-11-14T12:00:00Z");
    final ProfileCardSettings d = ProfileCardSettings.DEFAULTS;

    static List<String> panels(String svg) {
        var matcher = Pattern.compile("<rect x=\"(\\d+)\" y=\"(\\d+)\" width=\"(\\d+)\" height=\"(\\d+)\" rx=\"12\"").matcher(svg);
        List<String> found = new ArrayList<>();
        while (matcher.find()) found.add(matcher.group(1) + "," + matcher.group(2) + "," + matcher.group(3) + "," + matcher.group(4));
        return found;
    }

    ProfileCardSettings with(List<Block> blocks) {
        return new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), d.headline(), blocks);
    }

    static Block text(String id, Width width, String title, String... lines) {
        List<Line> parsed = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) parsed.add(new Line(lines[i], i == 0 ? Emphasis.STRONG : Emphasis.NORMAL));
        return new Block(id, Kind.TEXT, title, width, parsed, List.of());
    }

    @Test
    void defaultsKeepThePreviousContentInHalfAndFullRows() {
        String svg = ProfileCardRenderer.render(d.validated(), data, at).svg();
        assertTrue(svg.contains("width=\"900\" height=\"770\""));
        assertEquals(List.of("40,150,400,120", "460,150,400,120", "40,290,820,116", "40,426,400,149",
                "460,426,400,149", "40,595,400,99", "460,595,400,99"), panels(svg));
        assertTrue(svg.contains(">1,234<") && svg.contains(">synthetic-project<"));
        assertTrue(svg.contains(">CURRENT FOCUS<") && svg.contains(">Computer Science · UVA<"));
        assertTrue(svg.contains("#0a0a0a") && svg.contains("#3b82f6"));
    }

    @Test
    void legacySettingsConvertToEquivalentBlocks() {
        var legacy = ProfileCardSettings.Legacy.DEFAULTS;
        var converted = new ProfileCardSettings.Legacy(Theme.LIGHT, Accent.GREEN, legacy.name(), legacy.role(), legacy.monogram(),
                legacy.headline(), List.of("STACK", "FOCUS"), List.of("Java"), legacy.educationTitle(), legacy.educationDetail(),
                legacy.projectTitle(), legacy.projectStack(), legacy.projectNote(), "Synthetic focus", legacy.opportunity())
                .convert().validated();
        assertEquals(Theme.LIGHT, converted.theme());
        assertEquals(List.of("stack", "focus"), converted.blocks().stream().map(Block::id).toList());
        assertEquals("Synthetic focus", converted.blocks().get(1).lines().getFirst().text());
        assertEquals(List.of("Java"), converted.blocks().getFirst().stack());
    }

    @Test
    void rowsPackByWidthAndTheLastBlockStretchesToCloseTheRow() {
        String svg = ProfileCardRenderer.render(with(List.of(
                text("a", Width.THIRD, "One", "First"),
                text("b", Width.THIRD, "Two", "Second"),
                text("c", Width.HALF, "Three", "Third"),
                text("d", Width.FULL, "Four", "Fourth"))).validated(), data, at).svg();
        assertEquals(List.of("40,150,260,99", "320,150,540,99", "40,269,820,99", "40,388,820,99"), panels(svg));
    }

    @Test
    void textBlocksGrowWithTheirLinesAndRowsShareTheTallestHeight() {
        String svg = ProfileCardRenderer.render(with(List.of(
                text("a", Width.HALF, "Short", "One line"),
                text("b", Width.HALF, "Long", "First", "Second", "Third", "Fourth"))).validated(), data, at).svg();
        assertEquals(List.of("40,150,400,174", "460,150,400,174"), panels(svg));
    }

    @Test
    void stackChipsWrapInsideNarrowBlocks() {
        Block stack = new Block("stack", Kind.STACK, "Stack", Width.THIRD, List.of(),
                List.of("Java", "Spring Boot", "PostgreSQL", "Docker"));
        var settings = with(List.of(stack, text("t", Width.FULL, "Note", "Text"))).validated();
        String svg = ProfileCardRenderer.render(settings, data, at).svg();
        int rows = ProfileCardRenderer.chipRows(stack.stack(), ProfileCardRenderer.span(6) - 2 * ProfileCardRenderer.PAD).size();
        assertEquals("40,150,820," + (55 + rows * 38 + (rows - 1) * 10 + 23), panels(svg).getFirst());
        assertTrue(ProfileCardRenderer.chipRows(stack.stack(), ProfileCardRenderer.span(2) - 2 * ProfileCardRenderer.PAD).size() > 1);
    }

    @Test
    void themeAndAccentComeFromAClosedPalette() {
        var light = new ProfileCardSettings(Theme.LIGHT, Accent.GREEN, d.name(), d.role(), d.monogram(), d.headline(), d.blocks());
        String svg = ProfileCardRenderer.render(light, data, at).svg();
        assertTrue(svg.contains("fill=\"#ffffff\"") && svg.contains("#16a34a"));
        var violet = new ProfileCardSettings(Theme.DARK, Accent.VIOLET, d.name(), d.role(), d.monogram(), d.headline(), d.blocks());
        assertTrue(ProfileCardRenderer.render(violet, data, at).svg().contains("#8b5cf6"));
    }

    @Test
    void editableTextIsEscapedAndTheCardStaysWellFormed() throws Exception {
        var hostile = new ProfileCardSettings(d.theme(), d.accent(), "<a href=x>", d.role(), d.monogram(), "\"'&<script>",
                List.of(text("x", Width.FULL, "<svg>", "</text><script>"),
                        new Block("s", Kind.STACK, "C&C", Width.FULL, List.of(), List.of("<svg>", "C&C")))).validated();
        String svg = ProfileCardRenderer.render(hostile, data, at).svg();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        assertEquals(0, document.getElementsByTagName("script").getLength());
        assertEquals(0, document.getElementsByTagName("a").getLength());
        assertFalse(svg.matches("(?s).*<[^>]* href=.*"));
        assertTrue(svg.contains("&lt;/text&gt;&lt;script&gt;"));
    }

    @Test
    void validationRejectsUnsafeOrOversizedInputAndNamesTheField() {
        record Case(String field, ProfileCardSettings settings) {}
        List<Block> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(text("b" + i, Width.FULL, "T", "1"));
        List<Case> invalid = List.of(
                new Case("theme", new ProfileCardSettings(null, d.accent(), d.name(), d.role(), d.monogram(), d.headline(), d.blocks())),
                new Case("name", new ProfileCardSettings(d.theme(), d.accent(), " ", d.role(), d.monogram(), d.headline(), d.blocks())),
                new Case("monogram", new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), "J A", d.headline(), d.blocks())),
                new Case("headline", new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), "line\nbreak", d.blocks())),
                new Case("blocks", with(List.of())),
                new Case("blocks", with(ten)),
                new Case("blocks[1].id", with(List.of(text("same", Width.HALF, "A", "1"), text("same", Width.HALF, "B", "1")))),
                new Case("blocks[0].id", with(List.of(text("Bad Id", Width.HALF, "A", "1")))),
                new Case("blocks[0].lines", with(List.of(text("a", Width.HALF, "A", "1", "2", "3", "4", "5")))),
                new Case("blocks[0].lines", with(List.of(new Block("a", Kind.TEXT, "A", Width.HALF, List.of(), List.of())))),
                new Case("blocks[0].stack", with(List.of(new Block("a", Kind.STACK, "A", Width.FULL, List.of(),
                        List.of("A", "B", "C", "D", "E", "F", "G", "H", "I"))))),
                new Case("blocks[0].lines[0]", with(List.of(text("a", Width.THIRD, "A", "WWWWWWWWWWWWWWWWWWWWWWWW"),
                        text("b", Width.THIRD, "B", "1"), text("c", Width.THIRD, "C", "1")))),
                new Case("blocks[0].title", with(List.of(text("a", Width.THIRD, "WWWWWWWWWWWWWWWWWW", "Text"),
                        text("b", Width.THIRD, "B", "1"), text("c", Width.THIRD, "C", "1")))),
                new Case("blocks[0].width", with(List.of(new Block("a", Kind.TEXT, "A", null,
                        List.of(new Line("x", Emphasis.STRONG)), List.of())))));
        for (Case item : invalid) {
            var error = assertThrows(ProfileCardSettings.Invalid.class, item.settings()::validated, item.field());
            assertEquals(item.field(), error.field);
        }
    }

    @Test
    void validationTrimsTextAndDropsFieldsThatDoNotBelongToTheKind() {
        var trimmed = with(List.of(new Block("a", Kind.TEXT, "  Title  ", Width.HALF,
                List.of(new Line("  Line  ", Emphasis.NORMAL)), List.of("ignored")))).validated();
        assertEquals("Title", trimmed.blocks().getFirst().title());
        assertEquals("Line", trimmed.blocks().getFirst().lines().getFirst().text());
        assertEquals(List.of(), trimmed.blocks().getFirst().stack());
    }
}
