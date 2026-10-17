package xyz.znix.xftl.game;

import org.jdom2.Document;
import org.jdom2.JDOMException;
import ftl.browser.BrowserSaxBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.util.InputAdapter;
import xyz.znix.xftl.BlueprintManager;
import xyz.znix.xftl.Datafile;
import xyz.znix.xftl.VanillaDatafile;
import xyz.znix.xftl.devutil.DebugConsole;
import xyz.znix.xftl.hangar.EditableShip;
import xyz.znix.xftl.hangar.SelectShipState;
import xyz.znix.xftl.rendering.Cursor;
import xyz.znix.xftl.rendering.Graphics;
import xyz.znix.xftl.rendering.ShaderProgramme;
import xyz.znix.xftl.sys.DatafileSelectState;
import xyz.znix.xftl.sys.Game;
import xyz.znix.xftl.sys.GameContainer;
import xyz.znix.xftl.vanillasave.VanillaSaveWriter;
import xyz.znix.xftl.vanillasave.VanillaSaveParser;
import xyz.znix.xftl.vanillasave.VanillaSaveData;
import xyz.znix.xftl.sys.PlatformSpecific;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class MainGame implements Game {
    private VanillaDatafile vanillaDatafile;
    private Datafile datafile;
    private final CommandLineArgs commandLineArgs;

    private GameContainer gameContainer;

    private GameState currentState;
    private boolean browserFocused = true;

    private InGameState.GameContent content;

    private SaveProfile profile;
    private TutorialSession tutorialSession;

    private static final class TutorialSession {
        final SaveProfile profile;
        final String rng;
        final boolean mode, browserMode;
        final ftl.browser.BrowserTutorialAdapter adapter;
        final ftl.browser.BrowserTutorialOverlay overlay;
        TutorialSession(MainGame main) {
            profile = main.profile;
            rng = ftl.browser.BrowserRandom.snapshot();
            mode = main.content.enableAdvancedEdition;
            browserMode = ftl.browser.BrowserFiles.enableAE;
            adapter = new ftl.browser.BrowserTutorialAdapter(new ftl.browser.BrowserTutorialDefinition(main.datafile));
            overlay = new ftl.browser.BrowserTutorialOverlay(main);
        }
    }

    public boolean isTutorialRunning() { return tutorialSession != null; }
    @Nullable public ftl.browser.BrowserTutorialAdapter getTutorialAdapter() {
        return tutorialSession == null ? null : tutorialSession.adapter;
    }
    @Nullable public ftl.browser.BrowserTutorialOverlay getTutorialOverlay() {
        return tutorialSession == null ? null : tutorialSession.overlay;
    }

    /** Tutorial session isolated from ordinary saves and progression. */
    public InGameState startTutorialSession() {
        if (isTutorialRunning() || !(currentState instanceof MainMenuState))
            throw new IllegalStateException("Tutorial must start from the main menu");
        TutorialSession previous = new TutorialSession(this);
        SaveProfile temporary = SaveProfile.createBlank();
        temporary.load(previous.profile.save());
        temporary.setAchievementPopups(false);
        ftl.browser.BrowserFiles.beginTemporaryWrites();
        tutorialSession = previous;
        profile = temporary;
        try {
            useAdvancedEdition(false);
            // The tutorial runs at normal difficulty (10 starting scrap in the original).
            InGameState state = new InGameState(this, content, "PLAYER_SHIP_TUTORIAL", Difficulty.NORMAL, null);
            setCurrentState(state);
            ftl.browser.BrowserDebug.event("tutorial", "session.start", "mode", "vanilla");
            return state;
        } catch (RuntimeException failure) {
            endTutorialSession();
            throw failure;
        }
    }

    public void endTutorialSession() { restoreTutorialSession(true); }

    private void restoreTutorialSession(boolean returnToMenu) {
        TutorialSession previous = tutorialSession;
        if (previous == null) return;
        try {
            if (returnToMenu && currentState != null) {
                currentState.shutdown();
                currentState = null;
                gameContainer.getInput().removeAllListeners();
                ftl.browser.BrowserEngine.resetInput();
            }
            // Rebuild content with the temporary profile still installed, so
            // reconciliation cannot mutate the normal profile on tutorial exit.
            if (returnToMenu) useAdvancedEdition(previous.mode);
        } finally {
            profile = previous.profile;
            tutorialSession = null;
            ftl.browser.BrowserFiles.enableAE = previous.browserMode;
            ftl.browser.BrowserFiles.endTemporaryWrites();
            ftl.browser.BrowserRandom.restore(previous.rng);
        }
        if (returnToMenu) setCurrentState(new MainMenuState(this, datafile));
        ftl.browser.BrowserDebug.event("tutorial", "session.end", "restored", "ordinary");
    }
    private final ftl.browser.BrowserProgressNotifications progressNotifications = new ftl.browser.BrowserProgressNotifications(this);
    public ftl.browser.BrowserProgressNotifications getProgressNotifications() { return progressNotifications; }

    private Cursor currentCursor;

    public MainGame(CommandLineArgs args) {
        this.commandLineArgs = args;
    }

    @Override
    public void init(@NotNull GameContainer gc) throws SlickException {
gameContainer = gc; profile = loadProfile(); loadDatafile(); switchToMainMenu();
    }

    @Override
    public void shutdown() {
        restoreTutorialSession(false);
        // The state and content might be null if there was an exception during
        // initialisation, as this will be run in a finally block.
        // If we throw an exception here, it'll hide the original one that
        // caused the crash in the first place.
        if (currentState != null) {
            currentState.shutdown();
        }

        if (content != null) {
            content.freeResources();
        }
    }

    public void switchToShipSelect() {
        // This may have been called by DatafileSelectState
        if (vanillaDatafile == null) {
            loadDatafile();
        }

        // The hangar shows the saved choice; the game content follows it when a run starts (see selectHangarMode).
        boolean mode = profile.getLastAdvancedEdition();
        ftl.browser.BrowserFiles.enableAE = mode;
        SelectShipState state = new SelectShipState(datafile, this, mode, null);
        setCurrentState(state);
    }

    private final java.util.HashMap<Boolean, BlueprintManager> hangarBlueprints = new java.util.HashMap<>();

    /**
     * The blueprints the hangar shows in each mode, loaded once and kept so that flipping the toggle back and forth
     * costs nothing. These are the hangar's own, not the game content's (they are never bound to the sounds).
     */
    public BlueprintManager getHangarBlueprints(boolean advancedEdition) {
        BlueprintManager manager = hangarBlueprints.get(advancedEdition);
        if (manager == null) {
            // The loader reads the global mode for the pirate file, so give it the mode it is building.
            boolean saved = ftl.browser.BrowserFiles.enableAE;
            ftl.browser.BrowserFiles.enableAE = advancedEdition;
            try {
                manager = new BlueprintManager(datafile, advancedEdition);
            } finally {
                ftl.browser.BrowserFiles.enableAE = saved;
            }
            hangarBlueprints.put(advancedEdition, manager);
        }
        return manager;
    }

    /**
     * The hangar's Advanced Edition toggle. The choice is saved and shown at once, and the hangar swaps to the
     * other mode's blueprints, but the game content (events, sounds, blueprints of the run) is not rebuilt: a run
     * does that when it starts ([startConfiguredRun]), and loading a saved run does it for the save's mode. The old
     * hangar [from] hands its pictures and texts to the new one.
     */
    public void selectHangarMode(boolean enabled, String selectedShip, Difficulty difficulty, SelectShipState from) {
        profile.setLastAdvancedEdition(enabled);
        profile.markDirty();
        ftl.browser.BrowserFiles.enableAE = enabled;
        SelectShipState state = new SelectShipState(datafile, this, enabled, from);
        state.restoreSelection(selectedShip, difficulty);
        setCurrentState(state);
        ftl.browser.BrowserDebug.record("hangar", "ae.change", "{\"enabled\":" + enabled + "}");
    }

    /** Switches the hangar and the game content to a mode at once (the diagnostics' way; the toggle itself defers). */
    public void changeHangarMode(boolean enabled, String selectedShip, Difficulty difficulty) {
        profile.setLastAdvancedEdition(enabled);
        profile.markDirty();
        useAdvancedEdition(enabled);
        SelectShipState state = new SelectShipState(datafile, this, enabled, null);
        state.restoreSelection(selectedShip, difficulty);
        setCurrentState(state);
        ftl.browser.BrowserDebug.record("hangar", "ae.change", "{\"enabled\":" + enabled + "}");
    }

    private void useAdvancedEdition(boolean enabled) {
        ftl.browser.BrowserFiles.enableAE = enabled;
        if (content.enableAdvancedEdition == enabled) return;
        InGameState.GameContent replacement = new InGameState.GameContent(datafile, enabled, content);
        if (currentState != null) { currentState.shutdown(); currentState = null; }
        content.freeResources();
        content = replacement;
        profile.configureProgression(content.shipFamilies, content.achievements);
    }

    public void switchToMainMenu() {
        if (isTutorialRunning()) { endTutorialSession(); return; }
        setCurrentState(new MainMenuState(this, datafile));
    }

    /**
     * Is there a run saved by Save+Quit that could be continued?
     */
    public boolean hasRunSave() {
return ftl.browser.BrowserFiles.getRun() != null;
    }

    /** Path of the vanilla-format save (continue.sav), next to the run save. */
    public Path getVanillaSavePath() {
        return PlatformSpecific.INSTANCE.getSaveGamePath().resolve("continue.sav");
    }

    /**
     * Continue a run saved by Save+Quit, from the main menu's Continue
     * button. If the save is missing or corrupt, fall back to the hangar.
     */
    public void continueSavedRun() {
Document doc = consumeRunSave(); if(doc != null) loadSavedGame(doc); else switchToShipSelect();
    }

    /**
     * Try to continue from a vanilla-format continue.sav, e.g. one copied in
     * from a PC install. The save is consumed on success so stale copies
     * can't shadow newer engine saves.
     *
     * @return true if a run was loaded
     */
    public boolean continueVanillaSave() {
return false;
    }

    /**
     * Diagnostics only (UI parity captures on disposable fixtures): start a run
     * from an original continue.sav, so the port can be compared against the
     * original game in the same state. Nothing is written to the run save.
     */
    public void startVanillaSaveForParity(byte[] bytes) {
        useAdvancedEdition(true);
        VanillaSaveData data = parseVanillaSaveForParity(bytes);
        ftl.browser.BrowserDebug.paritySavedBeacon = true;
        try {
            setCurrentState(new InGameState(this, content, data));
        } finally {
            ftl.browser.BrowserDebug.paritySavedBeacon = false;
        }
    }

    /** Diagnostics only: parse an original continue.sav against this content's ship layouts. */
    public VanillaSaveData parseVanillaSaveForParity(byte[] bytes) {
        return VanillaSaveParser.INSTANCE.parse(bytes, name -> {
            ShipBlueprint blueprint = content.blueprintManager.getShip(name);
            java.util.List<Integer> tiles = new java.util.ArrayList<>();
            for (ShipBlueprint.ParsedRoom room : blueprint.getRooms())
                tiles.add(room.getSize().getX() * room.getSize().getY());
            return new kotlin.Pair<>(tiles, blueprint.getDoors().size());
        }, name -> content.blueprintManager.getOrNull(name) instanceof xyz.znix.xftl.LazyShipBlueprint);
    }

    /**
     * Quit the game entirely - on Android this closes the app.
     */
    public void exitGame() {
        gameContainer.exit();
    }

    /**
     * The game content, for states (like the main menu) that need sounds or
     * other shared resources without a full InGameState.
     */
    public InGameState.GameContent getGameContent() {
        return content;
    }

    public GameContainer getGameContainer() { return gameContainer; }

    /** Only a confirmed title-screen New Game discards the Continue checkpoint. */
    public void discardSavedRun() { deleteRunSave(); }

    public void abandonRunToHangar() {
        if (isTutorialRunning()) { endTutorialSession(); return; }
        if (!(currentState instanceof InGameState state)) return;
        state.browserProgress.finishAbandoned();
        state.browserRunEnded = true;
        deleteRunSave();
        switchToShipSelect();
    }

    public void switchToDatafileSelect() {
        setCurrentState(new DatafileSelectState(this));
    }

    public void startNewGame(@NotNull String shipName, Difficulty difficulty, EditableShip customised) {
        startNewGame(new RunConfiguration(shipName, difficulty, content.enableAdvancedEdition), customised);
    }

    public void startNewGame(RunConfiguration config, EditableShip customised) {
        if (!profile.isShipUnlocked(config.getShip()) || !ShipProgression.INSTANCE.existsInMode(config.getShip(), config.getAdvancedEdition(), content.shipFamilies))
            throw new IllegalArgumentException("This ship layout is locked or unavailable in the selected edition");
        startConfiguredRun(config, customised);
    }

    /** Diagnostics deliberately cover locked layouts without changing profile progress. */
    public void startDiagnosticGame(String ship, Difficulty difficulty) {
        // The generation stream starts from the wall clock; a diagnostic game takes its seed from the (restorable)
        // simulation stream instead, so a seeded probe builds the same map, events and ships every run.
        ftl.browser.NativeRandom.seed(ftl.browser.NativeRandom.freshSeed());
        startConfiguredRun(new RunConfiguration(ship, difficulty, content.enableAdvancedEdition), null);
    }

    private void startConfiguredRun(RunConfiguration config, EditableShip customised) {
        useAdvancedEdition(config.getAdvancedEdition());
        // A new run replaces any saved one
        deleteRunSave();
        InGameState inGameState = new InGameState(this, content, config.getShip(), config.getDifficulty(), customised, config.getCrew());
        inGameState.getPlayer().setDisplayName(config.getDisplayName());
        setCurrentState(inGameState);
    }

    public void restartGame() {
        if (isTutorialRunning()) { endTutorialSession(); startTutorialSession(); return; }
        InGameState state = (InGameState) currentState;
        state.browserProgress.finishAbandoned();
        state.browserRunEnded = true;
        startNewGame(new RunConfiguration(state.getPlayer().getName(), state.getDifficulty(),
                state.getContent().enableAdvancedEdition, state.getPlayer().getDisplayName()), state.getPlayer().getCustomised());
    }

    /**
     * Called by the Android activity when the app loses focus (GitHub
     * issue #96): open the pause menu so returning to the app never
     * resumes an unpaused battlefield. One-way - it never closes or
     * navigates anything. No-op outside a run.
     */
    public void autoPauseIfInFlight() {
        if (currentState instanceof InGameState) {
            ((InGameState) currentState).autoPause();
        }
    }

    /** Native focus pause restores the previous explicit pause on focus return. */
    public void setBrowserFocus(boolean focused) {
        if (browserFocused != focused) ftl.browser.BrowserDebug.record("input", "focus.changed",
                "{\"focused\":" + focused + ",\"autoPause\":" + profile.getAutoPauseOnFocusLoss() + "}");
        browserFocused = focused;
        refreshFocusPause();
    }

    public boolean isBrowserFocused() { return browserFocused; }

    public void refreshFocusPause() {
        if (currentState instanceof InGameState state)
            state.setFocusPaused(!browserFocused && profile.getAutoPauseOnFocusLoss());
    }

    /**
     * The Android letterbox pause button: quick-pause, identical to the
     * space-bar PAUSE hotkey (freezes the game; no pause menu). No-op
     * outside a run (main menu, hangar).
     */
    public void togglePauseFromTouch() {
        if (currentState instanceof InGameState) {
            ((InGameState) currentState).toggleQuickPause();
        }
    }

    /**
     * True while a run is in flight (the Android letterbox pause button
     * only shows then).
     */
    public boolean isInFlight() {
        return currentState instanceof InGameState;
    }

    public void loadSavedGame(Document savedGame) {
        useAdvancedEdition(Boolean.parseBoolean(savedGame.getRootElement().getAttributeValue("enableAE")));
        InGameState inGameState = new InGameState(this, content, savedGame);
        setCurrentState(inGameState);
    }

    @Override
    public void update(@NotNull GameContainer gc, float dt) throws SlickException {
        boolean tutorialAtFrameStart = isTutorialRunning();
        applyAudioSettings();
        currentState.update(gc, dt);
        if (tutorialSession != null && currentState instanceof InGameState state)
            tutorialSession.adapter.update(state);
        progressNotifications.update(dt);

        // If the profile needs saving, do that now. This means that if there's
        // a bunch of changes in the same update, we won't save multiple times.
        // Exit/completion can restore the ordinary profile mid-update. Do not
        // publish a save as a side effect of that tutorial frame; a dirty normal
        // profile is still saved by its next ordinary update.
        if (!tutorialAtFrameStart && profile.getDirty()) {
            saveProfile();
        }
    }

    public void applyAudioSettings() {
        if (content == null || !(content.sounds instanceof RealSoundManager)) return;
        RealSoundManager sounds = (RealSoundManager) content.sounds;
        sounds.setSoundEffectVolume(Math.max(0f, Math.min(1f, profile.getSoundVolume())));
        sounds.setMusicVolume(Math.max(0f, Math.min(1f, profile.getMusicVolume())));
    }

    @Override
    public void render(@NotNull GameContainer gc, Graphics g) throws SlickException {
        // When we use shaders, we have to transform from pixels to NDC
        // If this is set wrong, all the text etc will be transformed wrong.
        ShaderProgramme.getSHADER_SCREEN_SIZE().set(gameContainer.getWidth(), gameContainer.getHeight());

        // Reset the transform from last frame, in case there was a transform
        // call that wasn't inside a pushTransform block.
        g.loadIdentityMatrix();

        currentState.render(gc, g);
        progressNotifications.render(g);

        // Check there aren't any mismatched pushTransform/popTransform calls.
        g.checkNoPushedTransforms();

        // Update the mouse cursor, if required.
        Cursor newCursor = currentState.getCurrentCursor();
        if (newCursor != currentCursor) {
            gc.setCursor(newCursor);
            currentCursor = newCursor;
        }
    }

    @Override
    public String getTitle() {
        return "Project Wormhole";
    }

    public SaveProfile getProfile() {
        return profile;
    }

    /**
     * Save the current game to XML, and re-load it.
     * <p>
     * This is only for development use, to find bugs in the serialisation logic!
     */
    public boolean doSaveLoadGame() {
        InGameState oldGame = (InGameState) currentState;
        InGameState newGame;

        Document savedGame;
        try {
            savedGame = oldGame.saveGameState();
            newGame = new InGameState(this, content, savedGame);
        } catch (Exception ex) {
            oldGame.debugFailedSaveRestore();
            ex.printStackTrace();
            return false;
        }

        // Only change the state once we've done the save-load, so if
        // there's an exception while loading the saved state we stay
        // on the old state for ease of debugging.
        setCurrentState(newGame);

        // Copy over some basic UI stuff
        newGame.debugContinuousSaveRestore(oldGame);

        return true;
    }

    public GameState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(GameState currentState) {
        if (this.currentState != null) {
            this.currentState.shutdown();
        }

        this.currentState = currentState;

        gameContainer.getInput().removeAllListeners();
        gameContainer.getInput().addListener(currentState);

        applyAudioSettings();
        currentState.init(gameContainer);
        refreshFocusPause();
    }

    public void quitGame() {
        // Save the current run so it can be resumed when the game is reopened.
        // (Upstream never saves here - the comment below is upstream's.)
        writeRunSave();

        // With a main menu (issue #2), Save+Quit returns there, where the
        // player can use Continue to get back into this run.
        switchToMainMenu();
    }

    /**
     * Serialises the current run to the run save (temp file + atomic
     * rename), so it can be resumed from the main menu's Continue button.
     *
     * Called by {@link #quitGame()}, and automatically after every sector
     * jump - Android routinely force-closes backgrounded apps, and without
     * this a force-close costs the player the whole run instead of just the
     * current beacon (which is all vanilla FTL loses - it auto-saves on
     * every jump too).
     *
     * Safe to call from any state: outside a run it does nothing, and a
     * failed save is logged loudly but never fatal - the previous save
     * stays intact thanks to the rename.
     */
    public void writeRunSave() {
        // Tutorial state is never an ordinary checkpoint. Skip serialization
        // itself, not only publication through BrowserFiles.
        if (isTutorialRunning()) {
            ftl.browser.BrowserDebug.record("persistence", "save.ignored", "{\"kind\":\"run\",\"reason\":\"tutorial\"}");
            return;
        }
        if (currentState instanceof InGameState state) {
            ftl.browser.BrowserFiles.setRun(state.browserRunEnded ? null : ftl.browser.BrowserXml.write(state.saveGameState()));
        }
    }

    private Path getRunSavePath() {
        return PlatformSpecific.INSTANCE.getSaveGamePath().resolve("run-save.xml");
    }

    /**
     * Deletes the run save, if there is one. Used when a new run starts
     * (so an old save can't leak into it) and when the player is defeated
     * (so Continue can't resurrect a dead run).
     */
    public void deleteRunSave() {
ftl.browser.BrowserFiles.setRun(null);
    }

    /**
     * Loads a run saved by Save+Quit, if there is one.
     *
     * The save is consumed (deleted) either way, so a corrupt save can't
     * trap the player: if restoring it then fails the player falls back to
     * the hangar rather than crash-looping on every launch.
     *
     * @return the saved game's document, or null if there wasn't a valid one
     */
    @Nullable
    private Document consumeRunSave() {
String xml = ftl.browser.BrowserFiles.getRun(); return xml == null ? null : ftl.browser.BrowserXml.parse(xml);
    }

    private boolean loadDatafile() {
if(datafile == null) { datafile = new Datafile(); content = new InGameState.GameContent(datafile, ftl.browser.BrowserFiles.enableAE); profile.configureProgression(content.shipFamilies, content.achievements); } return true;
    }

    public static Path findFtlDat() {
return null;
    }

    private SaveProfile loadProfile() {
SaveProfile p = SaveProfile.createBlank(); String xml=ftl.browser.BrowserFiles.getProfile(); if(xml != null) p.load(ftl.browser.BrowserXml.parse(xml)); else newProfileWelcome = true; return p;
    }

    /** ScoreKeeper flags a profile created this session; the title shows "welcome" once. */
    private boolean newProfileWelcome;

    public boolean consumeNewProfileWelcome() {
        boolean show = newProfileWelcome;
        newProfileWelcome = false;
        return show;
    }

    private void saveProfile() {
ftl.browser.BrowserFiles.setProfile(ftl.browser.BrowserXml.write(profile.save())); profile.markSaveComplete();
    }

    public static abstract class GameState extends InputAdapter {
        public void init(@NotNull GameContainer container) {
        }

        public abstract void shutdown();

        public abstract void update(@NotNull GameContainer container, float delta) throws SlickException;

        public abstract void render(@NotNull GameContainer container, @NotNull Graphics g) throws SlickException;

        @Nullable
        public Cursor getCurrentCursor() {
            return null;
        }
    }

    public static class CommandLineArgs {
        // If a new game should be started, this is the name of the ship to use.
        public String newGameShip;

        // If a save created with the debug 'save' command should be loaded, this is it's name.
        public String debugLoad;
    }
}
