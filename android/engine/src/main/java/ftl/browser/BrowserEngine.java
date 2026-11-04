package ftl.browser;

import ftl.android.AndroidHost;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.newdawn.slick.InputListener;
import xyz.znix.xftl.game.MainGame;
import xyz.znix.xftl.rendering.Graphics;
import xyz.znix.xftl.rendering.ShaderProgramme;
import xyz.znix.xftl.sys.GameContainer;

/**
 * The engine driver on Android: the same start / step / render / input entry points as the browser build's
 * BrowserEngine (which the page calls through TeaVM exports), called here by the app's GL thread. Browser-only test
 * probes are not part of the Android build.
 */
public final class BrowserEngine {
    private BrowserEngine() {
    }

    public static void openOriginalGamePromotion() {
        AndroidHost.get().openUrl("https://store.steampowered.com/app/590380/Into_the_Breach/");
    }

    /** The native Fullscreen option: the app is always fullscreen; the host decides what a toggle means. */
    public static boolean isFullscreen() { return AndroidHost.get().isFullscreen(); }

    public static void toggleFullscreen() { AndroidHost.get().toggleFullscreen(); }

    /** A haptic tick of the touch HUD (TouchHaptics): count ticks of one kind, played by the app. */
    public static void haptic(int kind, int count) { AndroidHost.get().haptic(kind, count); }

    private static MainGame game;
    private static final Container container = new Container();
    private static Graphics graphics;

    public static void start(boolean ae, String run, String profile, String settings) throws Exception {
        BrowserDebug.tick = 0;
        if (game != null) game.shutdown();
        container.input.removeAllListeners();
        container.input.reset();
        BrowserFiles.enableAE = ae;
        BrowserFiles.hydrate(run, profile);
        BrowserFiles.hydrateSettings(settings);
        xyz.znix.xftl.hs.HS.init();
        graphics = new Graphics();
        graphics.markCurrentImageTransformSource();
        ShaderProgramme.getSHADER_SCREEN_SIZE().set(1280, 720);
        game = new MainGame(new MainGame.CommandLineArgs());
        game.init(container);
        if (!xyz.znix.xftl.hs.HS.scripts.isEmpty()) xyz.znix.xftl.hs.lua.HsLua.startForSession(game, graphics);
    }

    /** A frame advances at most half a speed unit, 1/32 s: a slower frame slows the game down, as in the original. */
    static final float MAX_STEP = 1f / 32f;

    public static void step(float dt) throws Exception {
        boolean hs = xyz.znix.xftl.hs.HS.luaActive && dt > 0;
        // the console's SPEED sets speedLevel in any run (CFPS::OnLoop)
        float step = hs || dt > 0 && xyz.znix.xftl.hs.lua.LuaFps.speedLevel != 0 ? xyz.znix.xftl.hs.lua.LuaFps.onLoop(dt) : Math.min(dt, MAX_STEP);
        if (hs) xyz.znix.xftl.hs.lua.LuaAdaptersWP7Ui.frame(step);
        game.update(container, step);
        if (hs) xyz.znix.xftl.hs.lua.LuaEvents.onTick();
        if (dt > 0) BrowserDebug.tick++;
        container.input.clearInputPressedRecord();
        xyz.znix.xftl.game.TouchRouter.INSTANCE.afterStep();
    }

    public static void render() throws Exception { game.render(container, graphics); }

    private static boolean touchCanvas() {
        if (!xyz.znix.xftl.game.TouchMode.enabled || game == null) return false;
        MainGame.GameState state = game.getCurrentState();
        return state instanceof xyz.znix.xftl.game.InGameState || state instanceof xyz.znix.xftl.game.MainMenuState
            || state instanceof xyz.znix.xftl.game.SettingsMenuState || state instanceof xyz.znix.xftl.hangar.SelectShipState
            || state instanceof xyz.znix.xftl.game.CreditsState;
    }

    /** The size of the frame the current screen draws: the touch flight canvas, else 1280×720. */
    public static int canvasWidth() { return touchCanvas() ? xyz.znix.xftl.game.TouchMode.canvasW : 1280; }

    public static int canvasHeight() { return touchCanvas() ? xyz.znix.xftl.game.TouchMode.HUD_H : 720; }

    static Graphics diagnosticGraphics() { return graphics; }

    static void diagnosticMouse(int x, int y, int type, int button) { mouse(x, y, type, button); }

    /** A finger in the current canvas's units: action 1 down, 2 move, 3 up, 4 cancel; time in milliseconds. */
    public static void touch(int action, int id, int x, int y, long time) {
        if (game != null) xyz.znix.xftl.game.TouchRouter.INSTANCE.touch(game, action, id, x, y, time, BrowserEngine::mouse);
    }

    public static void renderFrame(float dt) throws Exception {
        if (game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state) state.setRenderingDeltaTime(dt);
        else if (game.getCurrentState() instanceof xyz.znix.xftl.game.SettingsMenuState state) state.prepareBrowserRender(dt);
        render();
    }

    public static void pause() { if (game != null) game.autoPauseIfInFlight(); }

    public static String hsCapabilityReport() { return xyz.znix.xftl.hs.HS.capabilityReport(); }

    public static String hsProfile(boolean reset) { return xyz.znix.xftl.hs.lua.HsLua.profileJson(reset); }

    public static void focusChanged(boolean focused) { if (game != null) game.setBrowserFocus(focused); }

    public static void resetInput() {
        if (game != null && game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state) state.getShipUI().cancelDrag();
        container.input.reset();
    }

    public static void shutdown() {
        if (game != null) {
            game.shutdown();
            game = null;
        }
        container.input.removeAllListeners();
        container.input.reset();
    }

    public static boolean running() { return game != null; }

