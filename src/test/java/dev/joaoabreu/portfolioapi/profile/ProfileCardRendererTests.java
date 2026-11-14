package dev.joaoabreu.portfolioapi.profile;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Accent;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Block;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Theme;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;

class ProfileCardRendererTests {
    final GitHubProfileSource.Data data = new GitHubProfileSource.Data(1234, "synthetic-project");
    final Instant at = Instant.parse("2026-11-14T12:00:00Z");

    static List<String> panels(String svg) {
        var matcher = Pattern.compile("<rect x=\"(\\d+)\" y=\"(\\d+)\" width=\"(\\d+)\" height=\"(\\d+)\" rx=\"12\"").matcher(svg);
        List<String> found = new ArrayList<>();
        while (matcher.find()) found.add(matcher.group(1) + "," + matcher.group(2) + "," + matcher.group(3) + "," + matcher.group(4));
        return found;
    }

    ProfileCardSettings with(List<Block> blocks, Theme theme, Accent accent) {
        var d = ProfileCardSettings.DEFAULTS;
        return new ProfileCardSettings(theme, accent, d.name(), d.role(), d.monogram(), d.headline(), blocks, d.stack(),
                d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity());
    }

    @Test
    void defaultsReproduceTheApprovedBlockGeometry() {
        String svg = ProfileCardRenderer.render(ProfileCardSettings.DEFAULTS.validated(), data, at).svg();
        assertTrue(svg.contains("width=\"900\" height=\"720\""));
        assertEquals(List.of("40,150,400,120", "460,150,400,120", "40,290,820,116", "40,426,400,218",
                "460,426,400,100", "460,546,400,98"), panels(svg));
        assertTrue(svg.contains("y=\"682\""));
        assertTrue(svg.contains(">1,234<") && svg.contains(">synthetic-project<"));
        assertTrue(svg.contains("#0a0a0a") && svg.contains("#3b82f6"));
    }

    @Test
    void reorderedAndHiddenBlocksReflowWithoutGaps() {
        String svg = ProfileCardRenderer.render(with(List.of(Block.STACK, Block.FOCUS), Theme.DARK, Accent.BLUE), data, at).svg();
        assertEquals(List.of("40,150,820,116", "40,286,400,100"), panels(svg));
        assertTrue(svg.contains("height=\"462\""));
        assertFalse(svg.contains("CONTRIBUTIONS"));
        assertFalse(svg.contains("1,234"));
    }

    @Test
    void themeAndAccentComeFromAClosedPalette() {
        String light = ProfileCardRenderer.render(with(List.of(Block.values()), Theme.LIGHT, Accent.GREEN), data, at).svg();
        assertTrue(light.contains("fill=\"#ffffff\"") && light.contains("#16a34a"));
        assertFalse(light.contains("fill=\"#0a0a0a\" stroke"));
        String violet = ProfileCardRenderer.render(with(List.of(Block.values()), Theme.DARK, Accent.VIOLET), data, at).svg();
        assertTrue(violet.contains("#8b5cf6"));
    }

    @Test
    void editableTextIsEscapedAndTheCardStaysWellFormed() throws Exception {
        var d = ProfileCardSettings.DEFAULTS;
        var hostile = new ProfileCardSettings(d.theme(), d.accent(), "<a href=x>", d.role(), d.monogram(), "\"'&<script>",
                d.blocks(), List.of("<svg>", "C&C"), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(),
                d.projectNote(), "</text><script>", d.opportunity()).validated();
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
    void chipsFillTheRowExactly() {
        int[] widths = ProfileCardRenderer.chipWidths(ProfileCardSettings.DEFAULTS.stack());
        int total = ProfileCardRenderer.CHIP_GAP * (widths.length - 1);
        for (int width : widths) total += width;
        assertEquals(ProfileCardRenderer.CHIP_ROW, total);
        for (int width : widths) assertTrue(width > 60);
    }

    @Test
    void validationRejectsUnsafeOrOversizedInput() {
        var d = ProfileCardSettings.DEFAULTS;
        List<ProfileCardSettings> invalid = List.of(
                new ProfileCardSettings(null, d.accent(), d.name(), d.role(), d.monogram(), d.headline(), d.blocks(), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), " ", d.role(), d.monogram(), d.headline(), d.blocks(), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), "J A", d.headline(), d.blocks(), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), "line\nbreak", d.blocks(), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), d.headline(), List.of(), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), d.headline(), List.of(Block.STACK, Block.STACK), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), d.headline(), d.blocks(), List.of("A", "B", "C", "D", "E", "F", "G"), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), d.headline(), d.blocks(), List.of("WWWWWWWWWWWWWWWW", "MMMMMMMMMMMMMMMM", "WWWWWWWWWWWWWWWW"), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()),
                new ProfileCardSettings(d.theme(), d.accent(), d.name(), d.role(), d.monogram(), d.headline(), d.blocks(), d.stack(), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWW", d.opportunity()));
        for (ProfileCardSettings settings : invalid) assertThrows(ProfileCardSettings.Invalid.class, settings::validated);
    }

    @Test
    void validationTrimsText() {
        var d = ProfileCardSettings.DEFAULTS;
        var trimmed = new ProfileCardSettings(d.theme(), d.accent(), "  João Abreu  ", d.role(), d.monogram(), d.headline(), d.blocks(), List.of(" Java "), d.educationTitle(), d.educationDetail(), d.projectTitle(), d.projectStack(), d.projectNote(), d.focus(), d.opportunity()).validated();
        assertEquals("João Abreu", trimmed.name());
        assertEquals(List.of("Java"), trimmed.stack());
    }
}
