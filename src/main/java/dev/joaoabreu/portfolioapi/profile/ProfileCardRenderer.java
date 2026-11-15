package dev.joaoabreu.portfolioapi.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Block;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Emphasis;
import dev.joaoabreu.portfolioapi.profile.ProfileCardSettings.Line;

final class ProfileCardRenderer {
    static final int WIDTH = 900, GAP = 20, TOP = 150, FULL = 820, PAD = 22, CHIP_GAP = 12, CHIP_PAD = 20;
    static final int LEFT = 40, COLUMNS = 6, COLUMN = (FULL - (COLUMNS - 1) * GAP) / COLUMNS;
    static final int CHIP_HEIGHT = 38, CHIP_ROW_GAP = 10;

    private record Palette(String bg, String border, String panel, String chip, String text, String muted, String foot,
            String stroke, String strong, String light, String monogramBg, List<String> chips) {}

    record Placed(Block block, int x, int y, int width, int height, int span) {}

    private ProfileCardRenderer() {}

    static int span(int columns) { return columns * COLUMN + (columns - 1) * GAP; }

    static List<Placed> layout(List<Block> blocks) {
        List<Placed> placed = new ArrayList<>();
        List<Block> row = new ArrayList<>();
        List<Integer> spans = new ArrayList<>();
        int used = 0, y = TOP;
        for (Block block : blocks) {
            if (used + block.width().span > COLUMNS) {
                y = closeRow(row, spans, y, placed);
                used = 0;
            }
            row.add(block);
            spans.add(block.width().span);
            used += block.width().span;
        }
        closeRow(row, spans, y, placed);
        return placed;
    }

    private static int closeRow(List<Block> row, List<Integer> spans, int y, List<Placed> placed) {
        if (row.isEmpty()) return y;
        int used = spans.stream().mapToInt(Integer::intValue).sum();
        spans.set(spans.size() - 1, spans.getLast() + COLUMNS - used);
        int height = 0;
        for (int i = 0; i < row.size(); i++) height = Math.max(height, natural(row.get(i), span(spans.get(i))));
        int column = 0;
        for (int i = 0; i < row.size(); i++) {
            placed.add(new Placed(row.get(i), LEFT + column * (COLUMN + GAP), y, span(spans.get(i)), height, spans.get(i)));
            column += spans.get(i);
        }
        row.clear();
        spans.clear();
        return y + height + GAP;
    }

    static int natural(Block block, int width) {
        return switch (block.kind()) {
            case CONTRIBUTIONS, LATEST_REPOSITORY -> 120;
            case STACK -> {
                int rows = chipRows(block.stack(), width - 2 * PAD).size();
                yield 55 + rows * CHIP_HEIGHT + (rows - 1) * CHIP_ROW_GAP + 23;
            }
            case TEXT -> lineOffsets(block.lines()).getLast() + 30;
        };
    }

