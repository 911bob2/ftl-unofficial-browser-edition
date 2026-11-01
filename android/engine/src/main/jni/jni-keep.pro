# libhslua.so binds LuaJs's natives and calls its host callbacks by name (JNI): R8 must keep them unrenamed.
-keep class xyz.znix.xftl.hs.lua.LuaJs {
    native <methods>;
    static *** host*(...);
}
# libftlmanlua.so does the same with FtlmanLua.
-keep class ftl.android.app.mods.FtlmanLua {
    native <methods>;
    static *** host*(...);
}
