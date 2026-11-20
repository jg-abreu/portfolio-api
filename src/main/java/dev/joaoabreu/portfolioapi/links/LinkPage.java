package dev.joaoabreu.portfolioapi.links;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public record LinkPage(List<Link> links) {

    public record Link(String title, String url, String icon) {}

    static final int MAX_LINKS = 20, MAX_TITLE = 40, MAX_URL = 300;
    private static final Pattern ICON = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    public static final LinkPage DEFAULTS = new LinkPage(List.of(
            new Link("Portfolio", "https://joaoabreu.dev/en/", "house"),
            new Link("Projects", "https://joaoabreu.dev/en/projects/", "folder"),
            new Link("Résumé", "https://joaoabreu.dev/en/resume/", "file-user"),
            new Link("Blog", "https://joaoabreu.dev/en/blog/", "newspaper"),
            new Link("Recommendations", "https://joaoabreu.dev/en/recommendations/", "bookmark"),
            new Link("LinkedIn", "https://www.linkedin.com/in/jo%C3%A3o-abreuuu/", "linkedin"),
            new Link("GitHub", "https://github.com/jg-abreu", "github"),
            new Link("Email", "mailto:jgabreudev@gmail.com", "mail")));

    public LinkPage validated() {
        if (links == null || links.isEmpty() || links.size() > MAX_LINKS)
            throw new Invalid("links", "one to twenty links");
        List<Link> checked = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            String field = "links[" + i + "]";
            if (link == null) throw new Invalid(field, "required");
            if (link.icon() == null || !ICON.matcher(link.icon()).matches())
                throw new Invalid(field + ".icon", "short icon name");
            checked.add(new Link(title(field + ".title", link.title()), url(field + ".url", link.url()), link.icon()));
        }
        return new LinkPage(List.copyOf(checked));
    }

    private static String title(String field, String value) {
        if (value == null) throw new Invalid(field, "required");
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_TITLE)
            throw new Invalid(field, "1 to " + MAX_TITLE + " characters");
        if (trimmed.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.SURROGATE
                || Character.getType(c) == Character.FORMAT))
            throw new Invalid(field, "unsupported character");
        return trimmed;
    }

    private static String url(String field, String value) {
        if (value == null) throw new Invalid(field, "required");
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL || !trimmed.chars().allMatch(c -> c > 32 && c < 127))
            throw new Invalid(field, "https or mailto address up to " + MAX_URL + " characters");
        if (trimmed.startsWith("mailto:")) {
            if (!EMAIL.matcher(trimmed.substring(7)).matches()) throw new Invalid(field, "invalid email address");
            return trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null
                    || !HOST.matcher(uri.getHost()).matches() || uri.getPort() != -1)
                throw new Invalid(field, "https or mailto address");
        } catch (URISyntaxException exception) {
            throw new Invalid(field, "https or mailto address");
        }
        return trimmed;
    }

    public static class Invalid extends RuntimeException {
        final String field;
        Invalid(String field, String reason) { super(reason); this.field = field; }
    }
}
