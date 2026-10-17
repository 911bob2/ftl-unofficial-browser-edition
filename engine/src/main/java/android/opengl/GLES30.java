package android.opengl;
import java.nio.*;
import org.teavm.jso.*;
import org.teavm.jso.typedarrays.*;
/** The subset of GLES used by the engine, implemented against real WebGL2. */
public final class GLES30 {
    @JSBody(params={"op","a","b","c","d","e","f"},script="return window.ftlGL.call(op,a,b,c,d,e,f);")
    private static native int call(String op,double a,double b,double c,double d,double e,double f);
    private static int call(String op,double a,double b){return call(op,a,b,0,0,0,0);}
    private static int call(String op,double a){return call(op,a,0);}
    @JSBody(params={"op","id","text"},script="return window.ftlGL.text(op,id,text);")
    private static native int text(String op,int id,String text);
    @JSBody(params={"op","id"},script="return window.ftlGL.log(op,id);")
    private static native String log(String op,int id);
    @JSBody(params={"target","usage","data"},script="window.ftlGL.gl.bufferData(target,data,usage);")
    private static native void buffer(int target,int usage,Uint8Array data);
    @JSBody(params={"target","level","internal","w","h","format","type","data"},script="window.ftlGL.gl.texImage2D(target,level,internal,w,h,0,format,type,data);")
    private static native void texture(int target,int level,int internal,int w,int h,int format,int type,Uint8Array data);
    @JSBody(params={"target","level","x","y","w","h","format","type","data"},script="window.ftlGL.gl.texSubImage2D(target,level,x,y,w,h,format,type,data);")
    private static native void textureRows(int target,int level,int x,int y,int w,int h,int format,int type,Uint8Array data);
    @JSBody(params={"id","transpose","data"},script="window.ftlGL.gl.uniformMatrix3fv(window.ftlGL.obj(id),transpose,data);")
    private static native void matrix(int id,boolean transpose,Float32Array data);
    /** A view of a heap array's bytes, with no copy: WebGL copies it during the call that takes it. */
    @JSBody(params={"a","off","len"},script="return new Uint8Array(a.buffer,a.byteOffset+off,len);")
    private static native Uint8Array view(@JSByRef byte[] a,int off,int len);
    private static Uint8Array bytes(Buffer buffer){if(buffer==null)return null;ByteBuffer heap=(ByteBuffer)buffer;if(heap.hasArray())return view(heap.array(),heap.arrayOffset()+heap.position(),heap.remaining());ByteBuffer b=heap.duplicate();Uint8Array a=Uint8Array.create(b.remaining());for(int i=0;i<a.getLength();i++)a.set(i,(short)(b.get()&255));return a;}
    public static void glEnable(int c){call("enable",c);} public static void glDisable(int c){call("disable",c);}
    public static void glClearColor(float r,float g,float b,float a){call("clearColor",r,g,b,a,0,0);}
    public static void glClear(int m){call("clear",m);} public static void glViewport(int x,int y,int w,int h){call("viewport",x,y,w,h,0,0);}
    public static void glBlendFunc(int s,int d){call("blendFunc",s,d);} public static int glGetError(){return call("getError",0);}
    public static void glGetIntegerv(int p,int[] a,int offset){a[offset]=call("getParameter",p);}
    private static void generate(String kind,int n,int[] a,int offset){for(int i=0;i<n;i++)a[offset+i]=call("create"+kind,0);}
    private static void delete(String kind,int n,int[] a,int offset){for(int i=0;i<n;i++)call("delete"+kind,a[offset+i]);}
    public static void glGenTextures(int n,int[] a,int o){generate("Texture",n,a,o);} public static void glDeleteTextures(int n,int[] a,int o){delete("Texture",n,a,o);}
    public static void glBindTexture(int t,int id){call("bindTexture",t,id);} public static void glTexParameteri(int t,int p,int v){call("texParameteri",t,p,v,0,0,0);}
    public static void glTexImage2D(int t,int l,int internal,int w,int h,int border,int f,int ty,Buffer d){texture(t,l,internal,w,h,f,ty,bytes(d));}
    public static void glTexSubImage2D(int t,int l,int x,int y,int w,int h,int f,int ty,Buffer d){textureRows(t,l,x,y,w,h,f,ty,bytes(d));}
    public static void glStencilFunc(int f,int r,int m){call("stencilFunc",f,r,m,0,0,0);} public static void glStencilOp(int a,int b,int c){call("stencilOp",a,b,c,0,0,0);}
    public static void glStencilMask(int m){call("stencilMask",m);} public static void glDrawArrays(int m,int f,int n){call("drawArrays",m,f,n,0,0,0);}
    public static int glCreateShader(int t){return call("createShader",t);} public static void glShaderSource(int s,String text){text("shaderSource",s,text);}
    public static void glCompileShader(int s){call("compileShader",s);} public static void glGetShaderiv(int s,int p,int[] a,int o){a[o]=call("getShaderParameter",s,p);}
    public static String glGetShaderInfoLog(int s){return log("getShaderInfoLog",s);} public static void glDeleteShader(int s){call("deleteShader",s);}
    public static int glCreateProgram(){return call("createProgram",0);} public static void glAttachShader(int p,int s){call("attachShader",p,s);}
    public static void glLinkProgram(int p){call("linkProgram",p);} public static void glGetProgramiv(int p,int n,int[] a,int o){a[o]=call("getProgramParameter",p,n);}
    public static String glGetProgramInfoLog(int p){return log("getProgramInfoLog",p);} public static void glDeleteProgram(int p){call("deleteProgram",p);}
    public static int glGetUniformLocation(int p,String n){return text("getUniformLocation",p,n);} public static int glGetAttribLocation(int p,String n){return text("getAttribLocation",p,n);}
    public static void glUseProgram(int p){call("useProgram",p);}
    public static void glUniformMatrix3fv(int loc,int count,boolean trans,FloatBuffer b){Float32Array a=Float32Array.create(b.remaining());for(int i=0;i<a.getLength();i++)a.set(i,b.get(b.position()+i));matrix(loc,trans,a);}
    public static void glUniform1i(int loc,int n){call("uniform1i",loc,n);}
    public static void glGenBuffers(int n,int[] a,int o){generate("Buffer",n,a,o);} public static void glDeleteBuffers(int n,int[] a,int o){delete("Buffer",n,a,o);}
    public static void glGenVertexArrays(int n,int[] a,int o){generate("VertexArray",n,a,o);} public static void glDeleteVertexArrays(int n,int[] a,int o){delete("VertexArray",n,a,o);}
    public static void glBindBuffer(int t,int id){call("bindBuffer",t,id);} public static void glBindVertexArray(int id){call("bindVertexArray",id);}
    public static void glBufferData(int t,int size,Buffer b,int usage){buffer(t,usage,bytes(b));}
    public static void glEnableVertexAttribArray(int i){call("enableVertexAttribArray",i);} public static void glDisableVertexAttribArray(int i){call("disableVertexAttribArray",i);}
    public static void glVertexAttribPointer(int i,int size,int type,boolean normal,int stride,int offset){call("vertexAttribPointer",i,size,type,normal?1:0,stride,offset);}
    public static void glVertexAttribPointer(int i,int size,int type,boolean normal,int stride,Buffer b){throw new UnsupportedOperationException("Client vertex arrays are not supported by WebGL2");}
    public static void glDrawElements(int mode,int count,int type,int offset){call("drawElements",mode,count,type,offset,0,0);}
}
