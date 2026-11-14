package dev.joaoabreu.portfolioapi.profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ProfileCardSettings(Theme theme, Accent accent, String name, String role, String monogram,
        String headline, List<Block> blocks, List<String> stack, String educationTitle, String educationDetail,
        String projectTitle, String projectStack, String projectNote, String focus, String opportunity) {

    public enum Theme { DARK, LIGHT }
    public enum Accent { BLUE, GREEN, VIOLET, ORANGE }
    public enum Block {
        CONTRIBUTIONS(false, 120), LATEST_REPOSITORY(false, 120), STACK(true, 116),
        EDUCATION(false, 218), FOCUS(false, 100), OPPORTUNITY(false, 98);

        final boolean full;
        final int height;
        Block(boolean full, int height) { this.full = full; this.height = height; }
    }

    public static final ProfileCardSettings DEFAULTS = new ProfileCardSettings(Theme.DARK, Accent.BLUE,
            "João Abreu", "Back-end Developer", "JA", "Building back-end systems with Java & Spring Boot.",
            List.of(Block.values()), List.of("Java", "Spring Boot", "PostgreSQL", "Docker", "Git"),
            "Computer Science · UVA", "Rio de Janeiro, Brazil", "Tutoring Management System",
            "Java · Spring Boot · PostgreSQL", "Personal project", "inHub", "Java back-end internship");

    static final int MAX_STACK = 6;

    public ProfileCardSettings validated() {
        if (theme == null) throw new Invalid("theme", "required");
        if (accent == null) throw new Invalid("accent", "required");
        if (blocks == null || blocks.isEmpty() || blocks.size() > Block.values().length
                || blocks.stream().anyMatch(Objects::isNull) || new HashSet<>(blocks).size() != blocks.size())
            throw new Invalid("blocks", "one to six distinct blocks");
        if (stack == null || stack.isEmpty() || stack.size() > MAX_STACK || stack.stream().anyMatch(Objects::isNull))
            throw new Invalid("stack", "one to six technologies");
        List<String> chips = new ArrayList<>();
        for (String chip : stack) chips.add(text("stack", chip, 16));
        var result = new ProfileCardSettings(theme, accent, text("name", name, 30), text("role", role, 40),
                monogram("monogram", monogram), text("headline", headline, 70), List.copyOf(blocks), List.copyOf(chips),
                text("educationTitle", educationTitle, 40), text("educationDetail", educationDetail, 40),
                text("projectTitle", projectTitle, 40), text("projectStack", projectStack, 40),
                text("projectNote", projectNote, 40), text("focus", focus, 40), text("opportunity", opportunity, 40));
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
}
