package ftl.browser;

import android.util.Xml;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.regex.Pattern;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * XML into JDOM the way the browser build does it (DOMParser, then a walk that keeps elements, attributes other than
 * xmlns declarations and text/CDATA): Android's pull parser here, with DTDs and entity declarations refused.
 */
public final class BrowserXml {
    private static final Pattern DECLARATIONS = Pattern.compile("<!DOCTYPE|<!ENTITY", Pattern.CASE_INSENSITIVE);

    private BrowserXml() {
    }

    /** DOM's localName: a prefixed name without its prefix. */
    private static String local(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    public static Document parse(String text) {
        if (DECLARATIONS.matcher(text).find()) throw new IllegalArgumentException("XML declarations forbidden");
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(text));
            ArrayDeque<Element> stack = new ArrayDeque<>();
            Element root = null;
            for (int event = p.next(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event == XmlPullParser.START_TAG) {
                    Element e = new Element(local(p.getName()));
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String a = p.getAttributeName(i);
                        if (!a.startsWith("xmlns")) e.setAttribute(a, p.getAttributeValue(i));
                    }
                    if (stack.isEmpty()) root = e;
                    else stack.peek().addContent(e);
                    stack.push(e);
                } else if (event == XmlPullParser.END_TAG) {
                    stack.pop();
                } else if (event == XmlPullParser.TEXT && !stack.isEmpty()) {
                    stack.peek().addContent(p.getText());
                }
            }
            if (root == null) throw new IllegalArgumentException("XML document has no root element");
            return new Document(root);
        } catch (XmlPullParserException | java.io.IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public static String write(Document d) {
        return new XMLOutputter(Format.getRawFormat()).outputString(d);
    }
}
