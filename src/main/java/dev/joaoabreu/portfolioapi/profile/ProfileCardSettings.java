package dev.joaoabreu.portfolioapi.profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ProfileCardSettings(Theme theme, Accent accent, String name, String role, String monogram,
        String headline, List<Block> blocks) {

    public enum Theme { DARK, LIGHT }
    public enum Accent { BLUE, GREEN, VIOLET, ORANGE }
    public enum Kind { CONTRIBUTIONS, LATEST_REPOSITORY, STACK, TEXT }
    public enum Emphasis { STRONG, NORMAL }
    public enum Width {
        THIRD(2), HALF(3), FULL(6);

        final int span;
        Width(int span) { this.span = span; }
    }

    public record Line(String text, Emphasis emphasis) {}

    public record Block(String id, Kind kind, String title, Width width, List<Line> lines, List<String> stack) {}

    static final int MAX_BLOCKS = 9, MAX_LINES = 4, MAX_STACK = 8;

    public static final ProfileCardSettings DEFAULTS = Legacy.DEFAULTS.convert();

    public ProfileCardSettings validated() {
        if (theme == null) throw new Invalid("theme", "required");
        if (accent == null) throw new Invalid("accent", "required");
        if (blocks == null || blocks.isEmpty() || blocks.size() > MAX_BLOCKS)
            throw new Invalid("blocks", "one to nine blocks");
        Set<String> ids = new HashSet<>();
        List<Block> checked = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            String field = "blocks[" + i + "]";
            if (block == null) throw new Invalid(field, "required");
            if (block.id() == null || !block.id().matches("[a-z0-9-]{1,24}") || !ids.add(block.id()))
                throw new Invalid(field + ".id", "unique short identifier");
            if (block.kind() == null) throw new Invalid(field + ".kind", "required");
            if (block.width() == null) throw new Invalid(field + ".width", "required");
            String title = text(field + ".title", block.title(), 40);
            List<Line> lines = List.of();
            List<String> stack = List.of();
            if (block.kind() == Kind.TEXT) {
                if (block.lines() == null || block.lines().isEmpty() || block.lines().size() > MAX_LINES)
                    throw new Invalid(field + ".lines", "one to four lines");
                List<Line> clean = new ArrayList<>();
                for (int j = 0; j < block.lines().size(); j++) {
                    Line line = block.lines().get(j);
                    String lineField = field + ".lines[" + j + "]";
                    if (line == null || line.emphasis() == null) throw new Invalid(lineField, "required");
                    clean.add(new Line(text(lineField, line.text(), 60), line.emphasis()));
                }
                lines = List.copyOf(clean);
            }
            if (block.kind() == Kind.STACK) {
                if (block.stack() == null || block.stack().isEmpty() || block.stack().size() > MAX_STACK)
                    throw new Invalid(field + ".stack", "one to eight technologies");
                List<String> chips = new ArrayList<>();
                for (String chip : block.stack()) chips.add(text(field + ".stack", chip, 16));
                stack = List.copyOf(chips);
            }
            checked.add(new Block(block.id(), block.kind(), title, block.width(), lines, stack));
        }
        var result = new ProfileCardSettings(theme, accent, text("name", name, 30), text("role", role, 40),
                monogram("monogram", monogram), text("headline", headline, 70), List.copyOf(checked));
        ProfileCardRenderer.checkFits(result);
        return result;
    }

    private static String text(String field, String value, int max) {
        if (value == null) throw new Invalid(field, "required");
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max)
            throw new Invalid(field, "1 to " + max + " characters");
        if (trimmed.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.SURROGATE
                || Character.getType(c) == Character.FORMAT))
            throw new Invalid(field, "unsupported character");
        return trimmed;
    }

    private static String monogram(String field, String value) {
        String trimmed = text(field, value, 3);
        if (!trimmed.codePoints().allMatch(Character::isLetterOrDigit)) throw new Invalid(field, "letters or digits only");
        return trimmed;
    }

    public static class Invalid extends RuntimeException {
        final String field;
        Invalid(String field, String reason) { super(reason); this.field = field; }
    }

    record Legacy(Theme theme, Accent accent, String name, String role, String monogram, String headline,
            List<String> blocks, List<String> stack, String educationTitle, String educationDetail,
            String projectTitle, String projectStack, String projectNote, String focus, String opportunity) {

        static final Legacy DEFAULTS = new Legacy(Theme.DARK, Accent.BLUE, "João Abreu", "Back-end Developer", "JA",
                "Building back-end systems with Java & Spring Boot.",
                List.of("CONTRIBUTIONS", "LATEST_REPOSITORY", "STACK", "EDUCATION", "FOCUS", "OPPORTUNITY"),
                List.of("Java", "Spring Boot", "PostgreSQL", "Docker", "Git"),
                "Computer Science · UVA", "Rio de Janeiro, Brazil", "Tutoring Management System",
                "Java · Spring Boot · PostgreSQL", "Personal project", "inHub", "Java back-end internship");

        ProfileCardSettings convert() {
            List<Block> converted = new ArrayList<>();
            for (String block : blocks == null ? List.<String>of() : blocks) {
                switch (block) {
                    case "CONTRIBUTIONS" -> converted.add(new Block("contributions", Kind.CONTRIBUTIONS,
                            "Contributions · last year", Width.HALF, List.of(), List.of()));
                    case "LATEST_REPOSITORY" -> converted.add(new Block("latest-repository", Kind.LATEST_REPOSITORY,
                            "Latest public repository", Width.HALF, List.of(), List.of()));
                    case "STACK" -> converted.add(new Block("stack", Kind.STACK, "Tech stack", Width.FULL, List.of(),
                            stack == null ? List.of() : stack));
                    case "EDUCATION" -> {
                        converted.add(new Block("education", Kind.TEXT, "Education", Width.HALF,
                                List.of(new Line(educationTitle, Emphasis.STRONG), new Line(educationDetail, Emphasis.NORMAL)), List.of()));
                        converted.add(new Block("project", Kind.TEXT, "Projects", Width.HALF,
                                List.of(new Line(projectTitle, Emphasis.STRONG), new Line(projectStack, Emphasis.NORMAL),
                                        new Line(projectNote, Emphasis.NORMAL)), List.of()));
                    }
                    case "FOCUS" -> converted.add(new Block("focus", Kind.TEXT, "Current focus", Width.HALF,
                            List.of(new Line(focus, Emphasis.STRONG)), List.of()));
                    case "OPPORTUNITY" -> converted.add(new Block("opportunity", Kind.TEXT, "Open to opportunities",
                            Width.HALF, List.of(new Line(opportunity, Emphasis.STRONG)), List.of()));
                    default -> { }
                }
            }
            return new ProfileCardSettings(theme, accent, name, role, monogram, headline, List.copyOf(converted));
        }
    }
}