    public static boolean inFlight() { return game.isInFlight(); }

    /** The launcher's "Enable debug console" switch (docs/CONSOLE.md): the console opens on ` and \ in every mode. */
    public static void consoleConfig(boolean enabled) { xyz.znix.xftl.devutil.ConsoleGate.setting = enabled; }

    /** The >_ button: closes the debug console when it is open (as Back does), opens it otherwise. */
    public static void consoleToggle() {
        if (game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state) {
            if (state.isDebugConsoleVisible()) state.cancelMenu(); else state.openDebugConsole();
        }
    }

    public static void save() {
        if (game == null) return;
        if (game.isTutorialRunning()) {
            BrowserDebug.record("persistence", "save.ignored", "{\"kind\":\"run-profile\",\"reason\":\"tutorial\"}");
            return;
        }
        game.writeRunSave();
        BrowserFiles.setProfile(BrowserXml.write(game.getProfile().save()));
    }

    public static String runSave() { return BrowserFiles.getRun(); }

    /** What a save made now keeps: "run", "ended" (only the profile), "tutorial" (never saved) or "profile". */
    public static String saveKind() {
        if (game == null) return "profile";
        if (game.isTutorialRunning()) return "tutorial";
        if (game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state) return state.browserRunEnded ? "ended" : "run";
        return "profile";
    }

    /** Completes what a save cannot hold (a jump in progress) so the next save() is a consistent point. */
    public static boolean saveSettle() {
        return game != null && game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state
            && !game.isTutorialRunning() && state.browserSettleForSave();
    }

    public static String profileSave() { return BrowserFiles.getProfile(); }

    public static int frameRateLimit() { return game.getProfile().getFrameRateLimit(); }

    public static void newRun(String ship, int difficulty) {
        game.startNewGame(ship, xyz.znix.xftl.game.Difficulty.values()[difficulty], null);
    }

    public static void restore(String xml) { game.loadSavedGame(BrowserXml.parse(xml)); }

    public static MainGame getGame() { return game; }

    public static void debugConfig(boolean enabled) { BrowserDebug.enabled = enabled; }

    public static void menuCancel() {
        MainGame.GameState current = game.getCurrentState();
        if (current instanceof xyz.znix.xftl.game.InGameState state) state.cancelMenu();
        else {
            current.keyPressed(xyz.znix.xftl.sys.Input.KEY_ESCAPE, (char) 0);
            if (game.getCurrentState() == current) current.keyReleased(xyz.znix.xftl.sys.Input.KEY_ESCAPE, (char) 0);
        }
    }

    public static void diagnosticProgress(String phase) {}

    public static void diagnosticCapture(String step) {}

    /** Mouse: type 0 move, 1 press, 2 release, 3 wheel; button 0 left, 1 right, 2 middle. */
    public static void mouse(int x, int y, int type, int button) {
        Input in = container.input;
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaEvents.mouse(x, y, in.x, in.y, type, button,
            in.buttons.contains(0), in.buttons.contains(1), in.buttons.contains(2))) return;
        in.mouse(x, y, type, button);
    }

    public static void key(int key, int character, boolean down) {
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaEvents.key(key, down)) return;
        container.input.key(key, (char) character, down);
    }

    private static final class Container implements GameContainer {
        final Input input = new Input();

        public xyz.znix.xftl.sys.Input getInput() { return input; }

        public int getWidth() { return canvasWidth(); }

        public int getHeight() { return canvasHeight(); }

        public void exit() {
            BrowserEngine.save();
            AndroidHost.get().exit();
        }

        public void setCursor(xyz.znix.xftl.rendering.Cursor c) { AndroidHost.get().setCursor(c == null ? null : c.getKey()); }
    }

    private static final class Input implements xyz.znix.xftl.sys.Input {
        int x, y;
        final Set<Integer> held = new HashSet<>(), pressed = new HashSet<>(), buttons = new HashSet<>();
        final List<InputListener> listeners = new ArrayList<>();

        public int getMouseX() { return x; }

        public int getMouseY() { return y; }

        public boolean isMouseButtonDown(int b) { return buttons.contains(b); }

        public boolean isKeyPressed(int k) { return pressed.remove(k); }

        public boolean isKeyDown(int k) { return held.contains(k); }

        public void addListener(InputListener l) { listeners.add(l); }

        public void removeAllListeners() { listeners.clear(); }

        public void clearInputPressedRecord() { pressed.clear(); }

        void reset() {
            for (int k : new ArrayList<>(held)) key(k, (char) 0, false);
            List<Integer> released = new ArrayList<>(buttons);
            buttons.clear();
            for (int b : released) for (InputListener l : new ArrayList<>(listeners)) l.mouseReleased(b, x, y);
            held.clear();
            pressed.clear();
        }

        void mouse(int nx, int ny, int type, int b) {
            int ox = x, oy = y;
            x = nx;
            y = ny;
            if (type == 1) buttons.add(b);
            if (type == 2 && !buttons.remove(b)) return;
            for (InputListener l : new ArrayList<>(listeners)) {
                if (type == 0) {
                    l.mouseMoved(ox, oy, x, y);
                    if (!buttons.isEmpty()) l.mouseDragged(ox, oy, x, y);
                } else if (type == 1) l.mousePressed(b, x, y);
                else if (type == 2) {
                    l.mouseReleased(b, x, y);
                    l.mouseClicked(b, x, y, 1);
                } else if (type == 3) l.mouseWheelMoved(b);
            }
        }

        void key(int k, char c, boolean down) {
            if (down) {
                held.add(k);
                pressed.add(k);
            } else held.remove(k);
            for (InputListener l : new ArrayList<>(listeners)) {
                if (down) l.keyPressed(k, c);
                else l.keyReleased(k, c);
            }
        }
    }
}
