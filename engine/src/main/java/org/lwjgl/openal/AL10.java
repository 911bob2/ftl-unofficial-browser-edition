package org.lwjgl.openal;
import java.nio.*;
import ftl.browser.BrowserAudio;
public class AL10 {
    public static final int AL_NO_ERROR = 0;
    public static final int AL_INVALID_NAME = 0xA001;
    public static final int AL_INVALID_ENUM = 0xA002;
    public static final int AL_INVALID_VALUE = 0xA003;
    public static final int AL_INVALID_OPERATION = 0xA004;
    public static final int AL_OUT_OF_MEMORY = 0xA005;
    public static final int AL_NONE = 0;
    public static final int AL_FALSE = 0;
    public static final int AL_TRUE = 1;
    public static final int AL_SOURCE_RELATIVE = 0x202;
    public static final int AL_CONE_INNER_ANGLE = 0x1001;
    public static final int AL_CONE_OUTER_ANGLE = 0x1002;
    public static final int AL_PITCH = 0x1003;
    public static final int AL_POSITION = 0x1004;
    public static final int AL_DIRECTION = 0x1005;
    public static final int AL_VELOCITY = 0x1006;
    public static final int AL_LOOPING = 0x1007;
    public static final int AL_BUFFER = 0x1009;
    public static final int AL_GAIN = 0x100A;
    public static final int AL_MIN_GAIN = 0x100D;
    public static final int AL_MAX_GAIN = 0x100E;
    public static final int AL_ORIENTATION = 0x100F;
    public static final int AL_SOURCE_STATE = 0x1010;
    public static final int AL_INITIAL = 0x1011;
    public static final int AL_PLAYING = 0x1012;
    public static final int AL_PAUSED = 0x1013;
    public static final int AL_STOPPED = 0x1014;
    public static final int AL_BUFFERS_QUEUED = 0x1015;
    public static final int AL_BUFFERS_PROCESSED = 0x1016;
    public static final int AL_SEC_OFFSET = 0x1024;
    public static final int AL_SIZE = 0x2004;
    public static final int AL_FREQUENCY = 0x2001;
    public static final int AL_BITS = 0x2002;
    public static final int AL_CHANNELS = 0x2003;
    public static final int AL_MONO8 = 0x1100;
    public static final int AL_MONO16 = 0x1101;
    public static final int AL_STEREO8 = 0x1102;
    public static final int AL_STEREO16 = 0x1103;
    public static final int AL_FORMAT_MONO8 = AL_MONO8;
    public static final int AL_FORMAT_MONO16 = AL_MONO16;
    public static final int AL_FORMAT_STEREO8 = AL_STEREO8;
    public static final int AL_FORMAT_STEREO16 = AL_STEREO16;
    public static int alGetError(){return 0;}
    public static int alGenBuffers(){return (int)BrowserAudio.call("buffer",0,0,0);}
    public static void alGenBuffers(IntBuffer b){for(int i=b.position();i<b.limit();i++)b.put(i,alGenBuffers());}
    public static void alDeleteBuffers(int b){BrowserAudio.call("deleteBuffer",b,0,0);}
    public static void alDeleteBuffers(IntBuffer b){for(int i=b.position();i<b.limit();i++)alDeleteBuffers(b.get(i));}
    public static void alBufferData(int b,int f,ByteBuffer d,int rate){throw new UnsupportedOperationException("Audio is decoded with Web Audio");}
    public static int alGetBufferi(int b,int p){return (int)BrowserAudio.call("getBuffer",b,p,0);}
    public static int alGenSources(){return (int)BrowserAudio.call("source",0,0,0);}
    public static void alGenSources(IntBuffer b){for(int i=b.position();i<b.limit();i++)b.put(i,alGenSources());}
    public static void alDeleteSources(int s){BrowserAudio.call("deleteSource",s,0,0);}
    public static void alSourcei(int s,int p,int v){BrowserAudio.call("sourcei",s,p,v);}
    public static void alSourcef(int s,int p,float v){BrowserAudio.call("sourcef",s,p,v);}
    public static void alSource3f(int s,int p,float a,float b,float c){}
    public static void alSourcePlay(int s){BrowserAudio.call("play",s,0,0);}
    public static void alSourcePause(int s){BrowserAudio.call("pause",s,0,0);}
    public static void alSourceStop(int s){BrowserAudio.call("stop",s,0,0);}
    public static int alGetSourcei(int s,int p){return (int)BrowserAudio.call("getSource",s,p,0);}
    public static float alGetSourcef(int s,int p){return (float)BrowserAudio.call("getSource",s,p,0);}
    public static void alSourceQueueBuffers(int s,IntBuffer b){throw new UnsupportedOperationException("Browser streams use AudioBufferSourceNode");}
    public static int alSourceUnqueueBuffers(int s,IntBuffer b){return 0;}
    public static void alListenerf(int p,float v){} public static void alListener3f(int p,float a,float b,float c){}
    public static void alListenerfv(int p,FloatBuffer b){} public static void alListeneri(int p,int v){}
    public static void alDistanceModel(int p){} public static void alDopplerFactor(float v){}

}
