package ftl.android;

import java.util.Collections;
import java.util.List;

/**
 * The game files the engine reads: the player's ftl.dat (or the server's prepared assets), the engine's own resources
 * under engine/, and a mod session's merged files on top. Audio is kept apart, as in the browser build, where the
 * files map holds no .ogg/.wav and sounds are fetched by path when first played.
 */
public interface GameFiles {
    /** Every non-audio file name. */
    List<String> names();

    boolean contains(String name);

    /** The bytes of a non-audio file, or null when there is none. */
    byte[] read(String name);

    /** The bytes of an audio file (audio/...ogg or .wav), or null when there is none. */
    byte[] audio(String path);

    GameFiles EMPTY = new GameFiles() {
        public List<String> names() { return Collections.emptyList(); }
        public boolean contains(String name) { return false; }
        public byte[] read(String name) { return null; }
        public byte[] audio(String path) { return null; }
    };

    static GameFiles current() { return Holder.files; }

    static void install(GameFiles files) { Holder.files = files == null ? EMPTY : files; }

    final class Holder {
        private Holder() {}

        static volatile GameFiles files = EMPTY;
    }
}
