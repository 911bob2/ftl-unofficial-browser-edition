package ftl.browser;
import org.jdom2.*;
import org.jdom2.output.*;
import org.teavm.jso.*;
public final class BrowserXml {
    @JSBody(params="text",script="if(/<!DOCTYPE|<!ENTITY/i.test(text))throw Error('XML declarations forbidden');const d=new DOMParser().parseFromString(text,'application/xml');if(d.getElementsByTagName('parsererror').length)throw Error(d.documentElement.textContent);return d.documentElement;")
    private static native JSObject parseNode(String text);
    @JSBody(params="n",script="return n.localName;") private static native String name(JSObject n);
    @JSBody(params="n",script="return n.attributes.length;") private static native int attrCount(JSObject n);
    @JSBody(params={"n","i"},script="return n.attributes[i].name;") private static native String attrName(JSObject n,int i);
    @JSBody(params={"n","i"},script="return n.attributes[i].value;") private static native String attrValue(JSObject n,int i);
    @JSBody(params="n",script="return n.childNodes.length;") private static native int childCount(JSObject n);
    @JSBody(params={"n","i"},script="return n.childNodes[i];") private static native JSObject child(JSObject n,int i);
    @JSBody(params="n",script="return n.nodeType;") private static native int type(JSObject n);
    @JSBody(params="n",script="return n.nodeValue;") private static native String value(JSObject n);
    private static Element convert(JSObject n) {
        Element e=new Element(name(n));for(int i=0;i<attrCount(n);i++){String a=attrName(n,i);if(!a.startsWith("xmlns"))e.setAttribute(a,attrValue(n,i));}
        for(int i=0;i<childCount(n);i++){JSObject c=child(n,i);int t=type(c);if(t==1)e.addContent(convert(c));else if(t==3||t==4)e.addContent(value(c));}
        return e;
    }
    public static Document parse(String text) {return new Document(convert(parseNode(text)));}
    public static String write(Document d) {return new XMLOutputter(Format.getRawFormat()).outputString(d);}
}
