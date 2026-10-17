package org.newdawn.slick.opengl;
import java.io.*;
import java.nio.*;
public final class BrowserImageData implements LoadableImageData {
    private int width,height,tw,th;private ByteBuffer pixels;
    public int getDepth(){return 32;} public int getWidth(){return width;} public int getHeight(){return height;}
    public int getTexWidth(){return tw;} public int getTexHeight(){return th;}
    public ByteBuffer getImageBufferData(){return pixels.duplicate();}
    public void configureEdging(boolean value){}
    public ByteBuffer loadImage(InputStream in)throws IOException{return loadImage(in,false,null);}
    public ByteBuffer loadImage(InputStream in,boolean flip,int[] transparent)throws IOException{return loadImage(in,flip,true,transparent);}
    public ByteBuffer loadImage(InputStream in,boolean flip,boolean alpha,int[] transparent)throws IOException{
        PNGDecoder d=new PNGDecoder(in);width=d.getWidth();height=d.getHeight();tw=power(width);th=power(height);
        pixels=ByteBuffer.allocate(tw*th*4);d.decode(pixels,tw*4,PNGDecoder.RGBA);pixels.clear();return pixels;
    }
    private static int power(int n){int r=2;while(r<n)r*=2;return r;}
}
