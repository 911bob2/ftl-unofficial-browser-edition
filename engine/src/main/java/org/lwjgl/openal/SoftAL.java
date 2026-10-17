package org.lwjgl.openal;
/** Compatibility entry points; audio output is owned by the browser. */
public final class SoftAL {
    private static final SoftAL INSTANCE=new SoftAL();
    public static SoftAL get(){return INSTANCE;}
    public static boolean isDumping(){return false;}
    public void start(){}
}
