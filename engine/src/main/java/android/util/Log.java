package android.util;
public final class Log {
    public static int i(String tag,String text){System.out.println(tag+": "+text);return 0;}
    public static int w(String tag,String text){return i(tag,text);}
    public static int e(String tag,String text){System.err.println(tag+": "+text);return 0;}
    public static int d(String tag,String text){return i(tag,text);}
    public static int i(String tag,String text,Throwable t){t.printStackTrace();return i(tag,text);}
    public static int w(String tag,String text,Throwable t){return i(tag,text,t);}
    public static int e(String tag,String text,Throwable t){return i(tag,text,t);}
    public static int d(String tag,String text,Throwable t){return i(tag,text,t);}
}
