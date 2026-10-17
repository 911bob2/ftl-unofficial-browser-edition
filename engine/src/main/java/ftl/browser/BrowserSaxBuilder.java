package ftl.browser;
import java.io.*;
import org.jdom2.*;
import org.jdom2.input.sax.SAXHandlerFactory;
import ftl.browser.BrowserXml;
/** Browser parser adapter; no SAX provider discovery, filesystem, or entities. */
public class BrowserSaxBuilder {
    private boolean expandEntities;
    public void setExpandEntities(boolean value) {expandEntities=value;}
    public boolean getExpandEntities() {return expandEntities;}
    public void setSAXHandlerFactory(SAXHandlerFactory factory) { }
    public Document build(InputStream in) throws JDOMException, IOException {return build(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));}
    public Document build(Reader in) throws JDOMException, IOException {StringBuilder s=new StringBuilder();char[] b=new char[4096];int n;while((n=in.read(b))!=-1)s.append(b,0,n);return BrowserXml.parse(s.toString());}
    public Document build(File file) throws JDOMException, IOException {throw new IOException("Browser filesystem unavailable");}
}
