package ftl.browser;

import ftl.android.audio.AudioMixer;

/** The engine's audio calls (see AL10), served by the Android mixer instead of Web Audio. */
public final class BrowserAudio {
    private BrowserAudio() {
    }

    public static int buffer(String path) {
        return AudioMixer.get().buffer(path);
    }

    public static double call(String op, int a, int b, double c) {
        return AudioMixer.get().call(op, a, b, c);
    }

    public static void music(int source, String path) {
        AudioMixer.get().music(source, path);
    }
}