    static List<Integer> lineOffsets(List<Line> lines) {
        List<Integer> offsets = new ArrayList<>();
        int y = 69;
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) y += lines.get(i).emphasis() == Emphasis.STRONG ? 34 : 25;
            offsets.add(y);
        }
        return offsets;
    }

    static List<List<Integer>> chipRows(List<String> stack, int available) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        double used = 0;
        for (int i = 0; i < stack.size(); i++) {
            double width = chipWidth(stack.get(i));
            if (!row.isEmpty() && used + CHIP_GAP + width > available) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : CHIP_GAP) + width;
            row.add(i);
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    static double chipWidth(String chip) { return width(chip, 14, true) + 2 * CHIP_PAD; }

    static int[] chipWidths(List<String> stack, List<Integer> row, int available) {
        double sum = 0;
        for (int index : row) sum += chipWidth(stack.get(index));
        int free = available - CHIP_GAP * (row.size() - 1);
        int[] widths = new int[row.size()];
        int used = 0;
        for (int i = 0; i < row.size(); i++) {
            widths[i] = i == row.size() - 1 ? free - used : (int) Math.floor(chipWidth(stack.get(row.get(i))) * free / sum);
            used += widths[i];
        }
        return widths;
    }

    static ProfileCardCache.Card render(ProfileCardSettings settings, GitHubProfileSource.Data data, Instant fetchedAt) {
        Palette p = palette(settings);
        String count = data == null ? "Unavailable" : String.format(Locale.US, "%,d", data.contributions());
        String repo = data == null ? "Unavailable" : data.latestRepository() == null ? "No public repository" : data.latestRepository();
        String status = fetchedAt == null ? "GitHub data not loaded" : "GitHub snapshot: " + fetchedAt;

        var blocks = new StringBuilder();
        int bottom = TOP;
        for (Placed placed : layout(settings.blocks())) {
            blocks.append("    ").append(rect(placed.x(), placed.y(), placed.width(), placed.height(), 12, p.panel(), p.border()));
            blocks.append(content(placed, p, count, repo));
            bottom = Math.max(bottom, placed.y() + placed.height());
        }
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

    private static String content(Placed placed, Palette p, String count, String repo) {
        Block block = placed.block();
        int x = placed.x() + PAD, y = placed.y(), inner = placed.width() - 2 * PAD;
        String label = label(x, y + 34, p, block.title());
        return switch (block.kind()) {
            case CONTRIBUTIONS -> label + "    " + text(x, y + 85, 36, true, p.strong(), null, count);
            case LATEST_REPOSITORY -> label
                    + "    <text x=\"" + x + "\" y=\"" + (y + 72) + "\" font-family=\"monospace\" font-size=\"16\" font-weight=\"700\" fill=\""
                    + p.text() + "\">" + xml(shorten(repo, (int) (inner / (16 * 0.62)))) + "</text>\n";
            case STACK -> label + chips(block.stack(), p, x, y + 55, inner);
            case TEXT -> {
                var out = new StringBuilder(label);
                List<Integer> offsets = lineOffsets(block.lines());
                for (int i = 0; i < block.lines().size(); i++) {
                    Line line = block.lines().get(i);
                    boolean strong = line.emphasis() == Emphasis.STRONG;
                    out.append("    ").append(text(x, y + offsets.get(i), strong ? 16 : 14, strong,
                            strong ? p.text() : p.muted(), null, line.text()));
                }
                yield out.toString();
            }
        };
    }

    private static String chips(List<String> stack, Palette p, int x, int y, int available) {
        var out = new StringBuilder();
        int top = y;
        for (List<Integer> row : chipRows(stack, available)) {
            int[] widths = chipWidths(stack, row, available);
            int cursor = x;
            for (int i = 0; i < row.size(); i++) {
                int index = row.get(i);
                out.append("    ").append(rect(cursor, top, widths[i], CHIP_HEIGHT, 8, p.chip(), null));
                out.append("    ").append(text(cursor + widths[i] / 2, top + 25, 14, true,
                        p.chips().get(index % p.chips().size()), "middle", stack.get(index)));
                cursor += widths[i] + CHIP_GAP;
            }
            top += CHIP_HEIGHT + CHIP_ROW_GAP;
        }
        return out.toString();
    }

    static void checkFits(ProfileCardSettings s) {
        fits("name", s.name(), 32, true, WIDTH - 40 - 142);
        fits("headline", s.headline(), 17, false, WIDTH - 40 - 142);
        fits("monogram", s.monogram(), 25, true, 64);
        List<Placed> placed = layout(s.blocks());
        for (int i = 0; i < placed.size(); i++) {
            Block block = placed.get(i).block();
            int inner = placed.get(i).width() - 2 * PAD;
            String field = "blocks[" + i + "]";
            if (labelWidth(block.title()) > inner) throw new ProfileCardSettings.Invalid(field + ".title", "too wide for the card");
            for (int j = 0; j < block.lines().size(); j++) {
                Line line = block.lines().get(j);
                boolean strong = line.emphasis() == Emphasis.STRONG;
                fits(field + ".lines[" + j + "]", line.text(), strong ? 16 : 14, strong, inner);
            }
            for (String chip : block.stack())
                if (chipWidth(chip) > inner) throw new ProfileCardSettings.Invalid(field + ".stack", "too wide for the card");
        }
    }

    static double labelWidth(String title) {
        String upper = title.toUpperCase(Locale.ROOT);
        return width(upper, 12, true) + upper.codePointCount(0, upper.length());
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
                + p.muted() + "\">" + xml(value.toUpperCase(Locale.ROOT)) + "</text>\n";
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

    private static String shorten(String value, int max) {
        int limit = Math.max(8, Math.min(32, max));
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

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
