package ftl.browser;
import java.util.zip.DataFormatException;
import org.teavm.jso.*;
import org.teavm.jso.typedarrays.Uint8Array;
/** PNG decoder's streaming zlib bridge; keep pixel/filter handling upstream. */
public final class BrowserInflater {
    private final JSObject state=create();
    @JSBody(script="var s={chunks:[],offset:0,failed:false,done:false};s.stream=new window.ftlUnzlib(function(bytes,last){if(bytes.length)s.chunks.push(bytes);s.done=last;});return s;")
    private static native JSObject create();
    @JSBody(params={"s","bytes"},script="try{s.stream.push(bytes,false);}catch(e){s.failed=true;}") private static native void push(JSObject s,Uint8Array b);
    /** Copies up to length inflated bytes into the Java array at offset; the count, or -1 after a failure. */
    @JSBody(params={"s","dst","offset","length"},script="if(s.failed)return -1;var copiedCount=0;while(copiedCount<length&&s.chunks.length){var chunkBytes=s.chunks[0],copyCount=Math.min(length-copiedCount,chunkBytes.length-s.offset);dst.set(chunkBytes.subarray(s.offset,s.offset+copyCount),offset+copiedCount);copiedCount+=copyCount;s.offset+=copyCount;if(s.offset===chunkBytes.length){s.chunks.shift();s.offset=0;}}return copiedCount;") private static native int take(JSObject s,@JSByRef byte[] dst,int offset,int length);
    /** A copy of the Java array's bytes, since the caller reuses the array while the stream keeps the input. */
    @JSBody(params={"src","offset","length"},script="return new Uint8Array(src.slice(offset,offset+length).buffer);") private static native Uint8Array copyOf(@JSByRef byte[] src,int offset,int length);
    @JSBody(params="s",script="return s.chunks.length===0;") private static native boolean empty(JSObject s);
    @JSBody(params="s",script="return s.done;") private static native boolean done(JSObject s);
    public void setInput(byte[] bytes,int offset,int length){push(state,copyOf(bytes,offset,length));}
    public int inflate(byte[] bytes,int offset,int length)throws DataFormatException{int copied=take(state,bytes,offset,length);if(copied<0)throw new DataFormatException("Invalid PNG zlib stream");return copied;}
    public boolean needsInput(){return empty(state);}public boolean finished(){return done(state);}public void end(){}
}
