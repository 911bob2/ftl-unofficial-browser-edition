package xyz.znix.xftl.sys;
import java.io.*;
import com.jcraft.jorbis.VorbisFile;
import org.lwjgl.openal.AL10;
import ftl.browser.BrowserAudio;
public class OpenALStreamPlayer {
    private final int source;private float volume=1,masterVolume=1,fadeRate,fadeTime;
    public OpenALStreamPlayer(int source){this.source=source;}
    public void playPath(String path){BrowserAudio.music(source,path);}
    public void play(VorbisFile file)throws IOException{throw new IOException("Use browser music resources");}
    public void stop(){AL10.alSourceStop(source);}
    public void setup(float pitch,float volume,float master){this.volume=volume;masterVolume=master;AL10.alSourcef(source,AL10.AL_PITCH,pitch);apply();}
    public void setMasterVolume(float v){masterVolume=v;apply();}
    private void apply(){AL10.alSourcef(source,AL10.AL_GAIN,volume*masterVolume);}
    public void fadeVolume(float v,float duration){if(duration<=0){volume=v;fadeTime=0;apply();}else{fadeRate=(v-volume)/duration;fadeTime=duration;}}
    public boolean done(){return AL10.alGetSourcei(source,AL10.AL_SOURCE_STATE)==AL10.AL_STOPPED;}
    public void update(float dt){float step=Math.min(dt,fadeTime);volume+=fadeRate*step;fadeTime-=step;apply();if(fadeTime<=0&&volume<=0)stop();}
    public boolean setPosition(float p){AL10.alSourcef(source,AL10.AL_SEC_OFFSET,p);return true;}
    public float getPosition(){return AL10.alGetSourcef(source,AL10.AL_SEC_OFFSET);}
    public boolean isVolumeFading(){return fadeTime>0;}
    public void close()throws IOException{stop();}
}
