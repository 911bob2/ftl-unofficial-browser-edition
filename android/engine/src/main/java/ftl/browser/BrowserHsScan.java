package ftl.browser;

import ftl.android.GameFiles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hyperspace capability scan over the merged game files (modded sessions only, see xyz.znix.xftl.hs.HsCapability).
 * The same regex pass per data/*.xml file as the browser build, so both report identical lines.
 */
public final class BrowserHsScan {
    private BrowserHsScan() {}

    private static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z_][\\w.\\-:]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>");
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_][\\w.\\-:]*)\\s*=");
    private static final Pattern ROOTS = Pattern.compile("<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?\\]\\]>|<\\?([\\w:.\\-]+)[\\s\\S]*?\\?>"
        + "|<!(?:[^>\"']|\"[^\"]*\"|'[^']*')*>|<(/?)[A-Za-z_][\\w.\\-:]*(?:[^>\"']|\"[^\"]*\"|'[^']*')*?(/?)>");

    /** JavaScript's \s, which also covers Unicode spaces. */
    static boolean jsSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || c == '\u00a0' || c == '\u1680' || (c >= '\u2000' && c <= '\u200a')
            || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff';
    }

    private static boolean hasNonSpace(String s, int from, int to) {
        for (int i = from; i < to; i++) if (!jsSpace(s.charAt(i))) return true;
        return false;
    }

    private static boolean endsWithSlash(String s) {
        int i = s.length() - 1;
        while (i >= 0 && jsSpace(s.charAt(i))) i--;
        return i >= 0 && s.charAt(i) == '/';
    }

    private static Set<String> lines(String s) {
        return new HashSet<>(Arrays.asList(s.split("\n", -1)));
    }

    /**
     * Tab-separated lines {@code file kind name count}: kind {@code hs} (element only HS reads), {@code attr}
     * ({@code element@attribute} HS reads on a vanilla element that vanilla data never has), {@code other} (neither
     * vanilla nor HS: ignored by HS as well), {@code top} (a top-level element of data/hyperspace.xml).
     */
    public static String scan(String hsOnly, String vanilla, String hsAttrs, String vanillaPairs) {
        Set<String> h = lines(hsOnly), v = lines(vanilla), a = lines(hsAttrs), pairs = lines(vanillaPairs);
        List<String> names = new ArrayList<>(GameFiles.current().names());
        Collections.sort(names);
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (!name.startsWith("data/") || !name.toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
            String t = new String(GameFiles.current().read(name), StandardCharsets.UTF_8);
            if (!t.isEmpty() && t.charAt(0) == '\ufeff') t = t.substring(1);
            t = CDATA.matcher(COMMENT.matcher(t).replaceAll("")).replaceAll("");
            boolean hsx = name.equals("data/hyperspace.xml");
            Map<String, Integer> c = new LinkedHashMap<>();
            ArrayDeque<Boolean> stack = new ArrayDeque<>();
            int inHs = 0;
            Matcher m = TAG.matcher(t);
            while (m.find()) {
                if (!m.group(1).isEmpty()) {
                    if (!stack.isEmpty() && stack.pop()) inHs--;
                    continue;
                }
                String tag = m.group(2), rest = m.group(3);
                boolean self = m.group(4).equals("/") || endsWithSlash(rest), hs = false;
                if (hsx && stack.size() == 1) {
                    c.merge("top\t" + tag, 1, Integer::sum);
                    hs = true;
                } else if (h.contains(tag)) {
                    c.merge("hs\t" + tag, 1, Integer::sum);
                    hs = true;
                } else if (!v.contains(tag)) {
                    if (inHs == 0 && !hsx && !tag.startsWith("mod:") && !tag.startsWith("mod-")) c.merge("other\t" + tag, 1, Integer::sum);
                } else if (!rest.isEmpty() && inHs == 0) {
                    Matcher at = ATTR.matcher(rest);
                    while (at.find()) {
                        String an = at.group(1);
                        if (a.contains(an) && !pairs.contains(tag + "@" + an)) c.merge("attr\t" + tag + "@" + an, 1, Integer::sum);
                    }
                }
                if (!self) {
                    stack.push(hs);
                    if (hs) inHs++;
                }
            }
            for (Map.Entry<String, Integer> e : c.entrySet()) out.add(name + "\t" + e.getKey() + "\t" + e.getValue());
        }
        return String.join("\n", out);
    }

    /**
     * True when a strict XML parser would reject {@code text} for what rapidxml accepts: several top-level elements,
     * text or CDATA outside the root element, or an XML declaration that is not at the very start.
     */
    public static boolean needsRoot(String text) {
        Matcher m = ROOTS.matcher(text);
        int depth = 0, roots = 0, last = 0;
        while (m.find()) {
            if (depth == 0 && hasNonSpace(text, last, m.start())) return true;
            last = m.end();
            String s = m.group();
            if (s.length() > 1 && s.charAt(1) == '!') {
                if (depth == 0 && s.length() > 2 && s.charAt(2) == '[') return true;
                continue;
            }
            if (m.group(1) != null) {
                if (m.group(1).toLowerCase(Locale.ROOT).equals("xml") && m.start() != 0) return true;
                continue;
            }
            if ("/".equals(m.group(2))) {
                depth--;
                continue;
            }
            if (depth == 0 && ++roots > 1) return true;
            if (!"/".equals(m.group(3))) depth++;
        }
        return hasNonSpace(text, last, text.length());
    }
}
