package dev.joaoabreu.portfolioapi.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Block;

final class ProfileCardRenderer {
    static final int WIDTH = 900, GAP = 20, TOP = 150, HALF = 400, FULL = 820, PAD = 22, CHIP_GAP = 12;
    static final int LEFT = 40, RIGHT = 460, TEXT_BOX = HALF - 2 * PAD, CHIP_ROW = FULL - 2 * PAD, CHIP_PAD = 20;

    private record Palette(String bg, String border, String panel, String chip, String text, String muted, String foot,
            String stroke, String strong, String light, String monogramBg, List<String> chips) {}

    private ProfileCardRenderer() {}

    static ProfileCardCache.Card render(ProfileCardSettings settings, GitHubProfileSource.Data data, Instant fetchedAt) {
        Palette p = palette(settings);
        String count = data == null ? "Unavailable" : String.format(Locale.US, "%,d", data.contributions());
        String repo = data == null ? "Unavailable" : data.latestRepository() == null ? "No public repository" : data.latestRepository();
        String status = fetchedAt == null ? "GitHub data not loaded" : "GitHub snapshot: " + fetchedAt;

        var blocks = new StringBuilder();
        int left = TOP, right = TOP;
        for (Block block : settings.blocks()) {
            int x, y, width;
            if (block.full) {
                y = Math.max(left, right);
                x = LEFT;
                width = FULL;
                left = right = y + block.height + GAP;
            } else if (left <= right) {
                y = left;
                x = LEFT;
                width = HALF;
                left = y + block.height + GAP;
            } else {
                y = right;
                x = RIGHT;
                width = HALF;
                right = y + block.height + GAP;
            }
            blocks.append(rect(x, y, width, block.height, 12, p.panel(), p.border()));
            blocks.append(content(block, settings, p, x + PAD, y, count, repo));
        }
        int bottom = Math.max(left, right) - GAP;
        int height = bottom + 76;

        String title = settings.name() + " — " + settings.role();
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + WIDTH + "\" height=\"" + height
                + "\" viewBox=\"0 0 " + WIDTH + " " + height + "\" role=\"img\" aria-labelledby=\"title desc\">\n"
                + "  <title id=\"title\">" + xml(title) + "</title>\n"
                + "  <desc id=\"desc\">" + xml(settings.headline() + " Public GitHub snapshot.") + "</desc>\n"
                + "  " + rect(1, 1, WIDTH - 2, height - 2, 18, p.bg(), p.border())
                + "  <g font-family=\"Arial, sans-serif\">\n"
                + "    <circle cx=\"80\" cy=\"80\" r=\"39\" fill=\"" + p.monogramBg() + "\" stroke=\"" + p.stroke() + "\" stroke-width=\"2\"/>\n"
                + "    " + text(80, 89, 25, true, p.light(), "middle", settings.monogram())
                + "    " + text(142, 76, 32, true, p.text(), null, settings.name())
                + "    " + text(142, 104, 17, false, p.muted(), null, settings.headline())
                + blocks
                + "    " + text(40, bottom + 38, 11, false, p.foot(), null, status)
                + "    <text x=\"860\" y=\"" + (bottom + 38) + "\" text-anchor=\"end\" font-size=\"13\" fill=\"" + p.light() + "\">joaoabreu.dev</text>\n"
                + "  </g>\n</svg>\n";
        return new ProfileCardCache.Card(svg, etag(svg));
    }

    private static String content(Block block, ProfileCardSettings s, Palette p, int x, int y, String count, String repo) {
        return switch (block) {
            case CONTRIBUTIONS -> label(x, y + 34, p, "CONTRIBUTIONS · LAST YEAR") + "    " + text(x, y + 85, 36, true, p.strong(), null, count);
            case LATEST_REPOSITORY -> label(x, y + 34, p, "LATEST PUBLIC REPOSITORY")
                    + "    <text x=\"" + x + "\" y=\"" + (y + 72) + "\" font-family=\"monospace\" font-size=\"16\" font-weight=\"700\" fill=\""
                    + p.text() + "\">" + xml(shorten(repo)) + "</text>\n";
            case STACK -> label(x, y + 33, p, "TECH STACK") + chips(s.stack(), p, x, y + 55);
            case EDUCATION -> label(x, y + 33, p, "EDUCATION & PROJECTS")
                    + "    " + text(x, y + 69, 16, true, p.text(), null, s.educationTitle())
                    + "    " + text(x, y + 94, 14, false, p.muted(), null, s.educationDetail())
                    + "    " + text(x, y + 137, 16, true, p.text(), null, s.projectTitle())
                    + "    " + text(x, y + 163, 14, false, p.muted(), null, s.projectStack())
                    + "    " + text(x, y + 188, 14, false, p.muted(), null, s.projectNote());
            case FOCUS -> label(x, y + 33, p, "CURRENT FOCUS") + "    " + text(x, y + 69, 16, true, p.text(), null, s.focus());
            case OPPORTUNITY -> label(x, y + 33, p, "OPEN TO OPPORTUNITIES") + "    " + text(x, y + 69, 16, true, p.text(), null, s.opportunity());
        };
    }

    private static String chips(List<String> stack, Palette p, int x, int y) {
        int[] widths = chipWidths(stack);
        var out = new StringBuilder();
        int cursor = x;
        for (int i = 0; i < stack.size(); i++) {
            out.append("    ").append(rect(cursor, y, widths[i], 38, 8, p.chip(), null));
            out.append("    ").append(text(cursor + widths[i] / 2, y + 25, 14, true, p.chips().get(i % p.chips().size()), "middle", stack.get(i)));
            cursor += widths[i] + CHIP_GAP;
        }
        return out.toString();
    }

