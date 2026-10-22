package ftl.browser;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
/** Reads back drawn pixels, for the checks that compare what the engine draws with the picture it should be. */
public final class BrowserPixels {
    private BrowserPixels() {}
    @JSBody(params={"x","y","w","h","out"},script="var gl=window.ftlGL.gl,rows=new Uint8Array(w*h*4);gl.readPixels(x,gl.drawingBufferHeight-y-h,w,h,gl.RGBA,gl.UNSIGNED_BYTE,rows);for(var r=0;r<h;r++)out.set(new Int8Array(rows.buffer,(h-1-r)*w*4,w*4),r*w*4);")
    private static native void readInto(int x,int y,int w,int h,@JSByRef byte[] out);
    /** The RGBA pixels of a rectangle in game coordinates (top-left origin), rows top to bottom. */
    public static byte[] read(int x,int y,int w,int h){byte[] out=new byte[w*h*4];readInto(x,y,w,h,out);return out;}
}
