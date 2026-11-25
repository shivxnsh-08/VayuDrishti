package com.saanscheck.telegram;

import com.saanscheck.aqi.AqiCategory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram HTML (parse_mode=HTML) helpers: escaping, VayuDrishti visual identity, length limits and a validator.
 * Only the tags Telegram supports and we use are allowed: {@code <b> <i> <code> <a href="...">}.
 */
public final class TelegramHtml {

    /** Telegram's limit is 4096 characters per message; stay below it. */
    public static final int MAX_LENGTH = 4000;
    public static final String DIVIDER = "━━━━━━━━━━━━━━━━━━";
    public static final String TAGLINE = "<i>Stay informed. Breathe wisely.</i>";

    private static final Map<AqiCategory, String> DOT = Map.of(
            AqiCategory.GOOD, "🟢", AqiCategory.SATISFACTORY, "🟡", AqiCategory.MODERATE, "🟠",
            AqiCategory.POOR, "🔴", AqiCategory.VERY_POOR, "🟣", AqiCategory.SEVERE, "🟤");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-z]+)([^>]*)>");
    private static final Pattern HREF = Pattern.compile("^\\s+href=\"[^\"<>]*\"$");
    private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot|#\\d+);");

    private TelegramHtml() {
    }

    /** Escapes text for Telegram HTML (&amp; &lt; &gt; and quotes, which also makes it safe inside href). */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String bold(String text) {
        return "<b>" + esc(text) + "</b>";
    }

    public static String link(String url, String label) {
        return "<a href=\"" + esc(url) + "\">" + esc(label) + "</a>";
    }

    public static String dot(AqiCategory c) {
        return c == null ? "⚪" : DOT.get(c);
    }

    /** Escaped and shortened free text (e.g. advice from an external service) so a message always fits. */
    public static String clip(String text, int max) {
        String t = text == null ? "" : text.strip();
        if (t.length() > max) {
            int cut = t.lastIndexOf(' ', max);
            t = t.substring(0, cut > max / 2 ? cut : max) + "…";
        }
        return esc(t);
    }

    /** Last-resort guard: keep whole lines (tags never span lines in our messages) until the limit. */
    public static String fit(String html) {
        if (html.length() <= MAX_LENGTH) {
            return html;
        }
        StringBuilder out = new StringBuilder();
        for (String line : html.split("\n")) {
            if (out.length() + line.length() + 2 > MAX_LENGTH) {
                break;
            }
            out.append(line).append('\n');
        }
        return out.append('…').toString();
    }

    /** Plain-text fallback (used only if Telegram rejects the HTML): tags removed, entities decoded. */
    public static String toPlain(String html) {
        return html.replaceAll("<a href=\"([^\"]*)\">([^<]*)</a>", "$2 ($1)").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    /** True if the text uses only supported, properly nested tags and no raw '&lt;', '&gt;' or bare '&amp;'. */
    public static boolean isValid(String html) {
        if (html == null || html.length() > 4096) {
            return false;
        }
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(html);
        int last = 0;
        while (m.find()) {
            if (!textOk(html.substring(last, m.start()))) {
                return false;
            }
            String name = m.group(2);
            boolean closing = !m.group(1).isEmpty();
            String attrs = m.group(3);
            if (!name.equals("b") && !name.equals("i") && !name.equals("code") && !name.equals("a")) {
                return false;
            }
            if (closing) {
                if (!attrs.isEmpty() || open.isEmpty() || !open.pop().equals(name)) {
                    return false;
                }
            } else {
                if (name.equals("a") ? !HREF.matcher(attrs).matches() : !attrs.isEmpty()) {
                    return false;
                }
                open.push(name);
            }
            last = m.end();
        }
        return open.isEmpty() && textOk(html.substring(last));
    }

    private static boolean textOk(String text) {
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            return false;
        }
        int i = text.indexOf('&');
        while (i >= 0) {
            Matcher e = ENTITY.matcher(text);
            if (!e.find(i) || e.start() != i) {
                return false;
            }
            i = text.indexOf('&', i + 1);
        }
        return true;
    }
}
