package ftl.browser;
import org.teavm.jso.JSBody;
/**
 * Hyperspace capability scan over the merged {@code window.ftlFiles} (modded sessions only, see xyz.znix.xftl.hs.HsCapability).
 * One regex pass per {@code data/*.xml} file in the browser, so megabytes of mod XML cost no DOM parse.
 */
public final class BrowserHsScan {
    private BrowserHsScan() {}
    /**
     * Tab-separated lines {@code file kind name count}: kind {@code hs} (element only HS reads), {@code attr}
     * ({@code element@attribute} HS reads on a vanilla element that vanilla data never has), {@code other} (neither
     * vanilla nor HS: ignored by HS as well), {@code top} (a top-level element of data/hyperspace.xml).
     * Below an HS-only element (and anywhere in hyperspace.xml) names that neither list knows are data of that
     * feature (race names in a whiteList, ...) and are not reported on their own; nor are attributes there.
     * Arguments are newline-separated name lists from HsTags.
     */
    @JSBody(params={"hsOnly","vanilla","hsAttrs","vanillaPairs"},script=
        "var H=new Set(hsOnly.split('\\n')),V=new Set(vanilla.split('\\n')),A=new Set(hsAttrs.split('\\n')),P=new Set(vanillaPairs.split('\\n'));"
        +"var out=[],dec=new TextDecoder('utf-8'),names=Array.from(window.ftlFiles.keys()).sort();"
        +"var tagRe=/<(\\/?)([A-Za-z_][\\w.\\-:]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(\\/?)>/g,attrRe=/([A-Za-z_][\\w.\\-:]*)\\s*=/g;"
        +"for(var f=0;f<names.length;f++){var name=names[f];if(name.indexOf('data/')!==0||!/\\.xml$/i.test(name))continue;"
        +"var t=dec.decode(window.ftlFiles.get(name)).replace(/<!--[\\s\\S]*?-->/g,'').replace(/<!\\[CDATA\\[[\\s\\S]*?\\]\\]>/g,'');"
        +"var hsx=name==='data/hyperspace.xml',c=new Map(),stack=[],inHs=0,m;tagRe.lastIndex=0;"
        +"var add=function(k){c.set(k,(c.get(k)||0)+1);};"
        +"while((m=tagRe.exec(t))){"
        +"if(m[1]){if(stack.length&&stack.pop())inHs--;continue;}"
        +"var tag=m[2],self=m[4]==='/'||/\\/\\s*$/.test(m[3]),hs=false;"
        +"if(hsx&&stack.length===1){add('top\\t'+tag);hs=true;}"
        +"else if(H.has(tag)){add('hs\\t'+tag);hs=true;}"
        +"else if(!V.has(tag)){if(!inHs&&!hsx&&tag.indexOf('mod:')!==0&&tag.indexOf('mod-')!==0)add('other\\t'+tag);}"
        +"else if(m[3]&&!inHs){attrRe.lastIndex=0;var a;while((a=attrRe.exec(m[3]))){var an=a[1];if(A.has(an)&&!P.has(tag+'@'+an))add('attr\\t'+tag+'@'+an);}}"
        +"if(!self){stack.push(hs);if(hs)inHs++;}}"
        +"c.forEach(function(n,k){out.push(name+'\\t'+k+'\\t'+n);});}"
        +"return out.join('\\n');")
    public static native String scan(String hsOnly, String vanilla, String hsAttrs, String vanillaPairs);

    /**
     * True when a strict XML parser would reject {@code text} for what rapidxml accepts: several top-level elements,
     * text or CDATA outside the root element, or an XML declaration that is not at the very start. One regex pass,
     * no DOM, so a modded file can go straight to the wrapped parse: a failing DOMParser call makes Chrome build a
     * parsererror document with inline styles, which a page CSP without 'unsafe-inline' reports as console errors.
     */
    @JSBody(params="text",script=
        "var re=/<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?\\]\\]>|<\\?([\\w:.\\-]+)[\\s\\S]*?\\?>|<!(?:[^>\"']|\"[^\"]*\"|'[^']*')*>|<(\\/?)[A-Za-z_][\\w.\\-:]*(?:[^>\"']|\"[^\"]*\"|'[^']*')*?(\\/?)>/g;"
        +"var depth=0,roots=0,last=0,m,s;"
        +"while((m=re.exec(text))){"
        +"if(depth===0&&/\\S/.test(text.slice(last,m.index)))return true;"
        +"last=re.lastIndex;s=m[0];"
        +"if(s.charCodeAt(1)===33){if(depth===0&&s.charCodeAt(2)===91)return true;continue;}"
        +"if(m[1]!==undefined){if(m[1].toLowerCase()==='xml'&&m.index!==0)return true;continue;}"
        +"if(m[2]==='/'){depth--;continue;}"
        +"if(depth===0&&++roots>1)return true;"
        +"if(m[3]!=='/')depth++;}"
        +"return /\\S/.test(text.slice(last));")
    public static native boolean needsRoot(String text);
}