    static int[] chipWidths(List<String> stack) {
        double[] natural = new double[stack.size()];
        double sum = 0;
        for (int i = 0; i < natural.length; i++) sum += natural[i] = width(stack.get(i), 14, true) + 2 * CHIP_PAD;
        int available = CHIP_ROW - CHIP_GAP * (stack.size() - 1);
        int[] widths = new int[natural.length];
        int used = 0;
        for (int i = 0; i < natural.length; i++) {
            widths[i] = i == natural.length - 1 ? available - used : (int) Math.floor(natural[i] * available / sum);
            used += widths[i];
        }
        return widths;
    }

    static void checkFits(ProfileCardSettings s) {
        fits("name", s.name(), 32, true, WIDTH - 40 - 142);
        fits("headline", s.headline(), 17, false, WIDTH - 40 - 142);
        fits("monogram", s.monogram(), 25, true, 64);
        fits("educationTitle", s.educationTitle(), 16, true, TEXT_BOX);
        fits("educationDetail", s.educationDetail(), 14, false, TEXT_BOX);
        fits("projectTitle", s.projectTitle(), 16, true, TEXT_BOX);
        fits("projectStack", s.projectStack(), 14, false, TEXT_BOX);
        fits("projectNote", s.projectNote(), 14, false, TEXT_BOX);
        fits("focus", s.focus(), 16, true, TEXT_BOX);
        fits("opportunity", s.opportunity(), 16, true, TEXT_BOX);
        double natural = CHIP_GAP * (s.stack().size() - 1);
        for (String chip : s.stack()) natural += width(chip, 14, true) + 2 * CHIP_PAD;
        if (natural > CHIP_ROW) throw new ProfileCardSettings.Invalid("stack", "too wide for one row");
    }

    private static void fits(String field, String value, double size, boolean bold, double max) {
        if (width(value, size, bold) > max) throw new ProfileCardSettings.Invalid(field, "too wide for the card");
    }

    static double width(String value, double size, boolean bold) {
        double em = value.codePoints().mapToDouble(ProfileCardRenderer::advance).sum();
        return em * size * (bold ? 1.08 : 1);
    }

    private static double advance(int c) {
        if (c == ' ' || "il.,:;!|'`·".indexOf(c) >= 0) return 0.3;
        if ("fjtrI()[]-/".indexOf(c) >= 0) return 0.38;
        if ("mwMW@".indexOf(c) >= 0) return 0.92;
        if (Character.isUpperCase(c) || c == '&' || c == '%') return 0.74;
        if (c < 128) return 0.58;
        if (Character.isLetter(c) && Character.isLowerCase(c)) return 0.6;
        return 1.0;
    }

    private static Palette palette(ProfileCardSettings s) {
        boolean dark = s.theme() == ProfileCardSettings.Theme.DARK;
        List<String> accent = switch (s.accent()) {
            case BLUE -> dark ? List.of("#3b82f6", "#60a5fa", "#93c5fd", "#111a2b") : List.of("#3b82f6", "#2563eb", "#1d4ed8", "#dbeafe");
            case GREEN -> dark ? List.of("#22c55e", "#4ade80", "#86efac", "#0f2418") : List.of("#22c55e", "#16a34a", "#15803d", "#dcfce7");
            case VIOLET -> dark ? List.of("#8b5cf6", "#a78bfa", "#c4b5fd", "#1c1530") : List.of("#8b5cf6", "#7c3aed", "#6d28d9", "#ede9fe");
            case ORANGE -> dark ? List.of("#f97316", "#fb923c", "#fdba74", "#2a1708") : List.of("#f97316", "#ea580c", "#c2410c", "#ffedd5");
        };
        return dark
                ? new Palette("#0a0a0a", "#292929", "#171717", "#242424", "#fafafa", "#b0b0b0", "#a3a3a3",
                        accent.get(0), accent.get(1), accent.get(2), accent.get(3),
                        List.of(accent.get(2), "#a3d977", "#93c5fd", "#7dd3fc", "#f5a38e", "#c4b5fd"))
                : new Palette("#ffffff", "#e5e5e5", "#f5f5f5", "#e9e9e9", "#0a0a0a", "#525252", "#6b6b6b",
                        accent.get(0), accent.get(1), accent.get(2), accent.get(3),
                        List.of(accent.get(2), "#4d7c0f", "#1d4ed8", "#0369a1", "#c2410c", "#6d28d9"));
    }

    private static String label(int x, int y, Palette p, String value) {
        return "    <text x=\"" + x + "\" y=\"" + y + "\" font-size=\"12\" font-weight=\"700\" letter-spacing=\"1\" fill=\""
                + p.muted() + "\">" + xml(value) + "</text>\n";
    }

    private static String text(int x, int y, int size, boolean bold, String fill, String anchor, String value) {
        return "<text x=\"" + x + "\" y=\"" + y + "\"" + (anchor == null ? "" : " text-anchor=\"" + anchor + "\"")
                + " font-size=\"" + size + "\"" + (bold ? " font-weight=\"700\"" : "") + " fill=\"" + fill + "\">"
                + xml(value) + "</text>\n";
    }

    private static String rect(int x, int y, int width, int height, int radius, String fill, String stroke) {
        return "<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + width + "\" height=\"" + height + "\" rx=\"" + radius
                + "\" fill=\"" + fill + "\"" + (stroke == null ? "" : " stroke=\"" + stroke + "\"") + "/>\n";
    }

    private static String shorten(String value) { return value.length() <= 32 ? value : value.substring(0, 29) + "…"; }

    static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String etag(String svg) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(svg.getBytes(StandardCharsets.UTF_8))) + "\"";
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
