# libhslua.so: the Hyperspace Lua runtime of the browser build (hs-lua/ with Lua 5.3.6; the same sources and defines
# as hs-lua/Dockerfile) and its JNI host. hslua_jni.c includes hs-lua/hslua.c, so the runtime is compiled there.
LOCAL_PATH := $(call my-dir)
HS_ROOT := ../../../../..
HS_LUA := $(HS_ROOT)/hs-lua
LUA_SRC := $(HS_ROOT)/third_party/lua-5.3.6/src

include $(CLEAR_VARS)
LOCAL_MODULE := hslua
LUA_CORE := lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject lopcodes lparser lstate lstring ltable ltm \
    lundump lzio
LUA_LIBS := lauxlib lbaselib lbitlib lmathlib lstrlib ltablib lutf8lib
LOCAL_SRC_FILES := $(foreach f,$(LUA_CORE) $(LUA_LIBS),$(LUA_SRC)/$(f).c) $(HS_LUA)/hslua-lvm.c $(HS_LUA)/linit.c $(HS_LUA)/gen/hs_api.c \
    hslua_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(LUA_SRC) $(LOCAL_PATH)/$(HS_LUA)
# As the wasm build: gnu99, LUA_COMPAT_5_2, hsconf.h forced in. Native additions: LUA_USE_POSIX only switches Lua's
# error jumps to _setjmp/_longjmp (bionic's setjmp saves the signal mask with a syscall on every pcall; what else it
# selects is io/os code, not compiled, and the stock math.random, which HS replaces), hidden symbols except the JNI
# entry points, stack clash probes (64-bit ABIs).
# hslua.c's wasm export/import attributes are ignored here; lundump.c's LUA_SIGNATURE + 1 is intended.
LOCAL_CFLAGS := -std=gnu99 -DLUA_COMPAT_5_2 -include $(LOCAL_PATH)/$(HS_LUA)/hsconf.h -DLUA_USE_POSIX -O2 -fvisibility=hidden \
    -Wno-unknown-attributes -Wno-string-plus-int
ifneq ($(filter arm64-v8a x86_64,$(TARGET_ARCH_ABI)),)
LOCAL_CFLAGS += -fstack-clash-protection
endif
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

# libftlmanlua.so: ftlman's Lua patch scripts for the mod merge (hs-lua/ftlman.c, hs-lua/ftlman-lvm.c, Lua 5.3.6; the
# sources and defines of hs-lua/Dockerfile's second RUN) and its JNI host. ftlman_jni.c includes ftlman.c
# and ftlman_lstate_jni.c lstate.c. Native additions as above; for output equal to the wasm
# build also ftlman_lstate_jni.c (hash seed), ftlman_conf.h (math.random), -fsigned-char and -ffp-contract=off (wasm's
# char and arithmetic on every ABI).
include $(CLEAR_VARS)
LOCAL_MODULE := ftlmanlua
FTLMAN_CORE := lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject lopcodes lparser lstring ltable ltm lundump lzio
FTLMAN_LIBS := lauxlib lbaselib lmathlib lstrlib ltablib
LOCAL_SRC_FILES := $(foreach f,$(FTLMAN_CORE) $(FTLMAN_LIBS),$(LUA_SRC)/$(f).c) ftlman_lstate_jni.c $(HS_LUA)/ftlman-lvm.c ftlman_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(LUA_SRC) $(LOCAL_PATH)/$(HS_LUA)
LOCAL_CFLAGS := -std=gnu99 -DLUA_COMPAT_MATHLIB -include $(LOCAL_PATH)/$(HS_LUA)/hsconf.h -include $(LOCAL_PATH)/ftlman_conf.h \
    -DLUA_USE_POSIX -O2 -fvisibility=hidden -fsigned-char -ffp-contract=off -Wno-unknown-attributes -Wno-string-plus-int
ifneq ($(filter arm64-v8a x86_64,$(TARGET_ARCH_ABI)),)
LOCAL_CFLAGS += -fstack-clash-protection
endif
include $(BUILD_SHARED_LIBRARY)
