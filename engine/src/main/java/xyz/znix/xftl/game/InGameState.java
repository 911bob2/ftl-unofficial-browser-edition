package xyz.znix.xftl.game;

import kotlin.random.Random;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import ftl.browser.BrowserSaxBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.util.InputAdapter;
import xyz.znix.xftl.*;
import xyz.znix.xftl.ai.ShipAI;
import xyz.znix.xftl.augments.AugmentBlueprint;
import xyz.znix.xftl.crew.*;
import xyz.znix.xftl.devutil.DebugConsole;
import xyz.znix.xftl.devutil.DebugFlagManager;
import xyz.znix.xftl.drones.AbstractExternalDrone;
import xyz.znix.xftl.hangar.EditableShip;
import xyz.znix.xftl.layout.Room;
import xyz.znix.xftl.math.IPoint;
import xyz.znix.xftl.math.Point;
import xyz.znix.xftl.math.RoomPoint;
import xyz.znix.xftl.rendering.*;
import xyz.znix.xftl.savegame.ObjectRefs;
import xyz.znix.xftl.savegame.RefLoader;
import xyz.znix.xftl.savegame.SaveUtil;
import xyz.znix.xftl.sector.*;
import xyz.znix.xftl.shipgen.EnemyShipSpec;
import xyz.znix.xftl.shipgen.ShipGenerator;
import xyz.znix.xftl.sys.GameContainer;
import xyz.znix.xftl.sys.Input;
import xyz.znix.xftl.sys.ResourceContext;
import xyz.znix.xftl.systems.*;
import xyz.znix.xftl.ui.SpecDeserialiser;
import xyz.znix.xftl.ui.UIProvider;
import xyz.znix.xftl.ui.Widget;
import xyz.znix.xftl.weapons.Damage;
import xyz.znix.xftl.vanillasave.VanillaSaveData;
import xyz.znix.xftl.vanillasave.VanillaSaveApplier;
import xyz.znix.xftl.vanillasave.VanillaSaveFormat;
import kotlin.Triple;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class InGameState extends MainGame.GameState implements TouchRouter.LiftTargets {
    public boolean browserRunEnded = false;
    public final ftl.browser.BrowserRunProgress browserProgress = new ftl.browser.BrowserRunProgress(this);
    public final ftl.browser.BrowserAchievementProgress browserAchievements = new ftl.browser.BrowserAchievementProgress(this);
    @Nullable // Null only when running tests
    private final MainGame mainGame;

    private final GameContent content;

    private Difficulty difficulty = Difficulty.NORMAL;

    // These are copied from the GameContent object for convenience
    private final Datafile df;

    public Datafile getDatafile() { return df; }
    private final boolean enableAdvancedEdition;
    private final BlueprintManager blueprintManager;
    private final EventManager eventManager;

    private final SpecDeserialiser uiLoader = new SpecDeserialiser(new GameUIProvider());

    private GameMap gameMap;
    private Ship player;
    private Ship enemy;
    private ShipAI enemyAI;

    private LootPool lootPool;

    private SILFontLoader pauseFont;
    private static final float PAUSE_PULSE_MAX = 0.75f;
    private float pauseBannerTimer;
    // Shared like the original's static pulse direction (_DAT_00873fe0).
    private static float pauseBannerDirection = 1f;

    private Image missingImage;

    private final Point tempPoint = new Point(0, 0);

    private Room hoveredRoom;
    private Room touchHoverRoom;
    private RoomClickListener clickEvent;
    private final boolean[] mouseDownPrev = new boolean[3];
    /**
     * A held button whose press something other than the game took (the tutorial's panel, the debug console): its
     * release is not the game's.
     */
    private final boolean[] mouseCaptured = new boolean[3];

    /**
     * The input device from the most recent update() call. Lets UI code read
     * the keyboard modifier state while handling clicks (eg ctrl + clicking
     * a weapon slot for autofire).
     */
    public Input input;

    private ITooltipProvider lastFameTooltip;

    private PlayerShipUI shipUI;
    private HostileShipUI hostileShipUI;

    private boolean enemyIsHostile;
    private boolean enemyInteriorVisible;

    private Beacon currentBeacon;
    private boolean paused;
    private boolean focusPaused;

    // The list of all the sectors the ship has visited, including
    // the current one.
    private final ArrayList<GameMap.SectorInfo> visitedSectors = new ArrayList<>();

    private DebugConsole debugConsole;
    private boolean debugConsoleVisible;

    private DebugFlagManager debugFlags = new DebugFlagManager();

    private boolean isCurrentlyLoadingSave;

    /**
     * The encounter tail bytes from a vanilla save this run was imported
     * from, preserved verbatim by the vanilla-format writer (vanilla hangs
     * on tails we invent). Null for runs that started in this engine.
     */
    @Nullable
    public byte[] vanillaTail;

    // Used for animation rendering only
    private float renderingDeltaTime;
    /** Counts the updates: the UI's mouse pass runs twice per update, a timer kept there advances once per update. */
    private int browserUpdateSerial;
    public int getBrowserUpdateSerial() { return browserUpdateSerial; }
    public void browserCountUpdate() { browserUpdateSerial++; }

    private final Point playerShipOffset = new Point(0, 0);

    /** Hyperspace: the Lua view of CombatControl::playerShipPosition (hs.lua; a script may write it). */
    public Object hsCombatShipPosition;

    /**
     * The names of the quest events that couldn't be fit in the current
     * sector, and were delayed to the next one (StarMap +0x6f0; the event is
     * created again when the next sector is generated).
     */
    private final ArrayList<String> delayedQuests = new ArrayList<>();

    private final EventCreator eventCreator = new EventCreator(this);

    /**
     * This is the list of all the player-owned crew, as used by the main UI
     * and the ship upgrade window.
     */
    private final ArrayList<LivingCrew> playerCrew = new ArrayList<>();

    /**
     * The buttons each hotkey is bound to.
     * <p>
     * This is calculated from the profile and hotkey manager, so it shouldn't
     * be modified externally.
     */
    private final HashMap<Hotkey, HotkeyButton> reverseHotkeyBindings = new HashMap<>();
    private final HashMap<HotkeyButton, Hotkey> forwardHotkeyBindings = new HashMap<>();

    /**
     * The hotkeys that have been pressed and queued up to be used by the game.
     * <p>
     * This is done in case a key is somehow dispatched while update() is running.
     */
    private final List<Hotkey> queuedHotkeys = Collections.synchronizedList(new ArrayList<>());

    public InGameState(MainGame mainGame, GameContent content, String playerShipName, Difficulty difficulty, EditableShip customised) {
        this(mainGame, content, playerShipName, difficulty, customised, null);
    }

    public InGameState(MainGame mainGame, GameContent content, String playerShipName, Difficulty difficulty, EditableShip customised, List<StartingCrew> startingCrew) {
        this(mainGame, content);
        this.difficulty = difficulty;
        if (mainGame != null && !mainGame.isTutorialRunning()) mainGame.getProfile().startBrowserRun();

        // StarMap::NewGame (0x5f9e00): the tree seed is the next draw of the
        // global stream. The starting crew is named before this, so its names
        // are the ones the generated event crew has to avoid.
        eventManager.resetEventState();
        // Modded: OnInit's equipment draws (lists) and Hyperspace's crew list come before the blueprint's crew.
        if (xyz.znix.xftl.hs.HS.modded)
            hsStartEquipment = xyz.znix.xftl.hs.ship.HsShipStart.resolve(blueprintManager.getShip(playerShipName), blueprintManager);
        if (xyz.znix.xftl.hs.HS.active) {
            hsStartCrew = xyz.znix.xftl.hs.ship.HsShipState.drawStartingCrew(playerShipName, this);
            xyz.znix.xftl.hs.ship.HsAchievements.bindGame(this);
        }
        List<LivingCrewInfo> newCrew = createStartingCrew(playerShipName, startingCrew);
        if (hsStartCrew != null) {
            List<LivingCrewInfo> named = new ArrayList<>(newCrew);
            for (xyz.znix.xftl.hs.ship.HsStartingCrewMember c : hsStartCrew) named.add(c.info);
            getCrewFactory().setUsedNames(named);
        } else
        getCrewFactory().setUsedNames(newCrew);
        // Hyperspace (StarMap::NewGame hooks): the HS run state, the run seed and the initial variables.
        boolean hsSeeded = false;
        if (eventManager.getHs() != null) {
            xyz.znix.xftl.hs.events.HsEvents.INSTANCE.newGame(this, playerShipName);
            hsSeeded = xyz.znix.xftl.hs.sector.HsSeeds.enabled;
        }
        // Seeds.cpp: a seeded run's tree and first sector both come from the run seed (the original's own draw of
        // the tree seed is made and then overridden).
        int treeSeed = ftl.browser.NativeRandom.next31();
        if (hsSeeded) treeSeed = xyz.znix.xftl.hs.sector.HsSeeds.currentSeed;
        gameMap = new GameMap(df, eventManager, enableAdvancedEdition, treeSeed);

        // Start at the first beacon of the first sector
        // Be sure we do this after creating the player ship, it's used by the enemy AI
        // Note that we don't store the current sector anywhere - it's determined via
        // the current beacon.
        Sector firstSector = hsSeeded
                ? gameMap.generateSector(gameMap.getSectors().get(0).get(0), this, xyz.znix.xftl.hs.sector.HsSeeds.currentSeed)
                : gameMap.generateSector(gameMap.getSectors().get(0).get(0), this);

        // StarMap::NewGame (0x5f9e00) then gives the first beacon START_GAME's
        // text (TUTORIAL_START in the tutorial), created from the same stream.
        boolean tutorialRun = mainGame != null && mainGame.isTutorialRunning();
        CreatedEvent startGame = eventCreator.create(eventManager.get(tutorialRun ? "TUTORIAL_START" : "START_GAME"), 0);
        if (startGame != null) {
            if (!tutorialRun) eventCreator.appendStartTip(startGame);
            if (eventManager.getHs() != null && !tutorialRun) {
                // Hyperspace keeps the start beacon's own event (its HS tags run on arrival); only the text is
                // START_GAME's, as in the original.
                firstSector.getStartBeacon().getCreatedEvent().setText(startGame.getText());
            } else {
                firstSector.getStartBeacon().setCreatedEvent(startGame);
            }
        }
        setCurrentBeacon(firstSector.getStartBeacon());
        if (eventManager.getHs() != null && xyz.znix.xftl.hs.sector.HsSeeds.enabled) {
            // Seeds.cpp NewGame (priority 500): the quest seed of the start location.
            Beacon start = firstSector.getStartBeacon();
            xyz.znix.xftl.hs.sector.HsSeeds.questSeed = xyz.znix.xftl.hs.sector.HsSeeds.INSTANCE.questSeedFor(start.getPos().getX(), start.getPos().getY());
        }

        // Do this after setting the initial beacon, since the ship reads the current
        // beacon when calculating its power values.
        createNewPlayerShip(playerShipName, customised, newCrew);
        if (eventManager.getHs() != null) xyz.znix.xftl.hs.events.HsEvents.INSTANCE.afterPlayerShip(this);
        browserAchievements.beaconArrived();

        // Don't create PlayerShipUI in automated tests
        if (mainGame != null) {
            shipUI = new PlayerShipUI(player, this);
        }

        // Show the starting beacon dialogue, since it wasn't displayed earlier
        // due to shipUI not existing yet.
        showArrivalEvent();
    }

    /** The beacon's own event on arrival (Hyperspace: WorldManager::CreateLocation; vanilla: the same window). */
    private void showArrivalEvent() {
        if (shipUI != null) shipUI.showEventDialogue(currentBeacon.getCreatedEvent(), true);
    }

    /**
     * Load a previously-saved game.
     */
    public InGameState(MainGame mainGame, GameContent content, Document saveGame) {
        this(mainGame, content);

        Element root = saveGame.getRootElement();
        if (!root.getName().equals("xftlSaveGame")) {
            throw new IllegalArgumentException("Bad save-game root element name: " + root.getName());
        }

        // If AE was enabled on the save, make sure our content also has it.
        boolean saveEnabledAE = Boolean.parseBoolean(saveGame.getRootElement().getAttributeValue("enableAE"));
        if (saveEnabledAE != content.enableAdvancedEdition) {
            throw new IllegalArgumentException("Cannot load a game with content that doesn't match it's AE state!");
        }

        difficulty = Difficulty.valueOf(root.getAttributeValue("difficulty"));

        isCurrentlyLoadingSave = true;
        if (xyz.znix.xftl.hs.HS.modded) xyz.znix.xftl.hs.save.HsSpaceId.restoreEarly(root);
        loadGameState(root);
        isCurrentlyLoadingSave = false;
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.drones.DroneRuntime.hsCrewBodies(this);
        currentBeacon.getSector().clearVisitedEvents(currentBeacon);

        // This just loads stuff from XML, we don't need to serialise it
        lootPool = new LootPool(blueprintManager, currentBeacon.getSector().getType());

        // Give all the ships an update, for stuff like the
        // doors automatically opening. This must only be done once all
        // the ships are loaded, otherwise it'd break hacking since the
        // hacked system would think it wasn't being hacked any more.
        for (Beacon beacon : currentBeacon.getSector().getBeacons()) {
            if (beacon.getShip() != null)
                beacon.getShip().afterSaveLoad();
        }
        player.afterSaveLoad();
        for (Beacon beacon : currentBeacon.getSector().getBeacons()) {
            if (beacon.getShip() != null)
                beacon.getShip().update(0f);
        }
        player.update(0f);

        // Start the music up again - unless this sector's music is already
        // playing, in which case let it carry on.
        //
        // The continuous-save-load mode reloads the game every frame, and
        // restarting the music each time would use a lot of CPU for no
        // benefit. The sound manager is part of the game content, so it's
        // not cleared across a reload and the sector's music keeps playing.
        //
        // Only carrying on when it's THIS sector's tracks (rather than any
        // music at all) matters since the main menu exists (issue #28):
        // continuing a run from it leaves the title theme playing, which
        // then never got replaced as the tracklist still only had the
        // title track in it.
        if (getSounds() instanceof RealSoundManager real) {
            List<MusicSpec> sectorTracks = getSectorTracks(currentBeacon.getSector().getType());
            if (!sectorTracks.contains(real.getCurrentMusic())) {
                getSounds().switchMusicList(sectorTracks);
            }
        }

        updatePlayerCrew();
        if (xyz.znix.xftl.hs.HS.modded) xyz.znix.xftl.hs.save.HsSave.readRun(root);
        // A box saved open comes back open: the save holds it already, so its reappearing is no new autosave.
        browserEventOpenSeen = shipUI != null && shipUI.getDiagnosticDialogue() != null;
    }

    /**
     * Load a run from a parsed vanilla save (continue.sav). The sector map's
     * geometry is regenerated by our own generator from the save's seeds
     * (vanilla's exact generator isn't public), and every bit of per-beacon
     * state plus the full player ship is restored on top of it.
     */
    public InGameState(MainGame mainGame, GameContent content, VanillaSaveData data) {
        this(mainGame, content, data, false);
    }

    /**
     * [imported]: a run imported from the original game (vanillasave.NativeImport), which goes on at the beacon it was
     * saved at when the regenerated sector has the same beacons, with the Hyperspace state of a Hyperspace run.
     */
    public InGameState(MainGame mainGame, GameContent content, VanillaSaveData data, boolean imported) {
        this(mainGame, content);
        difficulty = data.getDifficulty();
        vanillaTail = data.getTail();
        isCurrentlyLoadingSave = true;

        // StarMap::LoadGame (0x5fb9a0): the tree is regenerated from the
        // saved tree seed and the saved route flags mark the visited nodes
        // (the current sector is the visited one furthest along), then the
        // current sector's map from the saved layout seed.
        eventManager.resetEventState();
        // Hyperspace: its run state (seeds, variables, queues, triggered events) before the map is generated from the seeds.
        if (xyz.znix.xftl.hs.HS.modded && data.getHs() != null) {
            org.jdom2.Element hsRoot = xyz.znix.xftl.vanillasave.NativeImport.hyperspaceRoot(data.getHs(), data.getSectorLayoutSeed());
            xyz.znix.xftl.hs.save.HsSpaceId.restoreEarly(hsRoot);
            xyz.znix.xftl.hs.save.HsSave.readRun(hsRoot);
            if (!data.getHs().getCustomVictoryType().isEmpty())
                xyz.znix.xftl.hs.ship.HsShipUnlocks.setCustomVictoryType = data.getHs().getCustomVictoryType();
        }
        gameMap = new GameMap(df, eventManager, enableAdvancedEdition, data.getSectorTreeSeed());

        GameMap.SectorInfo info = gameMap.getSectors().get(0).get(0);
        visitedSectors.add(info);
        List<GameMap.SectorInfo> allSectors = new ArrayList<>();
        for (List<GameMap.SectorInfo> column : gameMap.getSectors()) {
            allSectors.addAll(column);
        }
        // The generated event crew avoids the names of the loaded crew.
        List<String> loadedNames = new ArrayList<>();
        for (VanillaSaveData.VanillaCrew vc : data.getShip().getCrew()) {
            if (vc.isPlayer()) loadedNames.add(vc.getName());
        }
        getCrewFactory().setUsedNames(loadedNames);
        List<Boolean> route = data.getRouteFlags();
        int highestColumn = 0;
        for (int i = 0; i < allSectors.size() && i < route.size(); i++) {
            GameMap.SectorInfo candidate = allSectors.get(i);
            if (route.get(i) && !visitedSectors.contains(candidate)) {
                visitedSectors.add(candidate);
            }
            if (route.get(i) && candidate.getColumnNumber() > highestColumn) {
                highestColumn = candidate.getColumnNumber();
                info = candidate;
            }
        }
        if (route.isEmpty()) {
            // A save without route flags: walk the first option of each column.
            int targetColumn = Math.min(data.getSectorNumber(), gameMap.getSectors().size() - 1);
            for (int column = 1; column <= targetColumn; column++) {
                info = gameMap.getSectors().get(column).get(0);
                visitedSectors.add(info);
            }
        }

        Sector sector = gameMap.generateSector(info, this, data.getSectorLayoutSeed());
        VanillaSaveApplier.INSTANCE.applyBeacons(data, sector, this);

        // Build the player ship from its blueprint, then patch the freshly
        // serialised ship XML with the vanilla save's values and reload it
        // through the regular deserialiser. This guarantees the element
        // structure matches exactly what the engine knows how to load.
        // The regenerated geometry doesn't match vanilla's, so the save's
        // current-beacon index would land the player at an arbitrary map
        // position (reported: mid-sector on import). The intended workflow
        // is transferring runs at a sector boundary, so always arrive at
        // the regenerated sector's entry beacon and mark it visited, like
        // an in-engine jump would.
        //
        // This block MUST stay before createNewPlayerShip: its update pass
        // reaches Sensors.getBlocked -> getCurrentBeacon(), which NPE'd
        // while currentBeacon was still null.
        currentBeacon = sector.getStartBeacon();
        // Parity captures compare the beacon the original saved at (store, event).
        // The save has no environment, and parity runs only use hazard-free beacons.
        if (imported && data.getBeacons().size() == sector.getBeacons().size() && data.getCurrentBeaconId() < sector.getBeacons().size()) {
            // The original's own generator, so the same map: the run goes on where it was saved.
            currentBeacon = sector.getBeacons().get(data.getCurrentBeaconId());
            currentBeacon.setHasStore(data.getBeacons().get(data.getCurrentBeaconId()).getStorePresent());
        } else if (ftl.browser.BrowserDebug.paritySavedBeacon && data.getCurrentBeaconId() < sector.getBeacons().size()) {
            currentBeacon = sector.getBeacons().get(data.getCurrentBeaconId());
            currentBeacon.debugSetEnvironment(Beacon.EnvironmentType.NORMAL.getCreate().invoke(this, currentBeacon));
            // Our regenerated beacon may hold a store the original's does not.
            if (data.getCurrentBeaconId() < data.getBeacons().size())
                currentBeacon.setHasStore(data.getBeacons().get(data.getCurrentBeaconId()).getStorePresent());
        }
        currentBeacon.setVisited(true);
        sector.clearVisitedEvents(currentBeacon);
        int beaconId = sector.getBeacons().indexOf(currentBeacon);

        createNewPlayerShip(data.getBlueprintName(), null);
        player = applyVanillaShip(data, data.getShip(), player, true, "player");

        if (mainGame != null) {
            shipUI = new PlayerShipUI(player, this);
        }

        isCurrentlyLoadingSave = false;
        lootPool = new LootPool(blueprintManager, sector.getType());
        browserProgress.importVanilla(data.getShipsDefeated(), data.getBeaconsExplored(), data.getScrapCollected(), data.getCrewHired());

        // The ship the player was fighting (or the one parked at the beacon)
        // is rebuilt from its spawn spec and seed, like vanilla does. The
        // data list is indexed by VANILLA's beacon order, which can differ
        // in length from ours - clamp before looking up.
        int dataBeaconId = Math.min(beaconId, data.getBeacons().size() - 1);
        VanillaSaveData.VanillaBeacon dataBeacon = data.getBeacons().get(dataBeaconId);
        if (data.getEnemyShip() != null) {
            // A ship at the beacon: the save's tail holds it as a full section, hostile or not (native-capture
            // st-van-a1: a fight, the beacon also names its event; st-van-c1: a neutral ship the beacon does not name).
            spawnVanillaNearbyShip(data, data.getEnemyShip());
        } else if (dataBeacon.getEnemyPresent()) {
            spawnVanillaEnemy(dataBeacon.getShipEventId(), dataBeacon.getShipEventSeed());
        } else {
            setEnemy(currentBeacon.getShip());
        }
        placeVanillaBoarders(data.getShip(), player, false);
        if (enemy != null && data.getEnemyShip() != null)
            placeVanillaBoarders(data.getEnemyShip(), enemy, true);

        if (enemy != null)
            enemy.afterSaveLoad();
        player.afterSaveLoad();
        for (Beacon beacon : sector.getBeacons()) {
            if (beacon.getShip() != null)
                beacon.getShip().update(0f);
        }
        player.update(0f);

        // Start the sector's music (mirrors the XML save-load constructor).
        if (getSounds() instanceof RealSoundManager real) {
            List<MusicSpec> sectorTracks = getSectorTracks(sector.getType());
            if (!sectorTracks.contains(real.getCurrentMusic())) {
                getSounds().switchMusicList(sectorTracks);
            }
        }

        updatePlayerCrew();
    }

    /**
     * Spawns a hostile ship at the current beacon from an event and the
     * recorded generation seed - vanilla stores enemies as a spawn spec
     * rather than a serialised ship, so imports rebuild them this way.
     */
    public void spawnVanillaEnemy(String eventId, int seed) {
        Event event = eventManager.getByDeserialisationIdOrNull(eventId);
        if (event == null || event.getLoadShipName() == null || !eventManager.hasShip(event.getLoadShipName())) {
            setEnemy(null);
            return;
        }
        EnemyShipSpec spec = eventManager.getShip(event.getLoadShipName());
        int sectorNum = currentBeacon.getSector().getSectorNumber();
        setEnemy(content.generator.buildShip(this, spec, sectorNum, difficulty, seed));
        currentBeacon.setShip(enemy);
        setEnemyIsHostile(true);
    }

    /**
     * Rebuilds a nearby ship saved mid-encounter from its blueprint and ship section,
     * with the first enemy spec that can field that blueprint.
     */
    private void spawnVanillaNearbyShip(VanillaSaveData data, VanillaSaveData.ShipData vs) {
        EnemyShipSpec spec = null;
        for (EnemyShipSpec candidate : content.eventManager.getShips()) {
            for (xyz.znix.xftl.Blueprint blueprint : candidate.getAutoBlueprint().list()) {
                if (blueprint.getName().equals(vs.getBlueprint())) {
                    spec = candidate;
                    break;
                }
            }
            if (spec != null) break;
        }
        if (spec == null) {
            setEnemy(null);
            return;
        }
        Ship base = new Ship(blueprintManager.getShip(vs.getBlueprint()), this, null, spec, null);
        base.loadDefaultContents();
        // Generated enemies carry CreateShip's level-scaled hull.
        int level = currentBeacon.getSector().getSectorNumber() + difficulty.getSectorOffset();
        base.setBrowserMaxHealth(Math.max(vs.getHull(),
                xyz.znix.xftl.shipgen.ShipGenerator.Companion.nativeMaxHull(base.getType().getMaxHealth(), level)));
        // Mount the saved weapons so their hardpoints exist in the serialised ship.
        for (int i = 0; i < base.getHardpoints().size(); i++) {
            xyz.znix.xftl.IBlueprint weapon = i < vs.getWeapons().size() ? blueprintManager.getOrNull(vs.getWeapons().get(i).getFirst()) : null;
            base.getHardpoints().get(i).setWeapon(weapon instanceof xyz.znix.xftl.weapons.AbstractWeaponBlueprint w ? w.buildInstance(base) : null);
        }
        setEnemy(applyVanillaShip(data, vs, base, false, "enemy"));
        // A neutral ship is an event's, which the beacon does not keep (it is saved as the ship nearby, saveToXML).
        if (vs.getHostile()) currentBeacon.setShip(enemy);
        setEnemyIsHostile(vs.getHostile());
    }

    /**
     * Places the crew standing on [ship] that belong to the other side as intruders,
     * in their saved rooms and squares.
     */
    private void placeVanillaBoarders(VanillaSaveData.ShipData vs, Ship ship, boolean playerOwned) {
        for (VanillaSaveData.VanillaCrew vc : vs.getCrew()) {
            if (vc.isPlayer() != playerOwned) continue;
            if (vc.getRoomId() < 0 || vc.getRoomId() >= ship.getRooms().size()) continue;
            if (!(blueprintManager.getOrNull(vc.getRace()) instanceof xyz.znix.xftl.crew.CrewBlueprint race)) continue;
            xyz.znix.xftl.crew.LivingCrewInfo info = xyz.znix.xftl.crew.LivingCrewInfo.Companion.generateRandom(race, this);
            info.setName(vc.getName());
            xyz.znix.xftl.crew.LivingCrew crew = ship.addCrewMember(info, false, true);
            Room room = ship.getRooms().get(vc.getRoomId());
            crew.jumpTo(new xyz.znix.xftl.math.RoomPoint(room, vc.getRoomSquare() % room.getWidth(), vc.getRoomSquare() / room.getWidth()));
            crew.setHealth(vc.getHealth());
            if (playerOwned) crew.setOwnerShip(player);
        }
    }

    /**
     * Patches the freshly-serialised player ship XML with the vanilla save's
     * values and reloads it through the regular deserialiser.
     */
    /**
     * Patch [target]'s freshly serialised XML with a vanilla ship section and reload
     * it. [ownCrewIsPlayer] picks the crew that belong to it: the player's ship keeps
     * player-controlled crew, a nearby ship the others (boarders are placed later).
     */
    private Ship applyVanillaShip(VanillaSaveData data, VanillaSaveData.ShipData vs, Ship target,
                                  boolean ownCrewIsPlayer, String refName) {
        // Systems bought during the run: install them in their slot first, so the serialised ship has their element.
        for (Map.Entry<String, String> slotName : VanillaSaveFormat.INSTANCE.systemSlotEntries()) {
            if (!vs.getSystems().containsKey(slotName.getKey())) continue;
            String xftlName = slotName.getValue();
            if (target.getSystems().stream().anyMatch(sys -> sys.getBlueprint().getName().equals(xftlName))) continue;
            if (!(blueprintManager.getOrNull(xftlName) instanceof xyz.znix.xftl.systems.SystemBlueprint blueprint)) continue;
            target.getSystemSlots().stream().filter(slot -> slot.getSystem() == blueprint).findFirst().ifPresent(slot -> slot.getRoom().setSystem(slot));
        }
        // The player's weapons as saved, so weapons gained since the start (an event, a store) get their hardpoint
        // element too; the enemy's are mounted by its caller.
        if (ownCrewIsPlayer) {
            for (int i = 0; i < target.getHardpoints().size(); i++) {
                xyz.znix.xftl.IBlueprint weapon = i < vs.getWeapons().size() ? blueprintManager.getOrNull(vs.getWeapons().get(i).getFirst()) : null;
                target.getHardpoints().get(i).setWeapon(weapon instanceof xyz.znix.xftl.weapons.AbstractWeaponBlueprint w ? w.buildInstance(target) : null);
            }
        }
        Element xml = new Element("playerShip");
        ObjectRefs refs = new ObjectRefs();
        refs.register(target, refName);
        target.saveToXML(xml, refs);

        xml.setAttribute("health", Integer.toString(vs.getHull()));
        // The name the player gave the ship in the hangar.
        if (ownCrewIsPlayer && vs.getName() != null && !vs.getName().isEmpty()) xml.setAttribute("displayName", vs.getName());
        xml.setAttribute("reactorPower", Integer.toString(vs.getReactor()));
        xml.setAttribute("ftlProgress", Float.toString(vs.getFtlProgressTicks() / 85000f));

        Element resources = xml.getChild("resources");
        resources.setAttribute("FUEL", Integer.toString(vs.getFuel()));
        resources.setAttribute("MISSILES", Integer.toString(vs.getMissiles()));
        resources.setAttribute("DRONES", Integer.toString(vs.getDroneParts()));
        resources.setAttribute("SCRAP", Integer.toString(vs.getScrap()));

        // Patch systems by name; systems the blueprint has no slot for are
        // skipped (they can only be lost if vanilla bought a system our
        // layout has no slot for, which the dat doesn't allow for player
        // ships).
        for (Element sysElem : new ArrayList<>(xml.getChild("systems").getChildren("system"))) {
            String xftlName = sysElem.getAttributeValue("name");
            String slot = vanillaSlotForXftlName(xftlName);
            if (slot == null) continue;
            VanillaSaveData.VanillaSystem vsSys = vs.getSystems().get(slot);
            if (vsSys == null) {
                // A starting system the ship lost (Hyperspace's removeSystem): the save has none.
                if (vs == data.getShip() && data.getHs() != null) xml.getChild("systems").removeContent(sysElem);
                continue;
            }

            sysElem.setAttribute("level", Integer.toString(vsSys.getCapacity()));
            // Reactor power allocated to a main system (MainSystem's "power").
            sysElem.setAttribute("power", Integer.toString(vsSys.getPower()));
            sysElem.setAttribute("damage", Integer.toString(vsSys.getDamaged()));
            sysElem.setAttribute("ionTimer", Float.toString(vsSys.getDeionisationTicks() / VanillaSaveFormat.TICKS_PER_SECOND));
            if (vsSys.getIon() > 0) {
                sysElem.setAttribute("ionPowerLimit", Integer.toString(vsSys.getIon()));
            }
            sysElem.setAttribute("repairProgress", Float.toString(vsSys.getRepairProgress() / VanillaSaveFormat.TICKS_PER_SECOND));
            sysElem.setAttribute("damageProgress", Float.toString(vsSys.getDamageProgress() / VanillaSaveFormat.TICKS_PER_SECOND));
            if (xftlName.equals("shields")) {
                sysElem.setAttribute("active", Integer.toString(vs.getShieldsActive()));
                sysElem.setAttribute("rechargeTimer", Float.toString(vs.getShieldsRechargeTicks() / VanillaSaveFormat.TICKS_PER_SECOND));
            }
            if (xftlName.equals("cloaking")) {
                sysElem.setAttribute("cloakingTicks", Float.toString(vs.getCloakTicks() / VanillaSaveFormat.TICKS_PER_SECOND));
            }
        }

        // Crew: replace the default crew with the saved one.
        Element crewList = xml.getChild("crew");
        crewList.removeChildren("crewMember");
        int crewIndex = 0;
        for (VanillaSaveData.VanillaCrew vc : vs.getCrew()) {
            if (vc.isPlayer() != ownCrewIsPlayer) {
                continue; // boarders are placed once both ships exist
            }
            // The original marks every crew member of a ship with a clonebay (the flag is 1 for living crew too), so
            // only a dead one, waiting in the clonebay, is dropped on import.
            if (vc.getHealth() <= 0) {
                continue;
            }
            if (vc.getRoomId() < 0 || vc.getRoomId() >= target.getRooms().size()) {
                continue;
            }
            Room room = target.getRooms().get(vc.getRoomId());
            int sqX = vc.getRoomSquare() % room.getWidth();
            int sqY = vc.getRoomSquare() / room.getWidth();
            float x = Constants.ROOM_SIZE * (room.getX() + target.getOffset().getX() + sqX + 0.5f);
            float y = Constants.ROOM_SIZE * (room.getY() + target.getOffset().getY() + sqY + 0.5f);

            Element crewElem = new Element("crewMember");
            // The deserialiser registers every crew element by oid
            // (SaveUtil.registerObjectId) — freshly built elements need one
            // too, else Ship.loadFromXml dies with "Missing object ID!".
            // Nothing else in the fresh ship's XML references crew oids,
            // so any unique id works.
            crewElem.setAttribute("oid", "vanillacrew:" + crewIndex);
            crewElem.setAttribute("type", vc.getRace());
            crewElem.setAttribute("x", Float.toString(x));
            crewElem.setAttribute("y", Float.toString(y));
            // Hyperspace keeps the exact health (the original's own field is whole points).
            crewElem.setAttribute("health", vc.getHs() != null ? Float.toString(vc.getHs().getHealth()) : Integer.toString(vc.getHealth()));
            crewElem.setAttribute("action", "IDLE");
            crewElem.setAttribute("ownerShip", refs.get(target));
            crewElem.setAttribute("selectedName", vc.getName());
            crewElem.setAttribute("colour", Integer.toString(vc.getColour()));
            for (Map.Entry<String, Integer> skill : vc.getSkills().entrySet()) {
                xyz.znix.xftl.crew.Skill skillEnum = xftlSkillEnum(skill.getKey());
                float progress = skill.getValue() * skillEnum.getAmountPerAction();
                if (progress <= 0) continue;
                Element skillElem = new Element("skill");
                skillElem.setAttribute("id", skillEnum.name());
                skillElem.setAttribute("value", Float.toString(Math.min(progress, 2f)));
                crewElem.addContent(skillElem);
            }
            crewList.addContent(crewElem);
            crewIndex++;
        }

        // Doors: open/closed state string; damage is reset on import.
        Element doorsElem = xml.getChild("doors");
        doorsElem.removeChildren("door");
        StringBuilder doorState = new StringBuilder();
        for (int i = 0; i < target.getDoors().size(); i++) {
            VanillaSaveData.VanillaDoor vd = i < vs.getDoors().size() ? vs.getDoors().get(i) : null;
            doorState.append(vd != null && vd.getOpen() ? 'Y' : 'n');
        }
        doorsElem.getChild("isOpen").setText(doorState.toString());

        // Rooms: drop the fresh state's rooms element and write breaches.
        xml.removeChildren("rooms");
        Element roomsElem = new Element("rooms");
        HashMap<Integer, Element> breachRooms = new HashMap<>();
        for (Triple<Integer, Integer, Integer> breach : vs.getBreaches()) {
            // The original keeps a breach by its square on the ship (the layout's offset included, like the crew's
            // positions); the room and the slot in it follow from the layout.
            int bx = breach.getFirst() - target.getOffset().getX(), by = breach.getSecond() - target.getOffset().getY();
            Room breachRoom = target.getRooms().stream().filter(r -> bx >= r.getX() && bx < r.getX() + r.getWidth() && by >= r.getY() && by < r.getY() + r.getHeight()).findFirst().orElse(null);
            if (breachRoom == null) continue;
            int slot = (by - breachRoom.getY()) * breachRoom.getWidth() + (bx - breachRoom.getX());
            Element roomElem = breachRooms.computeIfAbsent(breachRoom.getId(), k -> {
                Element elem = new Element("room");
                elem.setAttribute("id", k.toString());
                roomsElem.addContent(elem);
                return elem;
            });
            Element breachElem = new Element("breach");
            breachElem.setAttribute("slot", Integer.toString(slot));
            breachElem.setAttribute("health", Float.toString(breach.getThird() / 10f));
            roomElem.addContent(breachElem);
        }
        xml.addContent(roomsElem);

        // Replace (not add to) the fresh ship's levels: the loader reads the first element.
        xml.removeChildren("oxygenLevels");
        Element oxygenElem = new Element("oxygenLevels");
        StringBuilder oxygen = new StringBuilder();
        for (int i = 0; i < target.getRooms().size(); i++) {
            float o2 = i < vs.getRoomOxygen().size() ? vs.getRoomOxygen().get(i) : 1f;
            oxygen.append(o2);
            oxygen.append(' ');
        }
        oxygenElem.setText(oxygen.toString().trim());
        xml.addContent(oxygenElem);

        // Weapons: replace the types on the hardpoints.
        Element weaponsElem = xml.getChild("weapons");
        List<Element> weaponElems = new ArrayList<>(weaponsElem.getChildren("weapon"));
        for (int i = 0; i < weaponElems.size(); i++) {
            Element weaponElem = weaponElems.get(i);
            if (i < vs.getWeapons().size()) {
                weaponElem.setAttribute("type", vs.getWeapons().get(i).getFirst());
                // A nearby ship keeps its armed weapons; the player's are re-armed by hand.
                weaponElem.setAttribute("powered", target == player ? "false" : Boolean.toString(vs.getWeapons().get(i).getSecond()));
                weaponElem.setAttribute("chargeTime", "0");
                weaponElem.removeChildren("extraCharges");
                weaponElem.removeChildren("chainCount");
            } else {
                weaponsElem.removeContent(weaponElem);
            }
        }

        // Drones: write them into the drone control system's element.
        for (Element sysElem : xml.getChild("systems").getChildren("system")) {
            if (sysElem.getAttributeValue("name").equals("drones")) {
                sysElem.removeChildren("drone");
                for (VanillaSaveData.VanillaDrone vd : vs.getDrones()) {
                    Element droneElem = new Element("drone");
                    droneElem.setAttribute("type", vd.getName());
                    sysElem.addContent(droneElem);
                }
            }
        }

        // Augments
        List<Element> augmentElems = new ArrayList<>(xml.getChildren("augment"));
        for (Element augElem : augmentElems) {
            xml.removeContent(augElem);
        }
        for (String aug : vs.getAugments()) {
            Element augElem = new Element("augment");
            augElem.setAttribute("name", aug);
            xml.addContent(augElem);
        }

        // Cargo
        xml.removeChildren("cargoBlueprint");
        for (int index = 0; target == player && index < data.getCargo().size() && index < 4; index++) {
            Element cargoElem = new Element("cargoBlueprint");
            cargoElem.setAttribute("name", data.getCargo().get(index));
            cargoElem.setAttribute("index", Integer.toString(index));
            xml.addContent(cargoElem);
        }

        return deserialiseSingleShip(xml, new RefLoader(), null);
    }

    private static String vanillaSlotForXftlName(String xftlName) {
        for (Map.Entry<String, String> entry : VanillaSaveFormat.INSTANCE.systemSlotEntries()) {
            if (entry.getValue().equals(xftlName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static xyz.znix.xftl.crew.Skill xftlSkillEnum(String vanillaSkill) {
        switch (vanillaSkill) {
            case "pilot": return xyz.znix.xftl.crew.Skill.PILOTING;
            case "engines": return xyz.znix.xftl.crew.Skill.ENGINES;
            case "shields": return xyz.znix.xftl.crew.Skill.SHIELDS;
            case "weapons": return xyz.znix.xftl.crew.Skill.WEAPONS;
            case "repair": return xyz.znix.xftl.crew.Skill.REPAIRS;
            case "combat": return xyz.znix.xftl.crew.Skill.COMBAT;
            default: return xyz.znix.xftl.crew.Skill.valueOf(vanillaSkill.toUpperCase());
        }
    }


    /**
     * Whether Advanced Edition content is enabled - used by the vanilla
     * save writer.
     */
    public boolean isAdvancedEdition() {
        return enableAdvancedEdition;
    }

    /**
     * Quest events delayed to the next sector - used by the vanilla save
     * writer for the distant-quest list, and by the loader to restore them.
     */
    public List<String> getDelayedQuests() {
        return delayedQuests;
    }

    public void addDelayedQuest(String questName) {
        delayedQuests.add(questName);
    }

    public EventCreator getEventCreator() {
        return eventCreator;
    }

    private NativeCrewFactory crewFactory;

    /** Generates crew with the original's draws and tracks the names in use. */
    public NativeCrewFactory getCrewFactory() {
        if (crewFactory == null) {
            crewFactory = new NativeCrewFactory(content.nameManager);
        }
        return crewFactory;
    }

    /**
     * The end of StarMap::GenerateMap (0x5f8ca0): quests that didn't fit the
     * previous sector are created again and placed with the unrestricted
     * rules; those that still fail wait for the next sector while the world
     * level is below six.
     */
    public void placeDelayedQuests(Sector sector) {
        ArrayList<String> pending = new ArrayList<>(delayedQuests);
        delayedQuests.clear();
        if (eventManager.getHs() != null) {
            // Hyperspace's AddQuest (quest options, quest seeds, "QUEST	" names).
            for (String name : pending)
                xyz.znix.xftl.hs.events.HsQuests.INSTANCE.addQuest(this, sector, sector.getStartBeacon(), name, true, delayedQuests);
            return;
        }
        for (String name : pending) {
            Sector.QuestPlacement placement = sector.addQuest(sector.getStartBeacon(), eventCreator, name, true);
            if (placement == Sector.QuestPlacement.NO_SPACE && sector.getSectorNumber() < 6) {
                delayedQuests.add(name);
            }
        }
    }


    private InGameState(MainGame mainGame, GameContent content) {
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.lua.LuaEvents.runEnded();
        this.mainGame = mainGame;
        this.content = content;

        // Load up the convenience variables
        df = content.datafile;
        enableAdvancedEdition = content.enableAdvancedEdition;
        blueprintManager = content.blueprintManager;
        eventManager = content.eventManager;

        // WorldManager::CreateNewGame / LoadGame hooks (StatBoost.cpp): fresh stat boost state per run.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewContent.newRun();
        // Hyperspace custom achievements: the run's difficulty, ship and popups (new and loaded runs).
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsAchievements.bindGame(this);

        // Don't load any assets while running automated tests
        if (isRunningAutomatedTest())
            return;

        pauseFont = getFont("c&c");

        // Load a bunch of images that we'll need, to make them available for later.
        List<SystemBlueprint> systems = blueprintManager.getBlueprints().values().stream()
                .filter(bp -> bp instanceof SystemBlueprint)
                .map(bp -> (SystemBlueprint) bp)
                .collect(Collectors.toList());
        for (SystemBlueprint system : systems) {
            getImg(system.getRoomIconPath());
        }

        for (SystemBlueprint system : systems) {
            getImg("img/icons/s_" + system.getType() + "_red1.png");
            getImg("img/icons/s_" + system.getType() + "_orange1.png");
            getImg("img/icons/s_" + system.getType() + "_grey1.png");
            getImg("img/icons/s_" + system.getType() + "_green1.png");
        }

        updateHotkeyBindings();

        // Issue #4: the beacon-arrival flare (glowing star that sweeps along
        // the hull as the ship scales up).
        jumpFlare = getImg("img/effects/glowing_star.png");
    }

    @Override
    public void init(@NotNull GameContainer container) {
        // Menus/console may open immediately after restoring a combat state,
        // before the first simulation update initializes its input reference.
        input = container.getInput();
        super.init(container);

        // Feed inputs to the debug console.
        container.getInput().addListener(new InputAdapter() {
            @Override
            public void keyPressed(int key, char c) {
                HotkeyButton consoleKey = reverseHotkeyBindings.get(getHotkeyManager().getKeyDevConsole());
                // The debug console blocks all hotkeys
                if (debugConsoleVisible) {
                    boolean shift = container.getInput().isKeyDown(Input.KEY_LSHIFT) || container.getInput().isKeyDown(Input.KEY_RSHIFT);
                    boolean closes = xyz.znix.xftl.devutil.ConsoleGate.closes(key, c, consoleKey == null ? null : consoleKey.getKeyID(), shift);
                    // Escape drops a picker's or the reference's selection first (docs/CONSOLE.md "Keys"), then closes
                    if (closes && key == Input.KEY_ESCAPE && getDebugConsole().escape()) { /* the selection went */ }
                    else if (closes) cancelMenu();
                    else getDebugConsole().keyPressed(key, c);
                    container.getInput().clearInputPressedRecord();
                    return;
                }

                // If the window uses the key for typing, then prevent it
                // from being used as a hotkey too.
                if (shipUI.onTextInput(key, c)) {
                    container.getInput().clearInputPressedRecord();
                    return;
                }
                // The debug console (devutil/ConsoleRegistry.kt, ConsoleGate): with the "Debug console" setting on, ` and \
                // open it in every mode; off, only a Hyperspace mod's <console enabled="true"/> opens it, on HS's console
                // hotkey, as CommandGui::KeyDown's hook does (no focus window open, not jumping).
                HotkeyButton pressedButton = HotkeyButton.BY_KEY_ID.get(key);
                Hotkey pressedBound = pressedButton == null ? null : forwardHotkeyBindings.get(pressedButton);
                if (xyz.znix.xftl.devutil.ConsoleGate.opens(key, pressedBound == null ? null : pressedBound.getId(),
                        !shipUI.isWindowOpen() && !(player != null && player.getBrowserJumping()))) {
                    openDebugConsole();
                    container.getInput().clearInputPressedRecord();
                    return;
                }

                // Check if there's a hotkey associated with this button
                HotkeyButton button = HotkeyButton.BY_KEY_ID.get(key);
                if (button == null) {
                    return;
                }
                Hotkey matching = forwardHotkeyBindings.get(button);
                if (matching == null) {
                    return;
                }

                // And if so, queue it for the next update
                queuedHotkeys.add(matching);
            }

            @Override
            public void mouseWheelMoved(int change) {
                if (debugConsoleVisible) {
                    getDebugConsole().mouseWheelMoved(change);
                    return;
                }

                // Hyperspace Input.cpp: a scrolling tooltip takes the wheel first
                if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.rendering.NativeTooltip.HsScroll.wheel(change)) return;
                shipUI.mouseScroll(change);
            }

            @Override
            public void mousePressed(int button, int x, int y) {
                if (debugConsoleVisible)
                    debugConsole.mousePressed(button, x, y);
            }

            @Override
            public void mouseReleased(int button, int x, int y) {
                if (debugConsoleVisible)
                    debugConsole.mouseReleased(button, x, y);
            }

            @Override
            public void mouseDragged(int oldX, int oldY, int newX, int newY) {
                if (debugConsoleVisible)
                    debugConsole.mouseDragged(oldX, oldY, newX, newY);
            }
        });
    }

    @Override
    public void shutdown() {
        // Our resource context is owned by the game data, so we don't need to free it here.
    }

    private void createNewPlayerShip(String shipName, EditableShip customised) {
        createNewPlayerShip(shipName, customised, createStartingCrew(shipName, null));
    }

    /**
     * The player's starting crew: the hangar's choices, with random crew
     * (name, colours and all) for the rest.
     */
    private List<LivingCrewInfo> createStartingCrew(String shipName, List<StartingCrew> startingCrew) {
        ShipBlueprint blueprint = blueprintManager.getShip(shipName);
        ArrayList<LivingCrewInfo> crew = new ArrayList<>();
        int crewIndex = 0;
        for (ShipBlueprint.InitialCrewSpec spec : blueprint.getInitialCrew()) {
            boolean hs = xyz.znix.xftl.hs.HS.active;
            CrewBlueprint race = hs ? null : blueprintManager.crewOrHuman(spec.getRace());
            for (int i = 0; i < spec.getAmount(); i++) {
                StartingCrew choice = startingCrew == null || crewIndex >= startingCrew.size() ? null : startingCrew.get(crewIndex);
                if (hs) {
                    // Hyperspace: each member's GetCrewBlueprint resolves crew lists and gives unknown races the
                    // human blueprint; a hangar choice keeps the race the hangar resolved.
                    race = choice != null && blueprintManager.getOrNull(choice.getSpecies()) instanceof CrewBlueprint cb ? cb
                            : xyz.znix.xftl.hs.crew.HsCrewContent.crewBlueprint(blueprintManager, spec.getRace(), ftl.browser.NativeRandom.INSTANCE);
                }
                LivingCrewInfo info = choice == null ? LivingCrewInfo.generateRandom(race, this) :
                        new LivingCrewInfo(this, race, choice.getName(), choice.getColour());
                crewIndex++;
                crew.add(info);
            }
        }
        if (xyz.znix.xftl.hs.HS.active)
            hsStartRooms = xyz.znix.xftl.hs.ship.HsShipState.startingCrewRooms(blueprint, crew.size());
        return crew;
    }

    /** Modded run start: the equipment and Hyperspace crew drawn before the sector tree (see the constructor). */
    private xyz.znix.xftl.hs.ship.HsShipStart.Equipment hsStartEquipment;
    private List<xyz.znix.xftl.hs.ship.HsStartingCrewMember> hsStartCrew;
    /** Hyperspace: the room of each blueprint crew member (HsShipState.startingCrewRooms), null in vanilla. */
    private List<Integer> hsStartRooms;

    private void createNewPlayerShip(String shipName, EditableShip customised, List<LivingCrewInfo> startingCrew) {
        ShipBlueprint blueprint = playerBlueprint(blueprintManager.getShip(shipName));
        // Hyperspace: the ShipManagers the hangar built and dropped (their constructor events), before this one
        if (xyz.znix.xftl.hs.HS.active) { Ship before = player; xyz.znix.xftl.hs.crew.HsHangarIds.replay(this, s -> player = s); player = before; }
        player = new Ship(blueprint, this, customised, null, null);
        player.hsStartEquipment = hsStartEquipment;
        hsStartEquipment = null;
        player.loadDefaultContents();
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsShipState.applyStartingResources(player);

        // The systems are built (and powered, ShipSystem ctor 0x642370) before the crew boards: each takes
        // its starting target power from the reactor in creation order; the Zoltans' bonus bars come later
        // on top, and AddSystem's InstantPowerShields (0x49d710) finally raises and charges the shields.
        java.util.Map<MainSystem, Integer> reactorStart = player.browserStartingReactorPower();

        for (int i = 0; i < startingCrew.size(); i++) {
            LivingCrewInfo info = startingCrew.get(i);
            Integer hsRoom = hsStartRooms != null && i < hsStartRooms.size() ? hsStartRooms.get(i) : null;
            if (hsRoom != null && hsRoom >= 0 && hsRoom < player.getRooms().size())
                player.addInitialCrewInRoom(info, player.getRooms().get(hsRoom));
            else
                player.addCrewMember(info, true, false);
        }
        hsStartRooms = null;
        if (hsStartCrew != null) {
            xyz.znix.xftl.hs.ship.HsShipState.placeStartingCrew(player, hsStartCrew);
            hsStartCrew = null;
        }

        // Save the default crew positions, so the player can immediately
        // load them again without first having to save them.
        player.saveCrewPositions();

        // Settling the start power is silent, like the rest of the new run's set-up.
        player.setStartSoundsMuted(true);
        player.browserApplyStartingPower(reactorStart);
        // The drive starts empty (ShipManager::Restart 0x4b1760 clears +0x48c). The opening event pauses the game
        // before WorldManager::OnLoop (0x61b930) runs, which fills the drive (0x49b5b0) while nothing is hostile, so
        // the button shows CHARGING under the event and jumpReady rings on the first frame after it closes.
        player.setStartSoundsMuted(false);
    }

    @Override
    public void update(@NotNull GameContainer container, float delta) throws SlickException {
        input = container.getInput();
        renderingDeltaTime = delta;
        browserCountUpdate();

        // Pictures asked for ahead of time (the next beacon's backdrop) load a little each frame.
        content.getPrefetcher().step(IMAGE_PREFETCH_MS);

        // BlueprintManager +0x10c (0x467dd0) is reset to the player's crew names every frame (WorldManager 0x61b930),
        // so the names generated within a frame are only avoided until the next one.
        eventCreator.refreshUsedNames();

        // ShipManager +0x4d8: a jumping ship keeps its ShipManager::OnLoop (crew, fire, oxygen) but skips
        // every system OnLoop, and every shot at it misses (GetDodged 0x4a00d0). The simulation keeps running
        // through the jump; only the arrival waits while its event is about to open (the original shows
        // PAUSED from the location switch until the event box).
        if (player != null) player.setBrowserJumping(isPlayerJumping());
        if (enemy != null) enemy.setBrowserJumping(enemyJumpOut > 0);
        // A reloaded save can hold a pending arrival event with the animation already over: it still waits.
        boolean arrivalHold = arrivalEventsPending;
        if (!isPaused() && !arrivalHold) {
            updateGameState(delta);
            // Hyperspace WorldManager::OnLoop (CustomEvents.cpp): a <finalBoss> ship event's enemy destroyed.
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.events.HsFinalBoss.onLoop(this);
            // Hyperspace WorldManager::OnLoop (TriggeredEvents.cpp): the triggered events count game time.
            if (eventManager.getHs() != null && shipUI != null)
                xyz.znix.xftl.hs.events.HsTriggeredEvents.INSTANCE.updateAll(this, delta, shipUI.hsChoiceBoxOpen());
        } else {
            // WorldManager::PauseLoop hooks (StatBoost.cpp, CrewAbilities.cpp).
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewContent.pauseLoop(this);
            checkForGameOver();
        }

        // The jump AnimationTracker (ShipManager +0x4a0, 2 s) is advanced from ShipManager::OnRender, so it
        // also plays while paused. When the player's jump-out finishes, the location changes
        // (CommandGui 0x530b10 -> ShipManager::JumpArrive 0x49d490) and the tracker runs in reverse for the
        // arrival. When the enemy's finishes, the ship is removed (ShipAI::Escaped).
        if (playerJumpOut > 0) {
            playerJumpOut = Math.max(0f, playerJumpOut - delta / JUMP_OUT_TIME);
            if (playerJumpOut == 0 && jumpOutTarget != null) {
                Beacon target = jumpOutTarget;
                jumpOutTarget = null;
                setCurrentBeacon(target);
                jumpArrival = true;

                // Auto-save on arrival (after the beacon change has fully
                // resolved), so a force-close can only cost the player the
                // current beacon, like vanilla FTL.
                mainGame.writeRunSave();
            }
        }

        if (enemyJumpOut > 0) {
            enemyJumpOut = Math.max(0f, enemyJumpOut - delta / JUMP_OUT_TIME);
            // A ship destroyed during its jump-out is gone already (its destroyed event shown): nothing escapes.
            if (enemyJumpOut == 0 && enemy == null) enemyJumpEvent = null;
            if (enemyJumpOut == 0 && enemy != null) {
                IEvent jumpEvent = enemyJumpEvent;
                enemyJumpEvent = null;
                Ship gone = enemy;
                // ShipAI::Escaped: the boarders aboard are lost with the ship (KillEveryone(true), no clone).
                xyz.znix.xftl.systems.BoardersLost.shipGone(this, gone, true);
                commandGuiClearLocation();
                // Hyperspace Lua: ships.enemy keeps the escaped ship until the location changes (not looped)
                if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaAdapters.enemyEscaped(this, gone);
                setEnemy(null);
                currentBeacon.setShip(null);
                // WorldManager::OnLoop (0x61c7c1): a ship that got away counts as defeated, like a destroyed one.
                browserProgress.defeatShip();

                if (jumpEvent != null) {
                    showShipEnding(jumpEvent, gone);
                }
            }
        }

        // Tick the beacon-arrival animation; the deferred arrival dialogues
        // only show once it's done (issue #4).
        if (playerFlyIn > 0) {
            playerFlyIn = Math.max(0f, playerFlyIn - delta / ARRIVAL_TIME);
        }
        if (playerFlyIn <= 0) jumpArrival = false;

        // The arrival's own box is already open (hidden, a focus window pausing the game) and shows when the fly-in is
        // over, whether or not the player paused meanwhile (the tutorial's COMBAT_0 asks for a pause right then).
        if (arrivalEventsPending && playerFlyIn <= 0 && !focusPaused) {
            arrivalEventsPending = false;
            arrivalBoxHidden = false;
            if (arrivalShowBeaconEvent) {
                arrivalShowBeaconEvent = false;
                showArrivalEvent();
            }
            if (hsRevisitPending) {
                hsRevisitPending = false;
                if (shipUI != null) shipUI.showHsRevisit(currentBeacon);
            }
            if (arrivalEliteEvent != null) {
                Event eliteEvent = arrivalEliteEvent;
                arrivalEliteEvent = null;
                showEventDialogue(eliteEvent, ftl.browser.BrowserRandom.INSTANCE.nextInt());
            }
        }

        // Hyperspace (TriggeredEvents.cpp, WorldManager::OnLoop/PauseLoop): queued events load while no choice
        // box is open.
        if (eventManager.getHs() != null && shipUI != null && !arrivalEventsPending && !shipUI.hsChoiceBoxOpen()) {
            xyz.znix.xftl.hs.events.HsFlow.INSTANCE.checkEventQueue(this, shipUI.getHsHost(), shipUI::hsChoiceBoxOpen);
        }

        // CommandGui::OnLoop (1.6.9 0x5220b0, 1.6.14 0x536770): while the player's ship is not jumping, the event box
        // opening or closing (the ChoiceBox's bOpen, CommandGui +0x1794, against the last frame's) queues event 13, on
        // which WorldManager's loop autosaves (PrepareAutoSave 1.6.9 0x5f5780: not in the tutorial, not after a game
        // over). The box closing saves in PlayerShipUI.eventDialogueClosed; this is its opening (an arrival's box, the
        // reward box after a fight), so a run that stops while a box is open goes on with it.
        boolean eventOpen = shipUI != null && shipUI.getDiagnosticDialogue() != null;
        if (!isPlayerJumping()) {
            if (eventOpen && !browserEventOpenSeen && mainGame != null && !browserRunEnded) mainGame.writeRunSave();
            browserEventOpenSeen = eventOpen;
        }

        Input in = container.getInput();

        shipUI.updateAlways(delta);

        hoveredRoom = null;

        // Don't touch sounds in automated tests
        if (content.sounds instanceof RealSoundManager sounds) {
            // StarMap::OnLoop (0x5f5720) keeps the distress loop at full volume
            // while the beacon is on and the ship has no fuel, paused or not.
            if (fuelDistressBeacon && player != null && player.getFuelCount() <= 0)
                sounds.setPlayingFuelDistressSound(true);
            sounds.updateLoopedSounds(isPaused());
            sounds.setCombatMusic(isInDanger());
            sounds.updateMusic(delta);
            sounds.setSoundEffectVolume(mainGame.getProfile().getSoundVolume());
            sounds.setMusicVolume(mainGame.getProfile().getMusicVolume());
        }

        if (debugConsoleVisible) {
            getDebugConsole().update(container, delta);
        }

        if (debugConsoleVisible) queuedHotkeys.clear();
        while (!queuedHotkeys.isEmpty()) {
            dispatchHotkey(queuedHotkeys.remove(0), in);
        }

        // Escape is special, it's not a hotkey and can't be rebound (CommandGui::KeyDown: not while the ship jumps).
        if (in.isKeyPressed(Input.KEY_ESCAPE) && (debugConsoleVisible || !keysHeldByJump())) {
            cancelMenu();
        }

        shipUI.updateUI(in.getMouseX(), in.getMouseY(), playerShipOffset);
        boolean rightClicked = false;
        ftl.browser.BrowserTutorialOverlay tutorialOverlay = mainGame == null ? null : mainGame.getTutorialOverlay();

        for (int i = 0; i < 3; i++) {
            boolean prev = mouseDownPrev[i];
            boolean now = in.isMouseButtonDown(i);

            // Block mouse input while the debug console is open, to avoid
            // accidentally clicking on something under the console. The console
            // has the press (its own listener): a press the game had ends for it
            // now, and one the console took stays the console's until it is
            // released, even when the console closes first (one click, one
            // receiver, as HostInput does between screens).
            if (debugConsoleVisible) {
                if (prev && !mouseCaptured[i]) shipUI.mouseUp(i, in.getMouseX(), in.getMouseY(), playerShipOffset);
                mouseCaptured[i] = now;
                mouseDownPrev[i] = now;
                continue;
            }

            if (now && !prev) {
                if (tutorialOverlay != null && tutorialOverlay.click(this, i, in.getMouseX(), in.getMouseY())) {
                    mouseCaptured[i] = true;
                    mouseDownPrev[i] = now;
                    // Exit/completion replaces this state and clears held input.
                    if (mainGame.getCurrentState() != this) return;
                    continue;
                }
                // CommandGui::RButtonDown hands the right press to an open focus window and stops there: it neither
                // drops the armed weapon nor orders the crew aboard the enemy ship under the window.
                // Otherwise CombatControl::MouseRClick (1.6.14 0x581520) drops what is armed, and a press that dropped
                // something goes no further than the weapon and drone slots (see PlayerShipUI.rightPressDisarmed).
                boolean disarmed = false;
                if (i == Input.MOUSE_RIGHT_BUTTON && !shipUI.isWindowOpen()) {
                    disarmed = clickEvent != null;
                    clickEvent = null;
                    rightClicked = !disarmed;
                }

                shipUI.setRightPressDisarmed(disarmed);
                shipUI.mouseClick(i, in.getMouseX(), in.getMouseY(), playerShipOffset);
                shipUI.setRightPressDisarmed(false);
            } else if (prev && !now) {
                if (!mouseCaptured[i]) shipUI.mouseUp(i, in.getMouseX(), in.getMouseY(), playerShipOffset);
                mouseCaptured[i] = false;
            }
            mouseDownPrev[i] = now;
        }

        shipUI.updateUI(in.getMouseX(), in.getMouseY(), playerShipOffset);

        // Figure out when the player right-clicks
        // an enemy room - this is used for controlling boarders.
        if (rightClicked && enemy != null) {
            tempPoint.setX(container.getInput().getMouseX());
            tempPoint.setY(container.getInput().getMouseY());
            convertScreenToEnemyShip(tempPoint);
            enemy.screenPosToShipPos(tempPoint);

            RoomPoint rp = enemy.shipToRoomPos(tempPoint);
            if (rp != null) {
                shipUI.enemyRoomRightClicked(rp.getRoom(), enemy, rp);
            }
        }

        updateClickEvent(container);

        // Hammer away at the serialisation logic, if that flag is set.
        // Only do it while un-paused, since we can't properly use
        // the console if we're constantly reloading.
        if (debugFlags.getContinuousSaveLoad().getSet() && !isPaused()) {
            mainGame.doSaveLoadGame();
        }
    }

    /** Hyperspace MouseControl.bHideMouse: no pointer at all (the browser shell's "none"). */
    @NotNull
    public Cursor getHiddenCursor() {
        Cursor cursor = content.cursors.get("none");
        if (cursor == null) {
            cursor = new Cursor(content.resourceContext, df, df.get("img/mouse/pointerInvalid.png"), null, -1, "none");
            content.cursors.put("none", cursor);
        }
        return cursor;
    }

    @Nullable
    @Override
    public Cursor getCurrentCursor() {
        if (mainGame != null && mainGame.getTutorialOverlay() != null && input != null &&
                mainGame.getTutorialOverlay().hitTest(this, input.getMouseX(), input.getMouseY())) return null;
        Cursor cursor = shipUI.getCurrentCursor();
        // Hyperspace: the pointer a script's MouseControl fields pick this frame
        if (xyz.znix.xftl.hs.HS.luaActive) cursor = xyz.znix.xftl.hs.lua.LuaAdaptersWP7Ui.cursor(this, cursor);
        return cursor;
    }

    private void updateClickEvent(GameContainer container) {
        // The touch HUD picks the room under the finger and clicks it itself.
        if (shipUI.getTouch() != null) {
            hoveredRoom = touchHoverRoom;
            if (hoveredRoom != null && clickEvent != null && !clickEvent.canTargetRoom(hoveredRoom)) hoveredRoom = null;
            return;
        }
        // Handle stuff like weapon targeting where the player
        // highlights a room on the enemy (or their) ship.
        if (clickEvent == null)
            return;
        if (mouseCaptured[Input.MOUSE_LEFT_BUTTON]) return;
        if (mainGame != null && mainGame.getTutorialOverlay() != null &&
                mainGame.getTutorialOverlay().hitTest(this, container.getInput().getMouseX(), container.getInput().getMouseY()))
            return;

        // Hovering over the enemy
        if (enemy != null && enemyInteriorVisible && enemy.getRoomsSelectableByPlayer()) {
            tempPoint.setX(container.getInput().getMouseX());
            tempPoint.setY(container.getInput().getMouseY());
            convertScreenToEnemyShip(tempPoint);
            enemy.screenPosToShipPos(tempPoint);

            RoomPoint rp = enemy.shipToRoomPos(tempPoint);
            if (rp != null)
                hoveredRoom = rp.getRoom();
        }

        // Hovering over the player
        tempPoint.setX(container.getInput().getMouseX());
        tempPoint.setY(container.getInput().getMouseY());
        convertScreenToPlayerShip(tempPoint);
        player.screenPosToShipPos(tempPoint);

        RoomPoint rp = player.shipToRoomPos(tempPoint);
        if (rp != null)
            hoveredRoom = rp.getRoom();

        if (hoveredRoom != null && !clickEvent.canTargetRoom(hoveredRoom)) {
            hoveredRoom = null;
        }

        // CommandGui::LButtonDown hands a press to the open focus window and stops there: weapon targeting
        // (CombatControl, after the windows) never sees a click that went to a window, even one it closed.
        if (hoveredRoom != null && clickEvent != null && mouseDownPrev[Input.MOUSE_LEFT_BUTTON] &&
                !shipUI.windowHasPress(Input.MOUSE_LEFT_BUTTON)) {
            var prev = clickEvent;
            clickEvent = null;
            prev.roomClicked(hoveredRoom, container);
        }
    }

    private void dispatchHotkey(Hotkey key, Input in) {
        // CommandGui::SpaceBar (0x52ce50): nothing while the ship jumps; with a focus window open (menu_pause) only the
        // event box (event_pause) sets the pause, so the game stays paused once the box closes; otherwise a toggle.
        if (key.getId().equals(VanillaHotkeys.PAUSE) && !isShipJumping()) {
            if (!shipUI.isWindowOpen()) paused = !paused;
            else if (shipUI.getDiagnosticPauseWindow() == null && shipUI.getTutorialDialogueOpen()) paused = true;
        }

        boolean shiftPressed = in.isKeyDown(Input.KEY_LSHIFT) || in.isKeyDown(Input.KEY_RSHIFT);
        boolean ctrlPressed = shipUI.isTargetingModifierDown();
        boolean jumpHeld = keysHeldByJump();
        // Hyperspace hotkeys (CommandGui::KeyDown / CombatControl::KeyDown hooks); its speed and info keys come
        // before the original's KeyDown, so they still work while the ship jumps.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsHotkeys.dispatch(this, key, shiftPressed, jumpHeld);
        if (jumpHeld) return;
        // ArmamentControl::KeyDown: each box whose ArmamentHotkey is the key (Hyperspace renumbers them past four weapons).
        if (player != null) {
            for (int i = 0; i < shipUI.armamentBoxCount(true); i++) {
                if (shipUI.armamentHotkey(true, i) == key) shipUI.weaponHotkeyPressed(i, shiftPressed, ctrlPressed);
            }
        }
        if (key.getId().equals(VanillaHotkeys.WEAPON_AUTOFIRE_TOGGLE)) {
            shipUI.toggleAutofire();
        }
        if (player != null) {
            for (int i = 0; i < shipUI.armamentBoxCount(false); i++) {
                if (shipUI.armamentHotkey(false, i) == key) shipUI.droneHotkeyPressed(i, shiftPressed);
            }
        }

        shipUI.hotkeyPressed(key, in);
    }

    /**
     * CommandGui::KeyDown (0x534820) gives the key to the event box, the pause menu or Game Over first, then returns while
     * the player's ship jumps (ShipManager bJumping): no hotkey, no Escape, no window while the jump animation plays.
     * bJumping is set by ShipManager::JumpLeave and cleared only by ShipManager::OnRender (1.6.14 0x4a3c00, 1.6.9
     * 0x49abc0) once the reversed jump animation, the arrival, is done: so the fly-in holds the keys too.
     */
    public boolean keysHeldByJump() {
        return player != null && isShipJumping() && shipUI.getDiagnosticPauseWindow() == null &&
                !shipUI.isGameOverOpen() && shipUI.getDiagnosticDialogue() == null;
    }

    @Override
    public void render(@NotNull GameContainer container, @NotNull Graphics g) throws SlickException {
        g.clear(Colour.black);
        Graphics.setTint(shipUI.getSceneTint());

        g.pushTransform();

        // Hyperspace GUI_CONTAINER render event around CommandGui::RenderStatic (the whole scene and HUD)
        int luaGui = xyz.znix.xftl.hs.HS.luaActive ? xyz.znix.xftl.hs.render.HsRenderEvents.pre(xyz.znix.xftl.hs.lua.LuaApi.RE_GUI_CONTAINER) : 0;
        if (luaGui >= 0) renderStatic(container, g);
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.post(xyz.znix.xftl.hs.lua.LuaApi.RE_GUI_CONTAINER, luaGui);
        renderMouseControl(container, g);
        g.popTransform();
    }

    /** CommandGui::RenderStatic: the scene and HUD (everything but the tooltip). */
    private void renderStatic(@NotNull GameContainer container, @NotNull Graphics g) throws SlickException {
        // CommandGui::RenderStatic (0x535070): the planet and the fleet (not the backdrop) shrink by 0.05 zoom.
        jumpSceneScale = playerJumpOut > 0 || playerFlyIn > 0
                ? 1f - JUMP_PLANET_SHRINK * browserJumpZoom(browserPlayerTrackerTime()) : 1f;
        jumpAsteroidScale = playerJumpOut > 0 || playerFlyIn > 0
                ? 1f - JUMP_ASTEROID_SHRINK * browserJumpZoom(browserPlayerTrackerTime()) : 1f;
        // The touch layout's canvas is not 1280x720: the scene covers it.
        boolean coverCanvas = TouchMode.enabled;
        if (coverCanvas) {
            float cover = Math.max(container.getWidth() / 1280f, container.getHeight() / 720f);
            g.pushTransform();
            g.translate((container.getWidth() - 1280 * cover) / 2, (container.getHeight() - 720 * cover) / 2);
            g.scale(cover, cover);
        }
        // Hyperspace LAYER_BACKGROUND render event around SpaceManager::OnRenderBackground
        // and LAYER_FOREGROUND / LAYER_ASTEROIDS around OnRenderForeground / OnRenderAsteroids(3) (HsRenderEvents)
        if (xyz.znix.xftl.hs.HS.luaActive) {
            if (!ftl.browser.BrowserDebug.parityNoBackground)
                xyz.znix.xftl.hs.render.HsRenderEvents.spaceLayers(currentBeacon.getEnvironment(this), container, g);
        } else if (!ftl.browser.BrowserDebug.parityNoBackground) {
            // Hyperspace background objects go between SpaceManager's three layers (HsBackgroundObjects)
            if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.render.HsBackgroundObjects.hasObjects())
                xyz.znix.xftl.hs.render.HsRenderEvents.spaceLayers(currentBeacon.getEnvironment(this), container, g);
            else
                currentBeacon.getEnvironment(this).renderStack(container, g);
        }
        if (coverCanvas) g.popTransform();

        // Set the position of the player's ship, based on whether or not
        // there's also an enemy ship.
        TouchHud touchHud = shipUI.getTouch();
        if (touchHud != null) {
            touchHud.layOutPlayerShip(playerShipOffset, player);
        } else {
        if (enemy == null) {
            playerShipOffset.setX(350);
        } else if (enemy.isUsingBossUI()) {
            playerShipOffset.setX(150);
        } else {
            playerShipOffset.setX(200);
        }
        playerShipOffset.setY(170);
        // CommandGui::AddEnemyShip (0x5326b0) / ClearLocation (0x532940) add the player layout's HORIZONTAL /
        // VERTICAL shift, vanilla layouts included (Federation B 27 / -17: native capture vf-run).
        playerShipOffset.setX(playerShipOffset.getX() + player.getType().getLayoutShift().getX());
        playerShipOffset.setY(170 + player.getType().getLayoutShift().getY());
        }

        // Hyperspace LAYER_PLAYER render event around CommandGui::RenderPlayerShip
        int luaPlayer = xyz.znix.xftl.hs.HS.luaActive ? xyz.znix.xftl.hs.render.HsRenderEvents.pre(xyz.znix.xftl.hs.lua.LuaApi.RE_LAYER_PLAYER) : 0;
        if (luaPlayer >= 0 && (touchHud == null || !touchHud.getPlayerOnTop())) renderPlayerShip(g);
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.post(xyz.znix.xftl.hs.lua.LuaApi.RE_LAYER_PLAYER, luaPlayer);
        // SpaceManager::OnRenderAsteroids(1) after the player ship while the jump zoom is above zero: the asteroid
        // field's front layer over the ship (Hyperspace: in its LAYER_FRONT render event, with the front-layer
        // background objects)
        if ((playerJumpOut > 0 || playerFlyIn > 0) && browserJumpZoom(browserPlayerTrackerTime()) > 0f) {
            if (xyz.znix.xftl.hs.HS.active) {
                xyz.znix.xftl.hs.render.HsRenderEvents.layerFront(this, container, g);
            } else if (!ftl.browser.BrowserDebug.parityNoBackground) {
                g.pushTransform();
                applyJumpAsteroids(g);
                currentBeacon.getEnvironment(this).renderFrontStack(container, g);
                g.popTransform();
            }
        }
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsBackgroundObjects.renderTooltip(g);

        // CommandGui::RenderStatic (0x00535070) moves the HUD, not the ships, by the screen shake.
        advanceScreenShake();
        g.pushTransform();
        g.translate(shakeX, shakeY);

        if (touchHud != null) touchHud.renderTopButtons(g);

        // CommandGui::RenderStatic (1.6.9 0x520a70, 1.6.14 0x535070) draws CombatControl::OnRenderCombat, which holds
        // the enemy's target window, before the crew, the systems, the weapons and ShipStatus: what a Hyperspace script
        // draws in the SHIP_STATUS event lies over the target window (oracle ps-u5-mvt, Multiverse's tutorial boxes).
        if (enemy != null) {
            // If the enemy ship is neutral but we still have crew inside,
            // allow the user to control and recover them.
            boolean anyLivingBoarders = enemy.hasCrewOwnedByShip(player);
            // ShipManager::OnRender (0x4a3c00) skips the interior of a cloaked ship unless the player has someone aboard.
            enemyInteriorVisible = (enemyIsHostile && !enemy.isCloakActive()) || anyLivingBoarders || enemy.getHasPlayerPresence();

            hostileShipUI.render(container, g, hoveredRoom, enemyInteriorVisible, enemyIsHostile);
        }

        if (touchHud == null) shipUI.render(container, g);
        if (touchHud != null) {
            if (luaPlayer >= 0 && touchHud.getPlayerOnTop()) renderPlayerShip(g);
            shipUI.render(container, g);
        }

        // Draw the paused text before the UI, so the UI goes on top. CommandGui::OnLoop
        // (0x00536770) sets menu_pause while any focus window is open, which shows it too. The new location's
        // event box is already open (hidden) during the arrival animation, so PAUSED shows from the switch.
        if (touchHud == null && (paused || focusPaused || (shipUI.isWindowOpen() && !shipUI.isGameOverOpen()) || arrivalEventsPending)) {
            // CommandGui's pause banner (0x00532a10): Text_pause1 opaque, Text_pause2
            // pulsing over it and the hint in white at alpha 0.6 + 0.3 * pulse. The
            // pulse is a 0-0.75 triangle advanced at 1/s only while the banner shows.
            float pulse = pauseBannerTimer / PAUSE_PULSE_MAX;
            // CommandGui::OnLanguageChange centres the banners' primitives on (640, 558): the languages'
            // own art (img/loc-<language>/) is cut to its letters, so its size differs from the English 256x64.
            Image pauseImg = getImg("img/Text_pause1.png");
            int imgY = container.getHeight() - 162 - pauseImg.getHeight() / 2;
            int imgX = container.getWidth() / 2 - pauseImg.getWidth() / 2;
            pauseImg.draw(imgX, imgY);
            getImg("img/Text_pause2.png").draw(imgX, imgY, pulse);

            String pauseKey = getButtonNameForHotkey(VanillaHotkeys.PAUSE, "<unbound>");
            String pauseText = getTranslator().get("paused_text").replace("\\1", pauseKey);
            pauseFont.drawStringCentred(
                    // printed from y 580 (baseline 590) at 640 in font 5
                    container.getWidth() / 2f, container.getHeight() - 130,
                    0,
                    pauseText, new Colour(1f, 1f, 1f, 0.6f + 0.3f * pulse)
            );

            pauseBannerTimer += pauseBannerDirection * renderingDeltaTime;
            if (pauseBannerTimer > PAUSE_PULSE_MAX) {
                pauseBannerDirection = -1f;
                pauseBannerTimer = 2 * PAUSE_PULSE_MAX - pauseBannerTimer;
            } else if (pauseBannerTimer < 0) {
                pauseBannerDirection = 1f;
                pauseBannerTimer = -pauseBannerTimer;
            }
        }
        g.popTransform();

        // Draw solar flares and pulsar pulses
        Graphics.setTint(shipUI.getStatusTint());
        currentBeacon.getEnvironment(this).overlayStack(container, g);
        Graphics.setTint(Colour.white);

        shipUI.renderMenus(container, g);

        if (mainGame != null && mainGame.getTutorialOverlay() != null) {
            mainGame.getTutorialOverlay().draw(this, g);
        }

        if (debugConsoleVisible) {
            g.setTooltip(null);
            getDebugConsole().render(container, g);
        }

    }

    /** CommandGui::RenderPlayerShip: the player's ship and its own targeting. */
    private void renderPlayerShip(@NotNull Graphics g) {
        // Get the player's ship away from the top UI
        g.pushTransform();
        g.translate(playerShipOffset.getX(), playerShipOffset.getY());

        // Touch weapon-targeting mode: shrink the player's ship about its
        // render origin (playerShipOffset) so the full-size enemy ship is
        // easier to aim at. Screen->ship conversions must divide by the
        // same scale - see convertScreenToPlayerShip().
        float playerShipScale = getPlayerShipRenderScale();
        if (playerShipScale != 1f) {
            g.scale(playerShipScale, playerShipScale);
        }

        // While the jump tracker runs (leaving, or arriving with the tracker in reverse) ShipManager::OnRender
        // (0x4a3c00) draws only the shield bubble, the hull and the flash (Ship.renderJump), and
        // CommandGui::RenderStatic (0x535070) shrinks the ship about its hull centre by 0.75 of the tracker's zoom
        // (browserJumpZoom), which is the first half second of the two.
        if (playerJumpOut > 0 || playerFlyIn > 0) {
            float trackerTime = browserPlayerTrackerTime();
            float scale = 1f - JUMP_SHIP_SHRINK * browserJumpZoom(trackerTime);
            float centreX = player.getHullCentreX();
            float centreY = player.getHullCentreY();
            g.translate(centreX, centreY);
            g.scale(scale, scale);
            g.translate(-centreX, -centreY);
            // Hyperspace background objects of the player layers (CommandGui::RenderPlayerShip hook)
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsBackgroundObjects.renderPlayer(this, g, false);
            player.renderJump(g, browserJumpFade(trackerTime));
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsBackgroundObjects.renderPlayer(this, g, true);
        } else {
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsBackgroundObjects.renderPlayer(this, g, false);
            player.render(g, true, hoveredRoom);
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsBackgroundObjects.renderPlayer(this, g, true);
        }

        player.setRenderAlpha(1f);

        if (player.getWeapons() != null) {
            // Some modded ships don't have a weapons system
            player.renderTargeting(g, player.getWeapons().getSelectedTargets());
        }
        g.popTransform();
    }

    /** MouseControl::OnRender: the tooltip. */
    private void renderMouseControl(@NotNull GameContainer container, @NotNull Graphics g) {
        // Hyperspace MOUSE_CONTROL render event around MouseControl::OnRender (the tooltip), PrintHelper first
        int luaMouse = 0;
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.rendering.NativeTooltip.HsScroll.frame();
            xyz.znix.xftl.rendering.NativeTooltip.HsScroll.images = this::getImg;
        }
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsOverlay.versionLabel(content);
        xyz.znix.xftl.hs.render.HsConsole.drawSquishy(this);
        if (xyz.znix.xftl.hs.HS.luaActive) { xyz.znix.xftl.hs.lua.LuaPrint.render(); luaMouse = xyz.znix.xftl.hs.render.HsRenderEvents.pre(xyz.znix.xftl.hs.lua.LuaApi.RE_MOUSE_CONTROL); }
        // Render the tooltip: a script's MouseControl tooltip of this frame (or its hidden mouse) in place of the engine's
        boolean luaTooltip = luaMouse >= 0 && xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaAdaptersWP7Ui.renderTooltip(g);
        ITooltipProvider tooltip = luaMouse >= 0 && !luaTooltip ? g.getTooltip() : null;
        if (tooltip != null) {
            Input in = container.getInput();

            if (shipUI != null && shipUI.getPowerPopupOpen()) {
                // While the touch power popup is open, the tooltip would
                // otherwise draw at the tap position and cover the +/-
                // buttons - put it at the right-middle of the screen.
                tooltip.drawTooltip(g,
                        container.getWidth() - 220, container.getHeight() / 2,
                        tooltip != lastFameTooltip,
                        container.getWidth(), container.getHeight()
                );
            } else {
                tooltip.drawTooltip(g,
                        in.getMouseX(), in.getMouseY(),
                        tooltip != lastFameTooltip,
                        container.getWidth(), container.getHeight()
                );
            }
        }
        lastFameTooltip = tooltip;
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.post(xyz.znix.xftl.hs.lua.LuaApi.RE_MOUSE_CONTROL, luaMouse);
    }

    /**
     * CommandGui::OnLoop (0x00536770) reads the player's last hull damage (ShipManager +0x49c): any hit sets
     * the shake to damage / 5, at most 1.
     */
    public void shakeForHullDamage(int damage) {
        if (damage != 0)
            shakeMagnitude = Math.min(damage / 5f, 1f);
    }

    /** An ASB shot (SpaceManager +0x388, set at 0x0041dd77) raises the shake to at least 0.05. */
    public void shakeForAsbShot() {
        shakeMagnitude = Math.min(Math.max(shakeMagnitude, 0.05f), 1f);
    }

    /**
     * The screen shake offset, once per rendered frame (0x005313d0). While the magnitude lasts every frame
     * picks a target of rand % 6 - 3 px per axis and moves a fifth of the way there; afterwards the offset
     * moves back a tenth of the way to 0 each frame. Passing the target lands on it. The magnitude drains
     * by 0.0625 per 1/16 s, so a full shake lasts a second.
     */
    private void advanceScreenShake() {
        if (shakeMagnitude <= 0f) {
            if ((int) shakeX == (int) shakeTargetX && (int) shakeY == (int) shakeTargetY) {
                shakeX = shakeTargetX;
                shakeY = shakeTargetY;
                shakeVelX = 0f;
                shakeVelY = 0f;
            } else {
                shakeTargetX = 0f;
                shakeTargetY = 0f;
                shakeVelX = (shakeTargetX - shakeX) / 10f;
                shakeVelY = (shakeTargetY - shakeY) / 10f;
            }
        } else {
            // Drawn per rendered frame, so it takes VisualRandom: the simulation's streams must not depend on the
            // frame rate.
            shakeTargetX = xyz.znix.xftl.UtilsKt.getVisualRandom().nextInt(6) - 3;
            shakeTargetY = xyz.znix.xftl.UtilsKt.getVisualRandom().nextInt(6) - 3;
            shakeVelX = (shakeTargetX - shakeX) / 5f;
            shakeVelY = (shakeTargetY - shakeY) / 5f;
        }

        float oldX = shakeX;
        float oldY = shakeY;
        shakeX += shakeVelX;
        shakeY += shakeVelY;
        shakeMagnitude -= renderingDeltaTime * 16f * 0.0625f;

        boolean passedX = (oldX < shakeTargetX && shakeTargetX <= shakeX) || (shakeTargetX < oldX && shakeX <= shakeTargetX);
        boolean passedY = (oldY < shakeTargetY && shakeTargetY <= shakeY) || (shakeTargetY < oldY && shakeY <= shakeTargetY);
        if (passedX || passedY) {
            shakeX = shakeTargetX;
            shakeY = shakeTargetY;
        }
    }

    /** The HUD's current shake offset, for the parity checks. */
    public float[] getScreenShakeOffset() {
        return new float[]{shakeX, shakeY, shakeMagnitude};
    }

    /**
     * Update the in-game state.
     * <p>
     * This is only public so that automated tests can call it - it should
     * NOT be called elsewhere from the main code.
     */
    public void updateGameState(float dt) {
        // CommandGui::OnLoop (1.6.14 0x536770) takes the player's ShipManager::GetLastDamage (0x49c8a0: read, then 0)
        // once a frame after the world's loop, so a hull number left there lasts only the frame of its hit
        player.nativeLastDamage = 0;
        if (!isInDanger()) player.browserSetSafe();
        // WorldManager::OnLoop hook (StatBoost.cpp): the stat boosts are gathered before the world updates.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.stat.StatBoostManager.onLoop(this);
        browserAchievements.advanceTime(dt);
        currentBeacon.getEnvironment(this).updateStack(dt);
        // Hyperspace SpaceManager::OnLoop hook: the background objects
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsBackgroundObjects.update(this, dt);
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsPdsSmoke.INSTANCE.update(this, dt);

        if (shipUI != null)
            shipUI.update();

        xyz.znix.xftl.drones.DroneRuntime.spaceLoop(this, dt);
        xyz.znix.xftl.weapons.ProjectileRuntime.advanceFrame(this, dt);
        xyz.znix.xftl.drones.DroneRuntime.afterProjectiles(this);
        player.update(dt);
        browserAchievements.update();

        updatePlayerCrew();

        checkForGameOver();

        // The destroyed enemy's CompleteShip keeps looping while the player stays at its beacon (native 1.6.14
        // drone-order probe: 760+ frames after the kill, its unpowered drones still in SpaceManager's loop).
        Ship browserRetained = getBrowserRetainedEnemy();
        if (enemy == null && browserRetained != null) browserRetained.update(dt);

        if (enemy != null) {
            enemy.update(dt);
            browserAchievements.updateEnemy();

            // Since we try and limit direct communications between
            // the ships, pass information about the cloak status back
            // and forth here.
            player.setOpponentCloakActive(enemy.isCloakActive());
            enemy.setOpponentCloakActive(player.isCloakActive());

            if (enemyIsHostile) {
                enemyAI.update(dt);
                if (enemy.getBoss() != null && !enemy.isDead()) {
                    enemy.getBoss().updateShip(enemy, dt);
                } else if (enemy.getBoss() != null) {
                    enemy.getBoss().updateDyingShip(enemy);
                }
                // Hyperspace super drones (CustomBoss.cpp): launched by the flagship or by events, from either ship.
                if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsBoss.INSTANCE.update(this, dt);
            } else {
                // ShipAI::OnLoop (0x625c50): a ship that isn't hostile stops fighting.
                enemyAI.updateIdle();
            }

            // The player's DroneSystem (ShipManager loop, before the enemy's) sees the destruction done on the frame
            // after the enemy's last explosion loop: native recall at +181 frames, one after Ship.DESTRUCTION_TIME.
            boolean browserGoneNow = enemy.isGone() && browserEnemyGoneSeen;
            if (enemy.isGone()) browserEnemyGoneSeen = true;
            if (browserGoneNow) {
                browserEnemyGoneSeen = false;
                // WorldManager::OnLoop (0x61b930): ShipAI::Destroyed, then the boarders aboard die (KillEveryone(false)).
                xyz.znix.xftl.systems.BoardersLost.shipGone(this, enemy, false);
                commandGuiClearLocation();
                if (enemyIsHostile) {
                    browserAchievements.hullDefeat();
                    browserProgress.defeatShip();
                }
                // Be sure to check if the enemy is still hostile, if we've
                // already killed their crew we can't get a second lot of
                // rewards if they later blow up.
                if (enemy.getSpec() != null && enemyIsHostile) {
                    IEvent event = enemy.getSpec().getDestroyed();
                    if (event != null)
                        showShipEnding(event, enemy);
                }

                if (enemy.getBoss() != null) {
                    enemy.getBoss().bossShipKilled(enemy);
                }

                browserDestroyedEnemy = enemy;
                browserDestroyedEnemyBeacon = currentBeacon;
                setEnemy(null);
                currentBeacon.setShip(null);

                // At this point enemy may be null if they
                // were destroyed, so we can't use that any more.
                // Just returning here keeps things simple.
                return;
            }

            Float escapeTimer = enemy.getEscapeTimer();
            if (escapeTimer != null && escapeTimer <= 0f && enemyJumpOut <= 0) {
                // Play the jump-away animation; the update() enemyJumpOut
                // machine removes the ship (and shows the got-away event)
                // once it finishes (issue #4).
                hostileShipUI.startFlyOut();
                enemyJumpEvent = enemy.getSpec() == null ? null : enemy.getSpec().getGotaway();
                enemyJumpOut = 1f;
                // The escape is the enemy's ShipManager::JumpLeave: its systems jump (the cloak drops) as it leaves.
                enemy.browserJumpLeave();

                return;
            }

            // Check if the enemy crew is dead (including any aboard the player ship).
            // Note we check the crew owners, so that mind-controlling the last
            // crew doesn't break it.
            checkCrewKillDefeat();

            // WorldManager::OnLoop: boarders left on a ship that is no longer hostile, with no crew at home and the
            // teleporter destroyed, come home with CREW_STUCK (not in a loop that showed an ending event).
            xyz.znix.xftl.crew.CrewStuck.check(this, shipUI != null && shipUI.isWindowOpen());
        } else {
            player.setOpponentCloakActive(false);
        }

        if (!isInDanger()) {
            // If the player isn't fighting a ship, there are no borders (not yet implemented), and they're not
            // in a dangerous environment (eg, an asteroid field) then let them jump instantly.
            player.setFtlChargeProgress(1);
        }
    }

    // Issue #4: when arriving at a beacon the game pauses while the ship
    // materialises: a glowing star sweeps from bow to stern as the ship
    // fades in, then it smoothly scales up to full size. playerFlyIn goes
    // from 1 (just arrived) to 0 (done).
    private float playerFlyIn = 0f;
    /** The fly-in running is the arrival of a jump (see [isShipJumping]). */
    private boolean jumpArrival = false;
    private boolean arrivalEventsPending = false;
    /** The pending arrival's box was opened at the location switch (not the run's first box, not a loaded save's). */
    private boolean arrivalBoxHidden = false;

    // The screen shake (CommandGui +0x2fdc and 0x005313d0's statics) - cosmetic, never serialised.
    private float shakeMagnitude = 0f;
    private float shakeX = 0f, shakeY = 0f;
    private float shakeTargetX = 0f, shakeTargetY = 0f;
    private float shakeVelX = 0f, shakeVelY = 0f;
    private boolean arrivalShowBeaconEvent = false;

    /** Hyperspace: arriving at a beacon whose event is cleared runs CreateLocation's revisit path. */
    private boolean hsRevisitPending = false;
    private Event arrivalEliteEvent;
    public Image jumpFlare;   // issue #4: shared by the arrival and jump-away animations
    private static final float ARRIVAL_TIME = 2.0f;

    // The reverse animation when the player jumps away (issue #4): the ship
    // scales down while the star sweeps back (stern -> bow) and it fades
    // out. When it finishes, the beacon actually changes.
    private float playerJumpOut = 0f;
    private Beacon jumpOutTarget;
    private static final float JUMP_OUT_TIME = 2.0f;

    /** Milliseconds of each frame that go to loading images ahead of time (see ImagePrefetcher). */
    private static final double IMAGE_PREFETCH_MS = 3.0;

    /** ShipManager's jump AnimationTracker length (set in ShipManager::OnInit 0x4a09f0), out and back. */
    public static final float JUMP_TRACKER_TIME = 2.0f;

    /** CommandGui::RenderStatic (0x535070): the player's ship is drawn at 1 - 0.75 zoom while the jump tracker runs. */
    private static final float JUMP_SHIP_SHRINK = 0.75f;

    /** CommandGui::RenderStatic: the planet and the fleet are drawn at 1 - 0.05 zoom about the ship's hull centre. */
    private static final float JUMP_PLANET_SHRINK = 0.05f;

    /**
     * CommandGui::RenderStatic (1.6.14 0x535070): SpaceManager::OnRenderAsteroids, the field's three layers and the
     * front pass over the ship, is drawn at 1 - 0.3 zoom (exe data 0x87d5b4) about the hull centre.
     */
    private static final float JUMP_ASTEROID_SHRINK = 0.3f;

    /**
     * The zoom CommandGui::RenderStatic (0x535070) derives from the player's jump tracker (progress p = time / 2 s):
     * 4 p over the first quarter, then 1, so the ship has shrunk after half a second and stays small until
     * the last half second of the arrival, when it grows back.
     */
    public static float browserJumpZoom(float trackerTime) {
        float progress = trackerTime / JUMP_TRACKER_TIME;
        return progress > 0.25f ? 1f : 4f * progress;
    }

    /** The player's jump tracker time: forward while leaving, backwards while arriving. */
    public float browserPlayerTrackerTime() {
        return playerJumpOut > 0 ? (1f - playerJumpOut) * JUMP_OUT_TIME : playerFlyIn * ARRIVAL_TIME;
    }

    /** How much of the zoom the planet and fleet scene gets this frame, set in render() for the environment. */
    private float jumpSceneScale = 1f;

    /** The scale of the planet and fleet while the player's ship jumps (1 otherwise), about the hull centre. */
    public float getJumpSceneScale() { return jumpSceneScale; }

    /** The scale of the asteroid field's layers while the player's ship jumps (1 otherwise), about the hull centre. */
    private float jumpAsteroidScale = 1f;

    /** Scales what the caller draws next by the jump's asteroid scale about the hull centre; the caller pushes the matrix. */
    public void applyJumpAsteroids(Graphics g) {
        if (jumpAsteroidScale == 1f) return;
        float centreX = getJumpZoomCentreX(), centreY = getJumpZoomCentreY();
        g.translate(centreX, centreY);
        g.scale(jumpAsteroidScale, jumpAsteroidScale);
        g.translate(-centreX, -centreY);
    }

    /** Scales what the caller draws next by the jump's planet scale about the hull centre; the caller pushes the matrix. */
    public void applyJumpScene(Graphics g) {
        if (jumpSceneScale == 1f) return;
        float centreX = getJumpZoomCentreX(), centreY = getJumpZoomCentreY();
        g.translate(centreX, centreY);
        g.scale(jumpSceneScale, jumpSceneScale);
        g.translate(-centreX, -centreY);
    }

    /** Where the hull centre is on screen, the origin of the jump zoom. */
    public float getJumpZoomCentreX() { return playerShipOffset.getX() + player.getHullCentreX(); }
    public float getJumpZoomCentreY() { return playerShipOffset.getY() + player.getHullCentreY(); }

    /**
     * ShipManager::OnRender (0x4a3c00) turns the jump tracker time into the ship's fade: drawn normally for
     * the first second, fading over the next half second and gone for the last half. The arrival runs the
     * tracker backwards, so it is gone for half a second, fades in, then has a second left in place.
     */
    public static float browserJumpFade(float trackerTime) {
        if (trackerTime < 1f) return 0f;
        if (trackerTime > 1.5f) return 1f;
        return 2f * (trackerTime - 1f);
    }

    /**
     * Ship::OnRenderJump (0x48cad0) draws the hull at alpha 1 - fade and the jump flash (the 64 px glowing star)
     * at progress p = fade / 0.75, so the flash is over a quarter of the fade before the hull is gone.
     */
    public static float browserJumpFlareProgress(float fade) {
        return fade / 0.75f;
    }

    /** The flash's size in ship pixels at progress p: 64 + 1200 p up to p = 0.5, then 1200 (1 - p); none outside (0, 1). */
    public static float browserJumpFlareSize(float progress) {
        if (progress <= 0f || progress >= 1f) return 0f;
        return progress < 0.5f ? 64f + 1200f * progress : 1200f * (1f - progress);
    }

    // Same animation for an enemy ship jumping away mid-combat.
    private float enemyJumpOut = 0f;

    /** The event box was open at the last frame the player's ship was not jumping (CommandGui +0x427a; see update). */
    private boolean browserEventOpenSeen;

    // The out-of-fuel distress beacon toggle (StarMap +0x39c): it outlives
    // the map window and only the toggle changes it.
    private boolean fuelDistressBeacon = false;

    public boolean getFuelDistressBeacon() { return fuelDistressBeacon; }

    public void setFuelDistressBeacon(boolean on) { fuelDistressBeacon = on; }
    private IEvent enemyJumpEvent;

    /**
     * How much the player's ship is drawn shrunken by while the touch
     * weapon-targeting mode is active (see getPlayerShipRenderScale).
     */
    private static final float TARGETING_SHIP_SCALE = 0.7f;


    public void setCurrentBeacon(Beacon currentBeacon) {
        boolean beaconChanged = this.currentBeacon == null || this.currentBeacon != currentBeacon;
        if (beaconChanged && this.currentBeacon != null) {
            // Leaving the beacon ends the no-fuel wait.
            this.currentBeacon.getSector().setWaitSeed(null);
        }
        boolean achievementJump = beaconChanged && this.currentBeacon != null && !isCurrentlyLoadingSave;
        if (achievementJump && player != null) {
            for (AbstractCrew crew : player.getCrew()) {
                if (crew instanceof LivingCrew living && living.getOwnerShip() == player) {
                    living.getInfo().getBrowserCounters().increment(ftl.browser.CrewRecordKind.JUMPS);
                    if (mainGame != null) mainGame.getProfile().updateCrewRecords(living.getInfo());
                }
            }
        }
        arrivalEventsPending = false;
        arrivalBoxHidden = false;
        arrivalShowBeaconEvent = false;
        hsRevisitPending = false;
        arrivalEliteEvent = null;

        if (this.currentBeacon == null || this.currentBeacon.getSector() != currentBeacon.getSector()) {
            SectorType sectorType = currentBeacon.getSector().getType();
            lootPool = new LootPool(blueprintManager, sectorType);

            // Switch to the new sector's music
            loadSectorMusic(sectorType);

            // Distraction Buoys (FLEET_DISTRACTION, 0x619fc0): entering one
            // of the first seven sectors delays the fleet by one jump.
            Sector newSector = currentBeacon.getSector();
            // StarMap::pursuitDelay (+0x498) is the map's, not the sector's: only ModifyPursuit, the danger zone's
            // step and LoadGame write it (1.6.14 0x5edbe0/0x5f5280/0x5fb9a0; GenerateMap 0x5f8ca0 and
            // AdvanceWorldLevel 0x5edec0 leave it; Hyperspace zeroes it only in NewGame), so the new sector starts
            // with what the old one had left (oracle ps-w1-ops: pursuit 2 across the secret sector warp).
            if (this.currentBeacon != null && !isCurrentlyLoadingSave) {
                newSector.setFleetAdvanceModifier(this.currentBeacon.getSector().getFleetAdvanceModifier());
            }
            if (this.currentBeacon != null && !isCurrentlyLoadingSave && player != null
                    && newSector.getSectorNumber() <= 6
                    && player.hasAugment(xyz.znix.xftl.augments.AugmentBlueprint.FLEET_DISTRACTION)) {
                newSector.setFleetAdvanceModifier(newSector.getFleetAdvanceModifier() - 1);
            }
        }

        // ClearLocation (0x614d00, from 0x619fc8 on a jump): leaving wipes the beacon's event and its hazard,
        // unless an enemy ship stays there (the unforced ClearEvent is skipped then).
        if (beaconChanged && this.currentBeacon != null && !isCurrentlyLoadingSave) {
            // The scene goes with the location, whether or not its event survives: the next arrival builds a new one.
            clearLocation();
        }

        boolean hsSectorJump = this.currentBeacon != null && this.currentBeacon.getSector() != currentBeacon.getSector();
        this.currentBeacon = currentBeacon;

        // WorldManager::CreateLocation (0x6183a0) starts with the scene: SwitchBackground, SwitchPlanet and SetDangerZone
        // draw from the game's stream on the first arrival (a revisit applies the stored picks).
        if (!isCurrentlyLoadingSave) {
            xyz.znix.xftl.environment.BeaconScene.arrive(this, currentBeacon);
        }

        // Keep track of which sectors we visit, to show on the sector map UI.
        GameMap.SectorInfo sectorInfo = currentBeacon.getSector().getInfo();
        if (!visitedSectors.contains(sectorInfo)) {
            visitedSectors.add(sectorInfo);
        }

        setEnemyIsHostile(true);
        setEnemy(currentBeacon.getShip());

        // This is called for the first beacon before the player is created.
        if (player != null) {
            // Make the store button appear and disappear.
            shipUI.updateButtons();

            // Cancel pending weapon targeting - the fight it belonged to
            // doesn't survive the jump (GitHub issue #55).
            if (shipUI != null) {
                shipUI.onJump();
            }

            player.resetAfterJump();
            if (currentBeacon.getState() == Beacon.State.UNVISITED) {
                // Deferred until the arrival animation finishes (issue #4).
                arrivalShowBeaconEvent = true;
            } else if (eventManager.getHs() != null && beaconChanged && currentBeacon.getEventCleared()) {
                hsRevisitPending = true;
                // Hyperspace: arriving again runs the beacon's event resources once more, and a system upgrade in it
                // is applied again unless upgradeRevisitFix is on (oracle hg-upg-off: shields 3 -> 4 on the revisit).
                if (!xyz.znix.xftl.hs.HS.cfg().getUpgradeRevisitFix())
                    applyEventUpgrades(currentBeacon.getCreatedEvent().getTemplate().getSystemUpgrades());
            }
        }

        // Spawn in a new rebel elite every jump for overtaken beacons
        if (currentBeacon.getState() == Beacon.State.OVERTAKEN && currentBeacon.getHasFleetEvent()) {
            // The fleet event was created when the fleet took the beacon (StarMap 0x5f4ca0).
            arrivalShowBeaconEvent = true;
        } else if (currentBeacon.getState() == Beacon.State.OVERTAKEN) {
            // Overtaken in an older save: build the encounter now.
            // See doc/sector-map for information on these events

            String eventName;

            if (currentBeacon.isExit()) {
                eventName = "FLEET_EASY_BEACON";
                if (difficulty != Difficulty.EASY) {
                    // This is correct - use _DLC for non-easy difficulties!
                    eventName += "_DLC";
                }
            } else {
                eventName = "FLEET_EASY";

                if (currentBeacon.isMapNebula()) {
                    eventName += "_NEBULA";
                } else if (enableAdvancedEdition) {
                    eventName += "_DLC";
                }
            }

            Event event = eventManager.get(eventName).resolve();
            arrivalEliteEvent = event;
        }

        // If the flagship is here, spawn it in. Doing this last overwrites
        // the rebel elite that's already been spawned.
        boolean bossHere = currentBeacon.getSector().getBosses().stream()
                .anyMatch(thisBoss -> thisBoss.getBeacon() == currentBeacon);
        if (bossHere) {
            arrivalShowBeaconEvent = false;
            hsRevisitPending = false;
            arrivalEliteEvent = null;
        }
        trySpawnBoss();

        if (achievementJump) browserAchievements.onJump();
        if (!isCurrentlyLoadingSave && !currentBeacon.getVisited()) browserProgress.visit(currentBeacon.getSector().getSectorNumber());
        currentBeacon.setVisited(true);
        if (eventManager.getHs() != null && beaconChanged && !isCurrentlyLoadingSave) {
            // WorldManager::CreateLocation (TriggeredEvents.cpp, before the original): triggered events counting arrivals.
            xyz.znix.xftl.hs.events.HsTriggeredEvents.INSTANCE.jumpAll(this, 0);
            // Location::visited counts the arrivals; HS seeds revisit events with it.
            currentBeacon.hsVisits++;
            if (xyz.znix.xftl.hs.sector.HsSeeds.enabled) {
                // Seeds.cpp GetNewLocation: the quest seed follows the location. A jump to the next sector runs it
                // before the new sector exists, so its start beacon gets the dummy location's seed.
                if (hsSectorJump) {
                    xyz.znix.xftl.hs.sector.HsSeeds.INSTANCE.onSectorJump(currentBeacon.getSector().getInfo().getSecretOrigin() != null);
                } else {
                    xyz.znix.xftl.hs.sector.HsSeeds.questSeed = xyz.znix.xftl.hs.sector.HsSeeds.INSTANCE.questSeedFor(currentBeacon.getPos().getX(), currentBeacon.getPos().getY());
                }
            }
        }

        // Fly the ship in, like vanilla does when arriving at a beacon
        // (this includes the first beacon of a run and loading a game).
        // Fly the ship in, like vanilla does when arriving at a beacon
        // (this includes the first beacon of a run and loading a game).
        if (beaconChanged) {
            playerFlyIn = 1f;

            // The jump arrival (0x49d490) plays jumpArrive after a jump;
            // starting or continuing a run is silent.
            if (achievementJump) {
                FTLSound arrive = getSounds().getSampleOrWarn("jumpArrive");
                if (arrive != null) {
                    arrive.play();
                }
            }

            arrivalEventsPending = arrivalShowBeaconEvent || arrivalEliteEvent != null || hsRevisitPending;
            arrivalBoxHidden = false;

            // WorldManager::CreateLocation loads the beacon's event at the location switch, its effects and script
            // callbacks included (oracle sw4-arrive: PRE_CREATE_CHOICEBOX on the switch frame, before the fly-in); the box
            // stays hidden and the game paused until the fly-in is over (PlayerShipUI.arrivalHidden).
            if (arrivalEventsPending && shipUI != null && !isCurrentlyLoadingSave) {
                if (arrivalShowBeaconEvent) {
                    arrivalShowBeaconEvent = false;
                    showArrivalEvent();
                    arrivalBoxHidden = true;
                }
                if (hsRevisitPending) {
                    hsRevisitPending = false;
                    shipUI.showHsRevisit(currentBeacon);
                    arrivalBoxHidden = true;
                }
            }
        }
    }

    /**
     * Play the jump-out animation (issue #4), then switch to the target
     * beacon - where the arrival animation plays. Used when the player
     * jumps to a beacon from the star map.
     */
    @Nullable public String progressionShipName() { return player == null ? null : player.getType().getName(); }

    /** The beacon a star-map jump is flying to, until the jump-out ends (null otherwise). */
    @Nullable public Beacon getJumpOutTarget() { return jumpOutTarget; }

    public void beginJumpOut(Beacon target) {
        // ShipManager::JumpLeave (0x49c9c0) runs for every jump that leaves the beacon - a star map jump, the
        // sector exit and the secret sector warp alike: it takes one fuel (floored at 0).
        int hsOldFuel = player.getFuelCount();
        player.setFuelCount(Math.max(0, player.getFuelCount() - 1));
        // Hyperspace FTL_JUMPER_GOOD (ShipManager::JumpLeave hook): the jump costs the hovered beacon's fuel.
        xyz.znix.xftl.hs.ship.HsNavAugments.jumpLeave(player, hsOldFuel);
        player.browserJumpLeave();
        // Hyperspace ShipManager::JumpLeave (player): triggered events counting departures.
        if (eventManager.getHs() != null) xyz.znix.xftl.hs.events.HsTriggeredEvents.INSTANCE.jumpAll(this, 1);
        // If an animation is still in progress, switch immediately.
        if (playerFlyIn > 0 || playerJumpOut > 0 || enemyJumpOut > 0) {
            setCurrentBeacon(target);
            mainGame.writeRunSave();
            return;
        }

        jumpOutTarget = target;
        playerJumpOut = 1f;

        // The arrival draws the target's backdrop and planet on its first frame; load them during the jump instead.
        content.getPrefetcher().requestAll(xyz.znix.xftl.environment.EnvironmentAssets.INSTANCE.paths(this, target), true);
        content.getPrefetcher().requestAll(xyz.znix.xftl.environment.EnvironmentAssets.INSTANCE.warmPaths(), false);

        // The jump-away whoosh (issue #16).
        FTLSound leave = getSounds().getSampleOrWarn("jumpLeave");
        if (leave != null) {
            leave.play();
        }
    }

    /** A jump-out animation that has not landed yet: the fuel is already spent but the run still sits at the old beacon. */
    public boolean browserJumpPending() {
        return jumpOutTarget != null && playerJumpOut > 0;
    }

    /**
     * Save and close: completes a jump in progress at once, so the saved run holds the new beacon. The native save
     * (WorldManager 0x61a3a0) starts by finishing the pending location change (0x619fc0 with its force flag set)
     * before it writes anything. Returns whether a jump was completed.
     */
    public boolean browserSettleForSave() {
        if (!browserJumpPending()) return false;
        Beacon target = jumpOutTarget;
        jumpOutTarget = null;
        playerJumpOut = 0f;
        setCurrentBeacon(target);
        return true;
    }

    public void browserEnterSecretSector() {
        if (isCurrentlyLoadingSave || currentBeacon.getSector().getInfo().isSecretSector()) return;
        String hsWarp = eventManager.getHs() != null ? xyz.znix.xftl.hs.events.HsFlow.INSTANCE.secretSectorWarp(this) : null;
        GameMap.SectorInfo info = hsWarp != null
                ? gameMap.createSecretSector(currentBeacon.getSector().getInfo(), hsWarp)
                : gameMap.createSecretSector(currentBeacon.getSector().getInfo());
        Sector sector = gameMap.generateSector(info, this);
        // The warp target (StarMap +0x714) goes through CommandGui::OnLoop's pending jump like a map jump:
        // ShipManager::JumpLeave takes the fuel and the jump animation plays before the arrival.
        browserAchievements.enterSecretSector();
        ftl.browser.BrowserDebug.record("event", "sector.secret", "{\"type\":\"" + info.getType().getName() + "\",\"sector\":" + sector.getSectorNumber() + "}");
        beginJumpOut(sector.getStartBeacon());
    }

    public boolean browserSpawnBossHere() {
        if (currentBeacon.getSector().getBosses().stream().noneMatch(boss -> boss.getBeacon() == currentBeacon)) return false;
        trySpawnBoss();
        return true;
    }

    private void trySpawnBoss() {
        BossManager boss = currentBeacon.getSector().getBosses().stream()
                .filter(thisBoss -> thisBoss.getBeacon() == currentBeacon)
                .findFirst().orElse(null);

        // No boss at this beacon?
        if (boss == null) {
            return;
        }

        // If there's a ship at the current beacon, get rid of it. This avoids
        // it sticking around after the flagship leaves, and saves space
        // in the serialised game.
        // Note the flagship isn't marked as belonging to a beacon, since it's
        // generated whenever the player jumps there.
        currentBeacon.setShip(null);

        setEnemy(boss.createShip());
        setEnemyIsHostile(true);
    }

    /**
     * Switch to using a new sector's music.
     * <p>
     * This switches to the start of the first track in this sector, so it
     * shouldn't be called unless there's a new sector.
     */
    private void loadSectorMusic(SectorType type) {
        getSounds().switchMusicList(getSectorTracks(type));
    }

    /**
     * Resolve a sector's soundtrack names into their MusicSpecs.
     */
    // StartPlaylist takes the sector's track names as they are: a name sounds.xml lacks plays silence when its turn comes.
    private List<MusicSpec> getSectorTracks(SectorType type) {
        return type.getSoundtracks().stream()
                .map(name -> getSounds().getTrack(name))
                .collect(java.util.stream.Collectors.toList());
    }

    public void loadEventShip(Event event) {
        loadEventShip(event, 0);
    }

    /**
     * Load the ship a created event names. The event's ship seed seeds the
     * ship's generation (CreateShip's srand, 0x624bb0); zero means none.
     * Arriving does not reseed first: the ShipAI constructor draws before
     * CreateShip come from the global stream (measured on 6 native arrivals;
     * the srand at 0x613f5c belongs to the pre-scan 0x613ed0, not arrival).
     */
    public void loadEventShip(CreatedEvent created) {
        loadEventShip(created.getTemplate(), created.getShipSeed());
    }

    private void loadEventShip(Event event, int shipSeed) {
        // Hyperspace without multiShipFix: UpdateLocation leaves the ship that is already here (WorldManager::ships is not
        // empty), so a second <ship load> at the beacon makes none (the generator, its events and its draws do not run).
        boolean keepOldShip = enemy != null && xyz.znix.xftl.hs.HS.active && !xyz.znix.xftl.hs.HS.cfg().getMultiShipFix();
        if (event.getLoadShipName() != null && !keepOldShip && eventManager.hasShip(event.getLoadShipName())) {
            EnemyShipSpec spec = eventManager.getShip(event.getLoadShipName());
            int sector = currentBeacon.getSector().getSectorNumber();
            // HS multiShipFix (WorldManager::UpdateLocation): loading a ship where one already is first brings the
            // player's boarders home and kills off the old ship's crew; the old ship is dropped with the enemy swap.
            if (enemy != null && xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().getMultiShipFix())
                xyz.znix.xftl.hs.events.HsEventEffects.INSTANCE.replaceEnemyShip(this);
            Ship built = null;
            try {
                built = content.generator.buildShip(this, spec, sector, difficulty, shipSeed != 0 ? Integer.valueOf(shipSeed) : null);
            } catch (RuntimeException e) {
                // A mod's ship whose layout is not in the game files: no ship comes.
                if (!xyz.znix.xftl.hs.HS.modded || !String.valueOf(e.getMessage()).startsWith("Missing asset")) throw e;
                System.out.println("[WARN] Ship " + event.getLoadShipName() + ": " + e.getMessage());
            }
            if (built != null) {
                setEnemy(built);
                browserEnemyCreated();
                if (eventManager.getHs() != null) xyz.znix.xftl.hs.events.HsFlow.INSTANCE.afterCreateShip(this, event.getLoadShipName());

                // Ships aren't hostile by default
                setEnemyIsHostile(false);
            }
        }
        Boolean hostileState = event.getLoadShipHostile();
        if (hostileState != null) {
            this.currentBeacon.setShip(hostileState ? enemy : null);
            setEnemyIsHostile(hostileState);

            // A battle just started at this beacon - save now so a process
            // kill mid-fight resumes with the enemy present (GitHub issue
            // #43). Vanilla saves at least this often.
            if (hostileState && mainGame != null) {
                mainGame.writeRunSave();
            }
        }
    }

    // For use by the debug console
    public void debugSpawnShip(EnemyShipSpec spec, Difficulty difficulty, int sector, int seed) {
        setEnemy(content.generator.buildShip(this, spec, sector, difficulty, seed));
        browserEnemyCreated();
        currentBeacon.setShip(enemy);
        setEnemyIsHostile(true);
    }

    /**
     * A new enemy ship as the original builds it: the ShipSystem ctor (0x642370) powers each system to its level at
     * construction (weapons and drones none) in creation order within the reactor (applied as the ship is generated,
     * Ship.browserPowerAtCreation; kept here), and ShipManager::OnInit (0x4b1bf0) runs
     * one OnLoop, which sets the rooms' blackout. So the ship is already lit and powered under its arrival event,
     * before the AI's first loop.
     */
    private void browserEnemyCreated() {
        if (enemy == null)
            return;
        // That loop is ShipManager's alone: ShipAI (and its crew AI and first boarding decision 0x6257d0) only runs
        // once the fight's CompleteShip loop does, so the ship is not at war with anyone while it is built.
        boolean hostile = enemyIsHostile;
        enemyIsHostile = false;
        try {
            enemy.browserApplyStartingPower(enemy.browserStartingReactorPower());
        } finally {
            enemyIsHostile = hostile;
        }
        for (xyz.znix.xftl.layout.Room room : enemy.getRooms())
            room.update(0f);
    }

    private void setEnemy(Ship enemy) {
        Ship previous = this.enemy;
        this.enemy = enemy;
        browserEnemyGoneSeen = false;
        if (player != null && previous != enemy) browserAchievements.enemyChanged();
        if (previous != null && previous != enemy && previous.getHacking() != null) previous.getHacking().browserEnemyChanged();
        if (player != null && player.getHacking() != null) player.getHacking().browserEnemyChanged();

        // ShipManager::SetTarget (CombatControl::AddEnemyShip / Clear): the drones aim at the new ship, or none.
        if (previous != enemy) {
            if (player != null && player.getDrones() != null) player.getDrones().browserTargetShipChanged();
            if (enemy != null && enemy.getDrones() != null) enemy.getDrones().browserTargetShipChanged();
        }

        // player=null when we're starting a new game, in which case enemy=null too.
        if (player != null) {
            player.enemyShipUpdated();
        }

        if (enemy != null) {
            enemyAI = new ShipAI(enemy, Objects.requireNonNull(player));
            // Don't create HostileShipUI in automated tests - it loads fonts,
            // which need a GL context (rendering never runs in tests).
            if (!isRunningAutomatedTest()) {
                hostileShipUI = new HostileShipUI(this, enemy);
            }
            enemy.enemyShipUpdated();
        } else {
            enemyAI = null;
            hostileShipUI = null;
        }

        // Update the drones system, as drones may need to disappear
        // when the enemy ship is changed.
        // Note the player won't be set by now, in the case of the first beacon.
        if (player != null && player.getDrones() != null) {
            player.getDrones().enemyShipUpdated();
        }

        // Turn off any drones flying around the player's ship if they belong
        // to a now-gone ship.
        if (player != null) {
            for (AbstractExternalDrone drone : player.getExternalDrones()) {
                if (isShipPresent(drone.getOwnerShip()))
                    continue;

                drone.setPowered(false);
            }
        }
    }

    /**
     * Fire the crew-kill defeat (deadCrew event, or the flagship automation)
     * when the enemy ship has no living crew left. Runs every update - it's
     * public so that automated tests can call it directly.
     */
    public void checkCrewKillDefeat() {
        if (enemy == null || !enemyIsHostile)
            return;

        // CompleteShip::DeadCrew (0x40a2f0): the enemy crew count of the crew factory (0x467dd0) is zero. It counts
        // the dead crew waiting in a clonebay, minus those once the clonebay is destroyed.
        boolean anyCrewLeft = enemy.hasCrewOwnedByShip(enemy);
        boolean anyBoardersLeft = player.hasCrewOwnedByAnyOtherShip();

        Clonebay clones = enemy.getClonebay();
        boolean clonebayOperational = false;
        if (clones != null && !clones.getBroken()) {
            for (LivingCrew queued : clones.getQueue()) {
                if (queued.getOwnerShip() == enemy)
                    clonebayOperational = true;
            }
        }

        if (!anyCrewLeft && !anyBoardersLeft && !clonebayOperational && !enemy.isAutoScout() && !enemy.isAutomated() && enemyIsHostile) {
            // Hyperspace CompleteShip::DeadCrew hook (CustomEvents.cpp): a <finalBoss> ship event's crew killed
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.events.HsFinalBoss.deadCrew(this);
            if (enemy.getBoss() != null) {
                // BossShip::GetSubEvent (0x4066d0): killing the flagship's crew doesn't defeat it (FlagshipBoss:
                // automated, BOSS_AUTOMATED at world level 0).
                enemy.getBoss().bossCrewKilled(enemy);
            } else {
                browserAchievements.crewDefeat();
                browserProgress.defeatShip();
                if (enemy.getSpec() != null) {
                    IEvent event = enemy.getSpec().getDeadCrew();
                    if (event != null)
                        showShipEnding(event, enemy);
                }

                setEnemyIsHostile(false);

                // If we jump back, they shouldn't re-appear.
                // This also means there won't be a danger mark
                // on the map.
                currentBeacon.setShip(null);
            }
        }
    }

    // This is, somewhat ironically, only used by the debug console itself.
    public void reloadDebugConsole() {
        debugConsole = null;
        debugConsoleVisible = false;
    }

    public void reloadDebugFlags() {
        debugFlags = new DebugFlagManager();
    }

    public boolean browserCanWait() { return enemy == null || !enemyIsHostile; }

    /** Hyperspace (hs.events): the enemy is hostile, and its jump-out is under way. */
    public boolean isEnemyHostile() { return enemyIsHostile; }
    public boolean isEnemyJumping() { return enemyJumpOut > 0; }

    /**
     * Hyperspace `<instantEscape>`: the enemy's ShipManager::JumpLeave now, without the escape timer or a charged FTL;
     * it leaves like an escaping ship (fly-out, then the got-away event).
     */
    public void hsEnemyJumpLeave() {
        if (enemy == null || enemyJumpOut > 0) return;
        hostileShipUI.startFlyOut();
        enemyJumpEvent = enemy.getSpec() == null ? null : enemy.getSpec().getGotaway();
        enemyJumpOut = 1f;
        enemy.browserJumpLeave();
    }
    /** Diagnostics only: the fight is over, the enemy stays (the way a crew kill ends it). */
    public void browserEndHostility() { setEnemyIsHostile(false); }

    public boolean isInDanger() {
        // WorldManager::HostileEnvironment (1.6.9 0x5ee1f0) asks SpaceManager::DangerousEnvironment first, so Hyperspace's
        // DANGEROUS_ENVIRONMENT chain runs every frame, a hostile ship or not (oracle ps-m1-ev ev|escape)
        if (xyz.znix.xftl.hs.HS.luaActive
                && xyz.znix.xftl.hs.lua.LuaAdaptersWP1.dangerousEnvironment(currentBeacon.isEnvironmentDangerous()))
            return true;

        if (enemy != null && enemyIsHostile)
            return true;

        if (!player.getIntruders().isEmpty())
            return true;

        // WorldManager::HostileEnvironment 0x6135f0: mind controlled enemies stay hostile
        if (xyz.znix.xftl.crew.NativeHostilityKt.containsMindControlledEnemy(player))
            return true;

        return !xyz.znix.xftl.hs.HS.luaActive && currentBeacon.isEnvironmentDangerous();
    }

    /**
     * Add a quest event marker, as required by an event.
     */
    @NotNull
    public QuestAddResult addQuest(String questName) {
        Sector sector = currentBeacon.getSector();
        eventCreator.refreshUsedNames();
        if (eventManager.getHs() != null) {
            int before = delayedQuests.size();
            boolean placed = xyz.znix.xftl.hs.events.HsQuests.INSTANCE.addQuest(this, sector, currentBeacon, questName, false, delayedQuests);
            if (placed) return QuestAddResult.CURRENT_SECTOR;
            return delayedQuests.size() > before ? QuestAddResult.NEXT_SECTOR : QuestAddResult.TOO_LATE;
        }
        Sector.QuestPlacement placement = sector.addQuest(currentBeacon, eventCreator, questName, false);

        if (placement == Sector.QuestPlacement.PLACED) {
            return QuestAddResult.CURRENT_SECTOR;
        } else if (sector.getSectorNumber() >= 6) {
            // If we're on sector 7 (6 when zero-indexed) or later,
            // the quest is skipped as we shouldn't put quests into
            // the last stand.
            return QuestAddResult.TOO_LATE;
        } else {
            if (placement == Sector.QuestPlacement.NO_SPACE) {
                delayedQuests.add(questName);
            }
            return QuestAddResult.NEXT_SECTOR;
        }
    }

    /**
     * Save the entire game state to a new XML document.
     * <p>
     * This can then be trivially serialised, and loaded back into
     * a new {@link InGameState} instance.
     */
    public Document saveGameState() {
        Element root = new Element("xftlSaveGame");
        root.setAttribute("enableAE", Boolean.toString(enableAdvancedEdition));
        root.setAttribute("browserRngState", ftl.browser.BrowserRandom.snapshot());
        root.setAttribute("browserNativeRngState", ftl.browser.NativeRandom.snapshot());
        // The original saves the distress toggle with the star map.
        if (fuelDistressBeacon)
            root.setAttribute("browserDistress", "true");
        Element eventState = new Element("browserEventState");
        eventManager.saveEventState(eventState);
        root.addContent(eventState);
        for (String questName : delayedQuests) {
            root.addContent(new Element("browserDelayedQuest").setAttribute("name", questName));
        }
        root.setAttribute("difficulty", difficulty.name());
        root.addContent(browserProgress.save());
        root.addContent(browserAchievements.save());
        if (arrivalEventsPending || playerFlyIn > 0f) {
            Element arrival = new Element("browserArrival")
                    .setAttribute("showBeacon", Boolean.toString(arrivalShowBeaconEvent))
                    .setAttribute("flyIn", Float.toString(playerFlyIn));
            if (arrivalEliteEvent != null) arrival.setAttribute("eliteEvent", arrivalEliteEvent.getDeserialisationId());
            root.addContent(arrival);
        }
        // An enemy that is jumping away (its escape, or Hyperspace's <instantEscape>): ShipManager::ExportShip writes
        // the jump flag and the jump tracker's time, which ImportShip puts back (see loadGameState).
        if (enemy != null && enemyJumpOut > 0f) {
            root.addContent(new Element("browserEnemyJump").setAttribute("progress", Float.toString(enemyJumpOut)));
        }

        ObjectRefs refs = new ObjectRefs();
        Sector sector = currentBeacon.getSector();

        // Generate IDs for all known ships
        refs.register(player, "player");
        for (Beacon beacon : sector.getBeacons()) {
            refs.register(beacon.getShip(), "ship");
        }
        if (enemy != null && enemy.getBoss() != null) {
            refs.register(enemy, "bossShip");
        }
        // A ship that isn't hostile and that no beacon (nor the flagship slot) keeps - an event's neutral ship, with
        // crew or automated (a guard drone), or a crew-killed one - is saved here, so a reload still shows it, neutral,
        // with its target panel. WorldManager::SaveGame (1.6.14 0x61a3a0) writes the ship at the beacon whenever there
        // is one, hostile or not, crew or none, unless it is destroyed (ShipManager::bDestroyed and
        // Ship::DestroyedDone); the crewless neutral ones used to be dropped by a reload.
        boolean saveDefeatedEnemy = enemy != null && !enemyIsHostile && !refs.isRegistered(enemy) && !enemy.isDead();
        if (saveDefeatedEnemy) {
            refs.register(enemy, "defeatedEnemy");
        }

        // Add the ID for the boss managers. We have to do this now, so the IDs
        // are available when we're writing the boss ship if there is one.
        for (BossManager boss : sector.getBosses()) {
            refs.register(boss, "boss");
        }

        // If the player ship has a custom layout, save that.
        if (player.getCustomised() != null) {
            Element customLayout = new Element("customLayout");
            player.getCustomised().saveToXML(customLayout);
            root.addContent(customLayout);
        }

        // Serialise the player ship
        Element playerShip = new Element("playerShip");
        player.saveToXML(playerShip, refs);
        root.addContent(playerShip);

        // Serialise the flagship, as it's not stored at a beacon like normal ships.
        if (enemy != null && enemy.getBoss() != null) {
            Element flagshipElem = new Element("bossShip");
            enemy.saveToXML(flagshipElem, refs);
            root.addContent(flagshipElem);
        }

        // If there's a neutral ship with some player-owned crew in it, serialise it.
        // Once you crew-kill a ship it's not saved by the beacon, so without this
        // the ship would disappear if you save-loaded, taking your crew with it.
        if (saveDefeatedEnemy) {
            Element flagshipElem = new Element("defeatedEnemy");
            enemy.saveToXML(flagshipElem, refs);
            root.addContent(flagshipElem);
        }

        // Serialise the sector map (the one you access via exit beacons)
        Element mapXML = new Element("gameMap");
        gameMap.saveToXML(mapXML, refs);
        root.addContent(mapXML);

        // Serialise the sector
        Element sectorXML = new Element("currentSector");
        ObjectRefs sectorRefs = sector.saveToXML(sectorXML, refs);
        root.addContent(sectorXML);

        // Save the beacon the player is currently at - this is how
        // we'll know what enemy ship to load, we'll just use whatever
        // is at the same beacon.
        SaveUtil.INSTANCE.addRef(root, "currentBeacon", sectorRefs, currentBeacon);

        // Save the list of visited sectors, which is used by the sector map.
        Element visitedSectorsXML = new Element("visitedSectors");
        for (GameMap.SectorInfo visited : visitedSectors) {
            String ref = refs.get(visited);
            Element visitedElem = new Element("sectorInfo");
            visitedElem.setAttribute("idr", ref);
            visitedSectorsXML.addContent(visitedElem);
        }
        root.addContent(visitedSectorsXML);

        // If the player saves while they're currently in the event UI, save that.
        Element shipUiXML = new Element("shipUI");
        shipUI.saveToXML(shipUiXML, refs);
        root.addContent(shipUiXML);

        if (xyz.znix.xftl.hs.HS.modded) xyz.znix.xftl.hs.save.HsSave.writeRun(root);
        return new Document(root);
    }

    private void loadGameState(Element root) {
        RefLoader refs = new RefLoader();

        // If the player ship has a custom layout, load that.
        Element customLayout = root.getChild("customLayout");
        EditableShip customised = null;
        if (customLayout != null) {
            customised = EditableShip.loadFromXML(customLayout);
        }

        // If there's another ship at this beacon, it's hostile.
        // Set this now so we can un-set it for crew-killed ships.
        enemyIsHostile = true;

        // Load the player ship
        Element playerShip = root.getChild("playerShip");
        if (playerShip != null && blueprintManager.getOrNull(playerShip.getAttributeValue("shipId")) == null)
            throw new xyz.znix.xftl.hs.save.HsSave.Mismatch("This run's ship, " + playerShip.getAttributeValue("shipId") + ", came from a mod that is not in this save's mods. Put that mod back under Saves > Advanced to continue the run.");
        hsLoadingPlayer = true;
        try {
            player = deserialiseSingleShip(playerShip, refs, customised);
        } finally {
            hsLoadingPlayer = false;
        }

        // Load the flagship, if we're fighting it.
        Element flagshipElem = root.getChild("bossShip");
        Ship overrideCurrentBeacon = null;
        if (flagshipElem != null) {
            overrideCurrentBeacon = deserialiseSingleShip(flagshipElem, refs, null);
        }

        // Load the non-hostile ship at the beacon that no beacon keeps (see saveGameState): an event's neutral ship,
        // an automated one or a crew-killed one
        Element defeatedElem = root.getChild("defeatedEnemy");
        if (defeatedElem != null) {
            overrideCurrentBeacon = deserialiseSingleShip(defeatedElem, refs, null);
            enemyIsHostile = false;
        }

        // Load the game map. The sector needs to reference it's SectorInfo
        // objects, so we need a separate reference loader we can switch
        // to resolve mode before loading the sector.
        RefLoader mapRefLoader = new RefLoader();
        Element mapXML = root.getChild("gameMap");
        gameMap = new GameMap(content, mapXML, mapRefLoader);
        mapRefLoader.switchToResolveMode();

        // Load the current sector - it looks odd to have a floating constructor,
        // but we only need it to create the current beacon and register it with
        // the reference loader. We then use our normal trick of only referring
        // to the current beacon, and letting that imply the current sector.
        Element sectorXML = root.getChild("currentSector");
        new Sector(sectorXML, refs, this, mapRefLoader);

        Element shipUiXML = root.getChild("shipUI");
        shipUI = new PlayerShipUI(player, this);
        shipUI.loadFromXML(shipUiXML, refs);

        // This resolves any async-resolved object references.
        refs.switchToResolveMode();

        // Load the list of visited sectors, which is used by the sector map.
        for (Element visitedElem : root.getChild("visitedSectors").getChildren("sectorInfo")) {
            String ref = visitedElem.getAttributeValue("idr");
            GameMap.SectorInfo visited = mapRefLoader.resolve(GameMap.SectorInfo.class, ref);
            visitedSectors.add(visited);
        }

        // We can finally load out our current beacon.
        currentBeacon = SaveUtil.INSTANCE.getRefImmediate(root, "currentBeacon", refs, Beacon.class);
        Objects.requireNonNull(currentBeacon);

        // Load in the previous enemy
        if (overrideCurrentBeacon != null) {
            setEnemy(overrideCurrentBeacon);
        } else {
            setEnemy(currentBeacon.getShip());
        }
        // ShipManager::ImportShip (1.6.9 0x4a7530): an enemy saved while jumping away runs JumpLeave again and its jump
        // tracker (+0x4ac) gets the saved time back, so it goes on leaving where it was instead of staying (an
        // <instantEscape>) or starting its 2 s again (an escape).
        Element enemyJump = root.getChild("browserEnemyJump");
        if (enemyJump != null && enemy != null) {
            enemyJumpOut = Math.max(0f, Math.min(1f, Float.parseFloat(enemyJump.getAttributeValue("progress"))));
            enemyJumpEvent = enemy.getSpec() == null ? null : enemy.getSpec().getGotaway();
            enemy.browserJumpLeave();
            if (hostileShipUI != null) hostileShipUI.startFlyOut(enemyJumpOut);
        }

        // We might have loaded a beacon with a store
        shipUI.updateButtons();
        browserProgress.load(root.getChild("browserProgress"));
        browserAchievements.load(root.getChild("browserAchievements"));
        Element arrival = root.getChild("browserArrival");
        if (arrival != null) {
            arrivalShowBeaconEvent = "true".equals(arrival.getAttributeValue("showBeacon"));
            playerFlyIn = Math.max(0f, Math.min(1f, Float.parseFloat(arrival.getAttributeValue("flyIn"))));
            String eliteEvent = arrival.getAttributeValue("eliteEvent");
            arrivalEliteEvent = eliteEvent == null ? null : eventManager.getByDeserialisationIdOrNull(eliteEvent);
            arrivalEventsPending = arrivalShowBeaconEvent || arrivalEliteEvent != null;
            arrivalBoxHidden = false;
        }
        ftl.browser.BrowserRandom.restore(root.getAttributeValue("browserRngState"));
        ftl.browser.NativeRandom.restore(root.getAttributeValue("browserNativeRngState"));
        fuelDistressBeacon = "true".equals(root.getAttributeValue("browserDistress"));
        Element eventState = root.getChild("browserEventState");
        if (eventState != null) {
            eventManager.loadEventState(eventState);
        } else {
            eventManager.restoreEventState();
        }
        for (Element quest : root.getChildren("browserDelayedQuest")) {
            delayedQuests.add(quest.getAttributeValue("name"));
        }
    }

    /**
     * Deserialise a player or enemy ship.
     * <p>
     * This should only be used by deserialisation code, specifically
     * the player ship deserialisation in this class and the enemy ship
     * deserialisation in beacons.
     */
    private boolean hsLoadingPlayer;

    /**
     * Hyperspace: the player's ship is the player's whatever its blueprint (ShipManager iShipId 0), so a ship the
     * console SWITCH built from an enemy blueprint keeps the player's start power, vision, shields and weapon rules.
     */
    private ShipBlueprint playerBlueprint(ShipBlueprint blueprint) {
        if (blueprint.isPlayerShip() || !xyz.znix.xftl.hs.HS.active) return blueprint;
        return new ShipBlueprint(blueprint.getSourceElement(), df, true);
    }

    public Ship deserialiseSingleShip(Element shipElement, RefLoader refs, EditableShip customised) {
        // Load the appropriate ship definition XML element from the blueprints
        String shipId = shipElement.getAttributeValue("shipId");

        String specId = shipElement.getAttributeValue("specId");
        EnemyShipSpec spec;
        if (specId.equals("null")) {
            spec = null;
        } else {
            spec = content.eventManager.hasShip(specId) ? content.eventManager.getShip(specId) : null;
            if (spec == null) xyz.znix.xftl.savegame.MissingContent.note(specId);
        }

        ShipBlueprint blueprint = blueprintManager.getShip(shipId);
        if (hsLoadingPlayer) blueprint = playerBlueprint(blueprint);

        // And use that to build and deserialise the ship
        // Note we pass in null as the boss - that'll be updated if this turns out to be a boss.
        Ship ship = new Ship(blueprint, this, customised, spec, null);
        ship.loadFromXml(shipElement, refs);

        String genSeed = shipElement.getAttributeValue("genSeed");
        if (genSeed != null) {
            ship.setGenerationSeed(Integer.parseInt(genSeed));
        }

        return ship;
    }

    @NotNull
    public Image getImg(String name) {
        name = xyz.znix.xftl.Languages.localImage(df, name);
        Image img = content.images.get(name);
        if (img != null)
            return img;

        // This is just a way to access the image by a path, since we need
        // that for the missing animation.
        if (name.equals(Constants.MISSING_FILE_PATH))
            return getMissingImage();

        FTLFile file = df.getOrNull(name);
        if (file != null) {
            img = content.loadImage(file);
        } else {
            System.out.println("[WARN] Missing image: " + name);
            img = getMissingImage();
        }
        content.images.put(name, img);
        return img;
    }

    /**
     * Loads an image if it's file exists, or returns null.
     * <p>
     * Try to avoid this if you can - normally image paths are specified in
     * some predictable way, so you shouldn't be guessing their names.
     */
    @Nullable
    public Image getImgIfExists(String name) {
        name = xyz.znix.xftl.Languages.localImage(df, name);
        Image img = content.images.get(name);
        if (img != null)
            return img;

        FTLFile file = df.getOrNull(name);
        if (file == null)
            return null;

        img = content.loadImage(file);
        content.images.put(name, img);
        return img;
    }

    /**
     * Creates a new {@link SILFontLoader} for a given font. This caches the bitmap and character
     * data, but the scale of each returned fontloader is completely independent.
     *
     * @param name The name of the font - the font at 'fonts/$name.font' is loaded
     * @return The new {@link SILFontLoader} instance
     */
    @NotNull
    public SILFontLoader getFont(String name) {
        SILFontLoader font = content.fonts.get(name);
        if (font != null) {
            // Make a new instance sharing the font data
            return new SILFontLoader(font);
        }

        font = new SILFontLoader(content.resourceContext, df, df.get("fonts/" + name + ".font"));
        content.fonts.put(name, font);
        return new SILFontLoader(font);
    }

    /**
     * Create a new {@link SILFontLoader} (see {@link #getFont(String)}), and also set the font's scale
     */
    @NotNull
    public SILFontLoader getFont(String name, float scale) {
        SILFontLoader font = getFont(name);
        font.setScale(scale);
        return font;
    }

    /**
     * Get or create a cursor for a given image path.
     */
    @NotNull
    public Cursor getCursor(String path) {
        Cursor cursor = content.cursors.get(path);
        if (cursor != null) {
            return cursor;
        }

        // For overlaying cursors, and '#n' for one 32px frame of a strip
        String base = path;
        int frame = -1;
        int hash = base.indexOf('#');
        if (hash >= 0) {
            frame = Integer.parseInt(base.substring(hash + 1));
            base = base.substring(0, hash);
        }
        String[] parts = base.split(",");
        FTLFile main = df.get(parts[0]);
        FTLFile overlay = parts.length == 2 ? df.get(parts[1]) : null;

        cursor = new Cursor(content.resourceContext, df, main, overlay, frame);
        content.cursors.put(path, cursor);
        return cursor;
    }

    public Animations getAnimations() {
        return content.animations;
    }

    public SoundManager getSounds() {
        return content.sounds;
    }

    public SpecDeserialiser getUiLoader() {
        return uiLoader;
    }

    public RoomClickListener getClickEvent() {
        return clickEvent;
    }

    public void setClickEvent(RoomClickListener clickEvent) {
        this.clickEvent = clickEvent;
    }

    public Translator getTranslator() {
        return content.translator;
    }

    public GameMap getGameMap() {
        return gameMap;
    }

    /**
     * The enemy destroyed at this beacon. WorldManager keeps its CompleteShip (bDestroyed, still looping) until the
     * next location (native 1.6.14 drone-order probe: 15 s after the kill; HS oracle wp4-drone-fight), while the
     * engine drops it from play once its explosion is over. Hyperspace's ShipManager lookup reads it.
     */
    private Ship browserDestroyedEnemy;
    private boolean browserEnemyGoneSeen;
    private Beacon browserDestroyedEnemyBeacon;

    public Ship getBrowserRetainedEnemy() {
        if (enemy != null) return enemy;
        return browserDestroyedEnemyBeacon == currentBeacon ? browserDestroyedEnemy : null;
    }

    public Beacon getCurrentBeacon() {
        return currentBeacon;
    }

    public BlueprintManager getBlueprintManager() {
        return blueprintManager;
    }

    /** The arrival's event box is open (hidden) during the fly-in: the game is paused (CFPS RunningTime stops). */
    /** The arrival's box opened at the location switch and waits, hidden, for the fly-in to end. */
    public boolean isArrivalHold() { return arrivalEventsPending && arrivalBoxHidden; }

    public boolean isPaused() {
        return focusPaused || isPausedExceptFocus();
    }

    private boolean isPausedExceptFocus() {
        // The touch UI's room-selection mode (iPad-style auto-pause while
        // crew are selected), the power popup (while power is being
        // manipulated) and the targeting modes (weapon aiming, or the
        // teleporter/hacking/mind-control room pickers) also freeze the
        // game.
        return paused || debugConsoleVisible || shipUI.isWindowOpen() ||
                (shipUI != null && shipUI.getRoomSelectionMode()) ||
                (shipUI != null && shipUI.getPowerPopupOpen()) ||
                isTouchTargeting();
    }

    /**
     * The pause flag SpaceManager's shade sees (SpaceManager +0x360, copied by CommandGui::RenderStatic 0x535070 from
     * CommandGui +0x2f6c, which only SetPaused and SpaceBar set). Focus windows (the event ChoiceBox, store, upgrades
     * and its crew/equipment tabs, star map, pause menu, options, confirmations) pause the game through the focus
     * window flag +0x2f6e instead (CommandGui::OnLoop 0x536ad6), so they never add the x0.4 shade; a full-focus one
     * only dims the whole scene by 0.15 ({@link PlayerShipUI#getSceneTint}). Native captures vn-slug, vn-win, vn-win2:
     * event box x1, store/upgrades/crew/equipment/map/menu/options/restart x0.15, Space x0.4, Space + upgrades x0.06;
     * HS oracle r2-beacon 08b, 14-pause, ships-rng-101 store/pause-menu, mv-seed-12345 maps x0.15.
     */
    public boolean isShadePaused() {
        return focusPaused || paused || debugConsoleVisible ||
                shipUI.getRoomSelectionMode() || shipUI.getPowerPopupOpen() || isTouchTargeting();
    }

    /** Explicit player pause; modal/focus pauses do not satisfy tutorial input. */
    public boolean isPlayerPaused() { return paused; }
    public boolean isFocusPaused() { return focusPaused; }
    public void setFocusPaused(boolean value) { focusPaused = value; }

    /** Native tutorial AdvanceState clears player pause when queuing the missile event. */
    public void unpauseTutorial() {
        requireCurrentTutorial();
        paused = false;
    }

    /** Diagnostics only: an event by name, through the normal event path. */
    public void debugShowEvent(String name) {
        showEventDialogue(eventManager.get(name).resolve(), 0);
    }

    /** Deliver through the normal event/reward/save path; never directly grant items. */
    public void deliverTutorialEvent(String name) {
        requireCurrentTutorial();
        if (debugConsoleVisible || shipUI.isWindowOpen())
            throw new IllegalStateException("Cannot replace a tutorial window with an event");
        showEventDialogue(eventManager.get(name).resolve(), ftl.browser.BrowserRandom.INSTANCE.nextInt());
    }

    private void requireCurrentTutorial() {
        if (mainGame == null || !mainGame.isTutorialRunning() || mainGame.getCurrentState() != this)
            throw new IllegalStateException("Operation requires the current isolated tutorial simulation");
    }

    /**
     * Called when the app loses focus (GitHub issue #96): open the pause
     * menu so returning to the app never resumes an unpaused battlefield.
     * One-way: it never closes or navigates anything - if any window is
     * already up (dialogue, game over, pause) the game is frozen and this
     * does nothing.
     */
    public void autoPause() {
        if (mainGame == null)
            return;  // automated tests have no main game

        if (!isPausedExceptFocus()) {
            shipUI.showPauseWindow();
        }
    }

    /**
     * The Android letterbox pause button: a quick pause, identical to
     * the space-bar PAUSE hotkey - freezes the game without opening the
     * pause menu (the vanilla PAUSED indicator shows while frozen).
     */
    public void toggleQuickPause() {
        paused = !paused;
    }

    /**
     * The screen position of the enemy ship, for UI code that needs to
     * hit-test rooms on it. Only valid while an enemy is present.
     */
    @Nullable
    public IPoint getEnemyShipPos() {
        if (hostileShipUI == null) {
            return null;
        }
        return hostileShipUI.getShipPos();
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     * <p>
     * It's generally better to go through SlickGame or something else to
     * keep the various moving parts separate.
     */
    public PlayerShipUI getShipUI() {
        return shipUI;
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     */
    public Ship getPlayer() {
        return player;
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     */
    public Ship getEnemy() {
        return enemy;
    }

    /**
     * Find the ship that's the enemy of the specified one.
     * <p>
     * This should be used for things like drones and teleporters
     * which need to access the enemy ship if installed on the player,
     * or the player ship if installed on the enemey.
     */
    @Nullable
    public Ship getEnemyOf(Ship source) {
        // If there isn't a fight going on, no-one should be shooting at anyone else.
        if (!enemyIsHostile) {
            return null;
        }

        if (source == player) {
            return enemy;
        } else if (source == enemy) {
            return player;
        } else {
            // Some ship that isn't present
            return null;
        }
    }

    /**
     * Check if the given ship is either the player or enemy.
     * <p>
     * This is intended for drones, which need to self-destruct
     * when the ship powering them is gone (regardless of which
     * ship had the system installed, or who jumped away).
     */
    public boolean isShipPresent(@NotNull Ship ship) {
        Objects.requireNonNull(ship);
        return ship == enemy || ship == player;
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     */
    public IPoint getEnemyPosition() {
        return hostileShipUI.getShipPos();
    }

    /**
     * Convert a screen-space position to the enemy ship's render space,
     * for hit-testing rooms/crew on it. Accounts for the touch layout's
     * scaled enemy box (a no-op otherwise). Only valid while an enemy is
     * present.
     */
    public void convertScreenToEnemyShip(Point point) {
        if (hostileShipUI == null) {
            return;
        }
        hostileShipUI.convertScreenToShipRender(point);
    }

    /**
     * Convert a position in the enemy ship's render space to screen
     * space - the inverse of {@link #convertScreenToEnemyShip}. Only
     * valid while an enemy is present.
     */
    public IPoint enemyShipRenderToScreen(int x, int y) {
        return hostileShipUI.shipRenderToScreen(x, y);
    }

    /** Read-only counterpart to player ship hit-testing, also used by diagnostics. */
    public IPoint playerShipRenderToScreen(int x, int y) {
        float scale = getPlayerShipRenderScale();
        return new xyz.znix.xftl.math.ConstPoint(Math.round(playerShipOffset.getX() + scale * x),
                Math.round(playerShipOffset.getY() + scale * y));
    }

    /**
     * True while the touch weapon-targeting mode is active: a weapon is
     * armed and waiting for its target room (or a beam is being drawn).
     */
    public boolean isWeaponTargeting() {
        return shipUI != null && shipUI.getWeaponTargetingMode();
    }

    /**
     * True while ANY touch targeting mode is active: weapon targeting, or
     * one of the teleporter/hacking/mind-control room pickers. All of
     * these aim at the enemy ship's rooms, so they get the same
     * auto-pause + enlarged-enemy-ship treatment (the iPad port's UX).
     */
    public boolean isTouchTargeting() {
        return false;
    }

    /**
     * The scale the player's ship is rendered at: 1 normally; on touch
     * layouts it shrinks while a targeting mode (weapon or one of the
     * room pickers) is active, so the (full-size) enemy ship is easier
     * to aim at. playerShipOffset stays the ship render space's screen
     * anchor, so screen positions convert with (mouse - offset) / scale
     * - see {@link #convertScreenToPlayerShip(Point)}.
     */
    public float getPlayerShipRenderScale() {
        if (shipUI != null && shipUI.getTouch() != null) return shipUI.getTouch().getPlayerScale();
        return isTouchTargeting() ? TARGETING_SHIP_SCALE : 1f;
    }

    public IPoint getPlayerShipOffsetPoint() { return playerShipOffset; }

    /** The enemy's target box UI, while there is an enemy. */
    @Nullable public HostileShipUI getEnemyShipUI() { return hostileShipUI; }

    /** The touch HUD's room under the finger, for the highlight (the mouse path sets it in updateClickEvent). */
    public void hoverRoomForTouch(@Nullable Room room) { touchHoverRoom = room; }

    public void setPlayerPaused(boolean value) { paused = value; }

    /**
     * Convert a screen-space position to the player ship's render space
     * (scaling by {@link #getPlayerShipRenderScale()} about
     * playerShipOffset inverted), for hit-testing rooms/doors/crew on
     * the player ship.
     */
    public void convertScreenToPlayerShip(Point point) {
        float scale = getPlayerShipRenderScale();
        point.setX(Math.round((point.getX() - playerShipOffset.getX()) / scale));
        point.setY(Math.round((point.getY() - playerShipOffset.getY()) / scale));
    }

    public LootPool getLootPool() {
        return lootPool;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public HotkeyManager getHotkeyManager() {
        return content.hotkeyManager;
    }

    /** Hyperspace: Sector::visited written by a script. */
    public void hsSetVisited(GameMap.SectorInfo info, boolean visited) {
        if (!visited) visitedSectors.remove(info);
        else if (!visitedSectors.contains(info)) visitedSectors.add(info);
    }

    public List<GameMap.SectorInfo> getVisitedSectors() {
        return Collections.unmodifiableList(visitedSectors);
    }

    public CrewNameManager getNameManager() {
        return content.nameManager;
    }

    public GameContent getContent() {
        return content;
    }

    public DebugFlagManager getDebugFlags() {
        return debugFlags;
    }

    public DebugConsole getDebugConsole() {
        if (debugConsole == null) debugConsole = new DebugConsole(this);
        return debugConsole;
    }
    public boolean isDebugConsoleVisible() { return debugConsoleVisible; }

    /**
     * What a finger taps where the touch HUD does not take it (TouchRouter): the tutorial's panel, else the open window's
     * button. The debug console takes the finger as the mouse.
     */
    @Override
    public Object liftTarget(int x, int y) {
        if (debugConsoleVisible || shipUI == null) return null;
        ftl.browser.BrowserTutorialOverlay overlay = mainGame == null ? null : mainGame.getTutorialOverlay();
        if (overlay != null && overlay.hitTest(this, x, y)) return overlay;
        return shipUI.liftTarget(x, y);
    }
    public void openDebugConsole() {
        getDebugConsole(); debugConsoleVisible = true; queuedHotkeys.clear();
        clickEvent = null; input.clearInputPressedRecord();
        // Android: the on-screen keyboard, the full-screen console's input bar at the top kept above it (DebugConsole)
        if (TouchMode.enabled) { TouchMode.keyboardWanted = true; TouchMode.keyboardFocusY = xyz.znix.xftl.devutil.DebugConsole.TOUCH_FOCUS_Y; }
        ftl.browser.BrowserDebug.event("ui", "console.open", "window", "console");
    }
    public void cancelMenu() {
        queuedHotkeys.clear();
        if (debugConsoleVisible) {
            debugConsoleVisible = false;
            debugConsole.closed();
            if (TouchMode.enabled) TouchMode.keyboardWanted = false;
        }
        else shipUI.escapePressed();
        ftl.browser.BrowserDebug.event("ui", "menu.cancel", "window", shipUI.getDiagnosticWindow());
    }

    /**
     * Stuff that happens in-game should be isolated to this specific game.
     * Think carefully about why you need to use this!
     */
    public MainGame getMainGame() {
        // This is only used for stuff like switching out of the main state,
        // which we don't currently support testing. Since very few things
        // do that, automated testing for that is overkill.
        if (mainGame == null) {
            throw new UnsupportedOperationException("Cannot get main state while running tests");
        }

        return mainGame;
    }

    /**
     * True if stuff is currently being deserialised.
     * <p>
     * It's generally a bit ugly to use this, but it's an easy
     * way to stop stuff from happening while the game is being loaded.
     */
    public boolean isCurrentlyLoadingSave() {
        return isCurrentlyLoadingSave;
    }

    @NotNull
    /** Diagnostics only: the parity generation hook runs maps at a requested difficulty. */
    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    /**
     * Get the delta-time (of wall-clock time) that has elapsed this frame.
     * <p>
     * This is for use in animations that play regardless of whether the game
     * is paused or not. It must NOT be used for anything related to gameplay,
     * as that would break if we change the game speed.
     */
    public float getRenderingDeltaTime() {
        return renderingDeltaTime;
    }

    /** Drawing can be capped separately from updates in the browser. */
    public void setRenderingDeltaTime(float delta) {
        renderingDeltaTime = delta;
    }

    /** Was the enemy interior drawn last frame (ShipManager::OnRender 0x4a3c00)? For the parity checks. */
    public boolean isEnemyInteriorVisible() {
        return enemyInteriorVisible;
    }

    /**
     * If there's currently a room click listener, this returns the room that is
     * currently being hovered.
     *
     * @see #setClickEvent(RoomClickListener)
     */
    @Nullable
    public Room getHoveredRoom() {
        return hoveredRoom;
    }

    /**
     * True if this 'game' is actually an automated test.
     * <p>
     * Try to avoid using this where possible to keep the tests accurate,
     * but it's useful for things that relate to sound or rendering.
     */
    public boolean isRunningAutomatedTest() {
        return mainGame == null;
    }

    @NotNull
    public Image getMissingImage() {
        if (missingImage != null)
            return missingImage;

        missingImage = df.readImage(content.resourceContext, df.get("img/nullResource.png"));
        return missingImage;
    }

    public WindowRenderer getWindowRenderer() {
        if (content.windowRenderer != null)
            return content.windowRenderer;

        Image background = getImg("img/window_base.png");
        Image outline = getImg("img/window_outline.png");
        Image mask = getImg("img/window_mask.png");
        content.windowRenderer = new WindowRenderer(background, outline, mask);

        return content.windowRenderer;
    }

    /**
     * ShipManager::ModifyScrapCount (0x49ba70), reached with the income flag only from the
     * event reward application (0x615930): the SCRAP_COLLECTOR value (stackable, +0.10 each)
     * minus the REPAIR_ARM value (0.15) while the hull is not full is added to the amount as one
     * fraction, and the result is truncated once. Store sales, repairs and purchases never
     * carry the flag, so they are not modified.
     */
    /** Hyperspace CustomEvents.cpp blockScrapCollected / blockScrapAugments: set while an event with disableScrapScore / disableScrapAugments is applied. */
    public boolean hsBlockScrapCollected, hsBlockScrapAugments;

    private int applyScrapModifiers(int scrap) {
        if (scrap <= 0 || hsBlockScrapAugments)
            return scrap;

        float bonus = player.getAugmentValue(AugmentBlueprint.SCRAP_COLLECTOR);
        if (player.getHealth() != player.getMaxHealth())
            bonus -= player.getAugmentValue(AugmentBlueprint.REPAIR_ARM);

        return (int) ((double) bonus * (double) scrap + (double) scrap);
    }

    /** ShipManager +0x4d8 for the player ship: from the start of the jump-out until the arrival animation ends. */
    /** The enemy ship's AI (CombatAI), or null without a hostile ship. */
    public ShipAI getEnemyAI() {
        return enemyAI;
    }

    public boolean isPlayerJumping() {
        return playerJumpOut > 0 || playerFlyIn > 0;
    }

    /**
     * ShipManager bJumping: set by JumpLeave, cleared by ShipManager::OnRender once the arrival after that jump is done.
     * The fly-in of a new or loaded run is not a jump's arrival (no evidence that the original holds keys there).
     */
    public boolean isShipJumping() {
        return playerJumpOut > 0 || (jumpArrival && playerFlyIn > 0);
    }

    /** The jump-out alone (ShipManager::JumpLeave until the arrival starts). */
    public boolean isPlayerJumpingOut() {
        return playerJumpOut > 0;
    }

    public void givePlayerResources(@NotNull ResourceSet resources) {
        // The event reward (0x615930) floors the fuel at 0, and ModifyMissileCount (0x49fa70 / 0x636200),
        // ModifyDroneCount (0x49f2c0 / 0x638b50) and ModifyScrapCount (0x49ba70) floor their counts at 0: a loss
        // larger than the stock empties it.
        player.setFuelCount(Math.max(0, player.getFuelCount() + resources.getFuel()));
        player.setDronesCount(Math.max(0, player.getDronesCount() + resources.getDroneParts()));
        player.setMissilesCount(Math.max(0, player.getMissilesCount() + resources.getMissiles()));

        int scrapGain = resources.getScrap();
        if (!hsBlockScrapCollected) browserProgress.collectScrap(scrapGain);
        player.setScrap(Math.max(0, player.getScrap() + applyScrapModifiers(scrapGain)));

        // ModifyScrapCount (0x49ba70): REPAIR_ARM heals 2 hull on every positive reward, through
        // ShipManager::DamageHull (0x49ba00) which does nothing while bJumping (+0x4d8, set by
        // JumpLeave 0x49c9d0 and cleared when the arrival animation ends in ShipManager::OnLoop).
        if (scrapGain > 0 && !isPlayerJumping() && player.getAugmentValue(AugmentBlueprint.REPAIR_ARM) > 0f) {
            player.setHealth(player.getHealth() + 2);
        }

        for (Blueprint item : resources.getItems()) {
            player.addBlueprint(item, true);
        }

        for (LivingCrewInfo info : resources.getCrew()) {
            player.addCrewMember(info, false, false);
            gameMap.noteBlueprintCrew(this, info);
        }

        for (RemoveCrewEval removed : resources.getLostCrew()) {
            LivingCrew crew = removed.getCrew();
            RemoveCrew info = removed.getInfo();
            browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.LOST_CREW);

            if (player.getClonebay() != null) {
                String cloneMessage = info.getCloneText().resolve(ftl.browser.BrowserRandom.INSTANCE);
                if (!cloneMessage.isBlank()) {
                    shipUI.showSyntheticDialogue(new DialogueWindow.SyntheticEvent(cloneMessage));
                }

                // Never actually remove the crewmember if they're cloned
                if (info.getClone()) {
                    crew.jumpTo(player.findSpaceForCrew(player.getClonebay().getRoom(), crew.getMode()));
                    crew.onCloned(); // Deducts skills
                    continue;
                }
            }

            if (info.getTurnHostile()) {
                crew.setOwnerShip(null);
            } else {
                crew.removeFromShip();
            }
        }

        // Event boarders (0x613440 -> 0x408260): none come aboard once the
        // ship holds 15 intruders. They all start in one random room and
        // teleport in, or with breach="true" arrive through a hull breach
        // in that room, which also costs a point of hull.
        if (resources.getHasIntruders() && !resources.getBoardersAboard() && intruderCount() < 15) {
            placeIntruders(resources.getIntruders(), resources.getBoarderCount(), resources.getBoarderRace(), resources.getIntrudersBreach());
        }

        // Apply the hull damage. Vanilla plays its eventDamage explosion
        // (a large explosion sample) once for the damage an event deals.
        // Negative amounts are hull REPAIRS - vanilla plays nothing for
        // those, and playing the damage boom for a repair was GitHub
        // issue #80.
        boolean anyDamage = resources.getDamage().stream()
                .anyMatch(damage -> damage.getAmount() > 0);
        if (anyDamage) {
            FTLSound eventDamage = getSounds().getSampleOrWarn("eventDamage");
            if (eventDamage != null) {
                eventDamage.play();
            }
        }
        for (EventHullDamage damage : resources.getDamage()) {
            String system = damage.getSystem();
            if (system == null) {
                // A null system means hull damage only
                player.setHealth(player.getHealth() - damage.getAmount());
                continue;
            }

            // Event damage (0x612d90): hull damage, plus that much system
            // damage in the target room. "random" picks one of the ship's
            // systems, "room" any room; a named system the ship lacks has no
            // room, leaving only the hull damage.
            Room targetRoom;

            if (system.equals("random")) {
                AbstractSystem randomSystem = player.getSystems().get(ftl.browser.NativeRandom.INSTANCE.nextInt(player.getSystems().size()));
                targetRoom = randomSystem.getRoom();
            } else if (system.equals("room")) {
                targetRoom = player.getRooms().get(ftl.browser.NativeRandom.INSTANCE.nextInt(player.getRooms().size()));
            } else {
                targetRoom = player.getSystems().stream()
                        .filter(s -> s.getCodename().equals(system))
                        .map(AbstractSystem::getRoom)
                        .findFirst().orElse(null);
            }

            if (targetRoom == null) {
                player.setHealth(player.getHealth() - damage.getAmount());
                continue;
            }

            player.damage(targetRoom, Damage.hullAndSys(damage.getAmount()), null);

            // Spreader_Fire::StartInRoom(room, 2) lights two random cells;
            // "random" is a fire or a breach with equal chance.
            boolean fire = damage.getEffectFire();
            boolean breach = damage.getEffectBreach();
            if (damage.getEffectRandom()) {
                if (ftl.browser.NativeRandom.INSTANCE.nextBoolean()) {
                    fire = true;
                } else {
                    breach = true;
                }
            }
            if (fire) {
                targetRoom.spawnFire();
                targetRoom.spawnFire();
            }
            if (breach) {
                targetRoom.spawnBreach();
            }

            // TEST_EVENT is a good way to test this, as it spawns both of them.
        }

        applyEventUpgrades(resources.getUpgrades());

        // Apply the fleet pursuit modifier
        Sector currentSector = currentBeacon.getSector();
        currentSector.setFleetAdvanceModifier(currentSector.getFleetAdvanceModifier() + resources.getModifyPursuit());
    }

    /** An event's system and reactor upgrades, capped at each system's maximum. */
    private void applyEventUpgrades(@NotNull java.util.List<EventSystemUpgrade> upgrades) {
        for (EventSystemUpgrade upgrade : upgrades) {
            if (upgrade.getSystem().equals("reactor")) {
                int newPower = player.getPurchasedReactorPower() + upgrade.getAmount();
                player.setPurchasedReactorPower(Math.min(newPower, player.getMaxReactorPower()));
                continue;
            }

            AbstractSystem system = player.getSystems().stream()
                    .filter(s -> s.getCodename().equals(upgrade.getSystem()))
                    .findFirst().orElse(null);

            if (system == null)
                continue;

            int newPower = system.getEnergyLevels() + upgrade.getAmount();
            system.setEnergyLevels(Math.min(system.getBlueprint().getMaxPower(), newPower));
        }
    }

    /**
     * Advance the fleet pursuit by the amount determined by the current beacon.
     */
    public void advanceFleet() {
        // Tutorial CommandGui loop 0x00536770 disables fleet pursuit.
        if (mainGame != null && mainGame.isTutorialRunning()) return;
        Sector sector = currentBeacon.getSector();

        // The last stand works differently, capturing two beacons per jump.
        if (sector.isLastStand()) {
            advanceFleetLastStand();
            return;
        }

        // StarMap::UpdateDangerZone (0x5f5280): the zone moves, overtaking beacons (and creating their
        // fleet events) in the order it reaches them.
        sector.advanceFleet(this, currentBeacon);
    }

    private void advanceFleetLastStand() {
        Sector sector = currentBeacon.getSector();

        ftl.browser.BrowserLastStand.advance(this);
    }

    /**
     * FOR DEBUG USE ONLY!
     * <p>
     * When reloading the game every frame, some UI stuff (like the selection
     * rectangle) breaks. It'd be a bit silly to serialise this, but it's
     * also very hard to meaningfully play with it being constantly cleared.
     * <p>
     * Thus, this copies over basic UI stuff that's very unlikely to cover
     * up any serialisation bugs.
     */
    void debugContinuousSaveRestore(InGameState prev) {
        // Swap over the entire debug console, since it has no state.
        // Without this, the debug console would close across a reload.
        // Note we copy it whether or not it's open, so we don't loose
        // it's command history when you close it.
        if (prev.debugConsole != null) {
            debugConsole = prev.debugConsole;
            debugConsole.setGame(this);
        }
        debugConsoleVisible = prev.debugConsoleVisible;

        shipUI.debugContinuousSaveRestore(prev.shipUI);
        System.arraycopy(prev.mouseDownPrev, 0, mouseDownPrev, 0, mouseDownPrev.length);
        System.arraycopy(prev.mouseCaptured, 0, mouseCaptured, 0, mouseCaptured.length);

        paused = prev.paused;

        // Copy over the debug flags.
        // It'd be annoying to lose those, since they're not supposed
        // to be saved.
        List<DebugFlagManager.DebugFlag> oldFlags = prev.debugFlags.getAll();
        List<DebugFlagManager.DebugFlag> newFlags = debugFlags.getAll();
        for (int i = 0; i < newFlags.size(); i++) {
            newFlags.get(i).setSet(oldFlags.get(i).getSet());
        }
    }

    /**
     * FOR DEBUGGING USE ONLY!
     * <p>
     * This is called by {@link MainGame} when we're in continuous save-load mode,
     * and there's an exception during the save-load process.
     */
    public void debugFailedSaveRestore() {
        // Pause the game so we don't get an ongoing stream of these errors.
        paused = true;

        // Print a message in the debug console, so the player knows what happened.
        debugConsoleVisible = true;
        getDebugConsole().onFailedSaveRestore();
    }

    /**
     * Update the list of player-owned crew, to account for crew being added or removed.
     */
    /**
     * Show the game-over screen when the player has lost: either their ship
     * has been destroyed (shown once its explosion animation has finished)
     * or their last crewmember has died (once the death animation finishes).
     *
     * Crew being rebuilt by a clonebay don't count as lost - if it's powered
     * (or backup DNA is preserving them) they can still come back. If the
     * clonebay is unpowered they'll die in short order and we'll end up here.
     *
     * The defeat is final: the run save is deleted as the screen appears,
     * so the main menu's Continue can't resurrect a dead run (vanilla
     * semantics). Winning keeps the save - the flagship fight isn't
     * implemented yet, so this only covers the loss outcomes in practice.
     */
    /** Hyperspace GameOver::OpenText: a queued death event loads instead of the game over screen. */
    private boolean hsDeathEvent() {
        return eventManager.getHs() != null
                && xyz.znix.xftl.hs.events.HsFlow.INSTANCE.onGameOver(this, shipUI.getHsHost(), false);
    }

    private void checkForGameOver() {
        // CommandGui::OnLoop (0x536770) calls CheckGameover (0x52c900) every frame, paused or not, unless the player
        // is jumping or a focus window is open (menu_pause, +0x2f6e). It tests the crew first (gameover_crew); the
        // blowup only counts while crew lives.
        if (shipUI == null || browserRunEnded || isPlayerJumping() || shipUI.isWindowOpen())
            return;

        if (!browserCrewLost()) {
            if (player.isGone()) {
                // Hyperspace WorldManager::OnLoop: after a <finalBoss> victory the defeat is the victory
                if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.events.HsFinalBoss.defeatIsVictory(this)) {
                    shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN);
                    return;
                }
                if (hsDeathEvent()) return;
                mainGame.deleteRunSave();
                shipUI.showGameOverScreen(GameOverWindow.Outcome.LOOSE_HULL);
                return;
            }
            // Third test: the flagship has finished the base and the player is not at its beacon. The jump that
            // used the last turn therefore lands, and its event boxes play, before the run ends.
            if (ftl.browser.BrowserLastStand.baseDestroyed(currentBeacon)) {
                if (hsDeathEvent()) return;
                ftl.browser.BrowserLastStand.checkDefeat(this, currentBeacon);
            }
            return;
        }

        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.events.HsFinalBoss.defeatIsVictory(this)) {
            shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN);
            return;
        }
        if (hsDeathEvent()) return;
        mainGame.deleteRunSave();
        shipUI.showGameOverScreen(GameOverWindow.Outcome.LOOSE_CREW);
    }

    /**
     * CompleteShip::DeadCrew (0x40a2f0): the crew factory's count of our crew is zero. A crew member at zero health
     * no longer counts unless it waits to be cloned and the clone bay has health (ShipManager::GetSystemHealth).
     * Ownership is checked, so crew aboard the enemy, mid-teleport or mind controlled are handled.
     */
    private boolean browserCrewLost() {
        Clonebay clonebay = player.getClonebay();
        boolean cloning = clonebay != null && clonebay.getUndamagedEnergy() > 0;
        java.util.List<xyz.znix.xftl.crew.AbstractCrew> all = new java.util.ArrayList<>(player.getCrew());
        if (enemy != null)
            all.addAll(enemy.getCrew());
        if (clonebay != null)
            all.addAll(clonebay.getQueue());
        for (xyz.znix.xftl.crew.AbstractCrew crew : all) {
            if (!(crew instanceof LivingCrew) || ((LivingCrew) crew).getOwnerShip() != player)
                continue;
            if (crew.getHealth() > 0f || cloning)
                return false;
        }
        return true;
    }

    public void updatePlayerCrew() {
        // Ignore calls during deserialisation
        if (isCurrentlyLoadingSave)
            return;

        playerCrew.clear();

        addPlayerCrew(player.getCrew());
        if (player.getClonebay() != null)
            addPlayerCrew(player.getClonebay().getQueue());
        if (enemy != null)
            addPlayerCrew(enemy.getCrew());
    }

    private <T extends AbstractCrew> void addPlayerCrew(List<T> crewList) {
        for (AbstractCrew crew : crewList) {
            // Filter out drones
            if (!(crew instanceof LivingCrew living))
                continue;

            // Filter out intruders, not including mind-controlled crew.
            if (living.getOwnerShip() != player)
                continue;

            playerCrew.add(living);
        }
    }

    public List<LivingCrew> getPlayerCrew() {
        return playerCrew;
    }

    public Map<Hotkey, HotkeyButton> getReverseHotkeyBindings() {
        return Collections.unmodifiableMap(reverseHotkeyBindings);
    }

    public String getButtonNameForHotkey(String hotkeyID, String defaultName) {
        Hotkey key = getHotkeyManager().getByID().get(hotkeyID);
        if (key == null) {
            return defaultName;
        }

        HotkeyButton button = reverseHotkeyBindings.get(key);
        if (button == null) {
            return defaultName;
        }

        // Key names read in upper case wherever the original shows them
        // (tooltips "Hotkey: J", the pause banner, the controls page).
        return getTranslator().get(button.getText()).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Update our cache of which key each hotkey is bound to.
     * <p>
     * This should be called if you modify the keybinds inside the [SaveProfile].
     */
    public void updateHotkeyBindings() {
        assert mainGame != null;
        getHotkeyManager().calculateBindings(mainGame.getProfile(), forwardHotkeyBindings, reverseHotkeyBindings);
    }

    private void setEnemyIsHostile(boolean enemyIsHostile) {
        if (this.enemyIsHostile == enemyIsHostile)
            return;

        this.enemyIsHostile = enemyIsHostile;
        if (!enemyIsHostile) browserAchievements.enemyBecamePeaceful();

        if (player != null)
            player.enemyShipUpdated();

        if (enemy != null) {
            enemy.enemyShipUpdated();

            // Turn the enemy weapons off - this is only a visual thing at
            // this point, since the AI isn't being updated to fire them.
            for (Ship.Hardpoint hardpoint : enemy.getHardpoints()) {
                if (hardpoint.getWeapon() == null || enemy.getWeapons() == null)
                    continue;

                enemy.getWeapons().setWeaponPower(hardpoint.getWeapon(), false);
            }
        }
    }

    void showEventDialogue(Event event, int seed) {
        // The UI is null for automated tests
        if (shipUI != null) {
            shipUI.showEventDialogue(event, seed);
        }
    }

    /** A ship's ending event, created with the ship event's seed (see EventCreator.createShipEnding). */
    public void showShipEnding(IEvent event, Ship ship) {
        CreatedEvent created = eventCreator.createShipEnding(event, ship);
        if (created != null) {
            showEventDialogue(created);
        }
    }

    void showEventDialogue(CreatedEvent event) {
        if (shipUI != null) {
            shipUI.showEventDialogue(event);
        }
    }

    public void setLootPool(LootPool pool) {
        lootPool = pool;
    }

    public boolean isPlayerCrewFull() {
        if (xyz.znix.xftl.hs.HS.active && player != null) return xyz.znix.xftl.hs.ship.HsShipState.isCrewFull(player, playerCrew.size());
        return playerCrew.size() >= 8;
    }

    public boolean playerHasTooManyCrew() {
        if (xyz.znix.xftl.hs.HS.active && player != null) return xyz.znix.xftl.hs.ship.HsShipState.isCrewOverFull(player, playerCrew.size());
        return playerCrew.size() > 8;
    }

    public interface RoomClickListener {
        void roomClicked(Room room, GameContainer gc);

        default boolean canTargetRoom(@NotNull Room room) {
            return true;
        }
    }

    public enum QuestAddResult {
        CURRENT_SECTOR,
        NEXT_SECTOR,
        TOO_LATE,
    }

    /**
     * This represents all the parsed resources that can be pulled from a {@link Datafile}.
     * <p>
     * It's kept separate so that stuff like restarting the game (or starting a new game
     * where the mods and Advanced Edition mode are the same) is fast.
     */
    public static class GameContent {
        public final Datafile datafile;
        public final boolean enableAdvancedEdition;

        public final BlueprintManager blueprintManager;
        public final EventManager eventManager;
        public final CrewNameManager nameManager;
        public final Animations animations;
        public final SoundManager sounds;
        public final Translator translator;
        public final ShipGenerator generator;
        public final Achievement.AchievementTable achievements;
        public final ShipFamily.FamilyTable shipFamilies;
        public final HotkeyManager hotkeyManager;

        // These are loaded on-demand
        private final Map<String, Image> images = new HashMap<>();
        private final Map<String, SILFontLoader> fonts = new HashMap<>();
        private final Map<String, Cursor> cursors = new HashMap<>();

        // The context that owns the images and fonts
        private final ResourceContext resourceContext = new ResourceContext();
        private ResourceContext audioContext = new ResourceContext();
        private boolean ownsAudio = true;

        // This is only here to avoid wasting heaps of resources in cont-save-load mode.
        private WindowRenderer windowRenderer;

        private ImagePrefetcher prefetcher;

        public GameContent(Datafile datafile, boolean enableAdvancedEdition) {
            this(datafile, enableAdvancedEdition, null);
        }

        public GameContent(Datafile datafile, boolean enableAdvancedEdition, GameContent previous) {
            this.datafile = datafile;
            this.enableAdvancedEdition = enableAdvancedEdition;

            blueprintManager = new BlueprintManager(datafile, enableAdvancedEdition);
            animations = new Animations(datafile);
            if (previous == null) {
                sounds = new RealSoundManager(datafile, audioContext);
            } else {
                sounds = previous.sounds;
                audioContext = previous.audioContext;
            }
            generator = new ShipGenerator(datafile, blueprintManager);
            xyz.znix.xftl.rendering.LanguageFonts.INSTANCE.useDatafile(datafile);
            translator = new Translator(datafile, xyz.znix.xftl.Languages.getCurrent());
            eventManager = new EventManager(datafile, translator, blueprintManager);
            nameManager = new CrewNameManager(datafile, xyz.znix.xftl.Translator.Companion.isEnglish(xyz.znix.xftl.Languages.getCurrent()) ? "en" : xyz.znix.xftl.Languages.getCurrent());
            achievements = new Achievement.AchievementTable(datafile);
            hotkeyManager = new HotkeyManager();

            blueprintManager.finishLoading(this);
            // the weapons' stat boosts first, then hyperspace.xml's crew and augments (HsWeapons.loadContent)
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewContent.load(blueprintManager);

            // TODO load this via the usual asset system, so mods can override it
            BrowserSaxBuilder builder = com.pocketwormhole.android.SafeXML.builder();
            try {
                Document doc = builder.build(xyz.znix.xftl.sys.XftlResources.open("assets/data/xftl_ships.xml"));
                shipFamilies = new ShipFamily.FamilyTable(doc);
            } catch (JDOMException | IOException e) {
                throw new RuntimeException("Failed to load ship data", e);
            }
            if (previous != null) previous.ownsAudio = false;
        }

        /**
         * Wrap some pre-loaded content.
         * <p>
         * This is used by automated tests.
         */
        public GameContent(
                Datafile datafile, boolean enableAdvancedEdition,
                BlueprintManager blueprintManager, EventManager eventManager,
                CrewNameManager nameManager, Animations animations,
                SoundManager sounds, Translator translator,
                ShipGenerator generator, Achievement.AchievementTable achievements,
                ShipFamily.FamilyTable shipFamilies, HotkeyManager hotkeyManager
        ) {
            this.datafile = datafile;
            this.enableAdvancedEdition = enableAdvancedEdition;
            this.blueprintManager = blueprintManager;
            this.eventManager = eventManager;
            this.nameManager = nameManager;
            this.animations = animations;
            this.sounds = sounds;
            this.translator = translator;
            this.generator = generator;
            this.achievements = achievements;
            this.shipFamilies = shipFamilies;
            this.hotkeyManager = hotkeyManager;
        }

        public void freeResources() {
            if (prefetcher != null) prefetcher.cancelAll();
            resourceContext.freeAll();
            if (ownsAudio) audioContext.freeAll();
        }

        public Image getImg(String path) {
            path = xyz.znix.xftl.Languages.localImage(datafile, path);
            Image image = images.get(path);
            if (image == null) {
                image = loadImage(datafile.get(path));
                images.put(path, image);
            }
            return image;
        }

        /** Loads an image now, taking over a background load of it if one is under way. */
        public Image loadImage(FTLFile file) {
            Image image = prefetcher == null ? null : prefetcher.takeNow(file.getName());
            return image != null ? image : datafile.readImage(resourceContext, file);
        }

        /** Whether the image at path is already loaded, for the checks. */
        public boolean hasImage(String path) {
            return images.containsKey(path);
        }

        /** Loads images a few milliseconds a frame, ahead of the frame that draws them. */
        public ImagePrefetcher getPrefetcher() {
            if (prefetcher == null) prefetcher = new ImagePrefetcher(datafile, resourceContext, images);
            return prefetcher;
        }

        public SILFontLoader getFont(String name) {
            SILFontLoader font = fonts.get(name);
            if (font == null) {
                font = new SILFontLoader(resourceContext, datafile, datafile.get("fonts/" + name + ".font"));
                fonts.put(name, font);
            }
            return new SILFontLoader(font);
        }

        public WindowRenderer getWindowRenderer() {
            if (windowRenderer == null) windowRenderer = new WindowRenderer(
                getImg("img/window_base.png"), getImg("img/window_outline.png"), getImg("img/window_mask.png"));
            return windowRenderer;
        }
    }

    private class GameUIProvider implements UIProvider {
        @NotNull
        @Override
        public SILFontLoader getFont(@NotNull String name) {
            return InGameState.this.getFont(name);
        }

        @NotNull
        @Override
        public Image getImg(@NotNull String path) {
            return InGameState.this.getImg(path);
        }

        @Nullable
        @Override
        public String translate(@NotNull String key) {
            return getTranslator().getTranslations().get(key);
        }

        @Nullable
        @Override
        public Colour getDebugOutlineColour(@NotNull Widget widget) {
            return null;
        }

        @NotNull
        @Override
        public WindowRenderer getWindowRenderer() {
            return content.windowRenderer;
        }
    }

    private long intruderCount() {
        return player.getCrew().stream().filter(c -> c.getMode() == AbstractCrew.SlotType.INTRUDER).count();
    }

    /**
     * WorldManager::AddBoarders(BoardingEvent&) (0x613440): nothing comes aboard once the ship holds 15 intruders or when
     * the amount is not positive; otherwise BoarderSpawn (0x408260) places them and the call returns true.
     */
    public boolean addBoarders(String race, int amount, boolean breach) {
        if (player == null || intruderCount() >= 15 || amount <= 0) return false;
        placeIntruders(new ArrayList<>(), amount, race, breach);
        return true;
    }

    private void placeIntruders(List<LivingCrewInfo> explicit, int boarderCount, String boarderRace, boolean breach) {
        // 0x408260: the room is the first draw, then each boarder's name, class and look.
        int intruderRoomId = ftl.browser.NativeRandom.INSTANCE.nextInt(player.getRooms().size());
        Room intruderRoom = player.getRooms().get(intruderRoomId);

        List<LivingCrewInfo> infos = new ArrayList<>(explicit);
        List<Boolean> neverReady = new ArrayList<>();
        for (int i = 0; i < infos.size(); i++) neverReady.add(null);
        for (int i = 0; i < boarderCount; i++) {
            NativeCrewFactory.Boarder boarder = getCrewFactory().createBoarder(this, boarderRace);
            infos.add(boarder.getInfo());
            neverReady.add(boarder.getCrystalNeverReady());
        }

        for (int i = 0; i < infos.size(); i++) {
            LivingCrew intruder = player.addCrewMember(infos.get(i), false, true);
            if (intruder instanceof CrewCrystal && neverReady.get(i) != null)
                ((CrewCrystal) intruder).initEnemyPower(neverReady.get(i));

            RoomPoint slot = player.findSpaceForCrew(intruderRoom, AbstractCrew.SlotType.INTRUDER);
            intruder.jumpTo(slot);
            if (!breach) {
                intruder.startTeleportArrive();
            }
        }

        if (breach) {
            intruderRoom.spawnBreach();
            player.setHealth(player.getHealth() - 1);
        }
    }

    /**
     * Hyperspace WorldManager::SwitchShip / SwitchShipTransfer (CustomShips.cpp, the console's SWITCH and SWITCHALL): the
     * location is cleared (the ship there goes with it), then the player's ship is built again from [blueprintName] as a
     * run start builds it (ShipBuilder::GetShip, CompleteShip::Restart: its systems, weapons, drones, augments and crew,
     * the Hyperspace start equipment and crew) and the command GUI restarts (a new PlayerShipUI). Returns the old ship, so
     * SWITCHALL can carry its state over. Hyperspace only (console).
     */
    public Ship hsRebuildPlayerShip(String blueprintName) {
        return hsRebuildPlayerShip(blueprintName, null);
    }

    /** [crew] non-null: SWITCHALL's saved crew instead of the blueprint's (and no Hyperspace start crew or equipment). */
    public Ship hsRebuildPlayerShip(String blueprintName, List<LivingCrewInfo> crew) {
        if (enemy != null) {
            setEnemy(null);
            currentBeacon.setShip(null);
        }
        // The scene and the location's background objects stay (oracle sw2-switch: the start beacon's station after
        // SWITCH); only the ship there goes.
        Ship old = player;
        if (crew == null) {
            hsStartEquipment = xyz.znix.xftl.hs.ship.HsShipStart.resolve(blueprintManager.getShip(blueprintName), blueprintManager);
            hsStartCrew = xyz.znix.xftl.hs.ship.HsShipState.drawStartingCrew(blueprintName, this);
            createNewPlayerShip(blueprintName, null);
        } else {
            hsStartEquipment = null;
            hsStartCrew = null;
            hsStartRooms = null;
            createNewPlayerShip(blueprintName, null, crew);
        }
        // ShipManager::Restart leaves scrap and fuel at 0 (a run start sets them in StarMap::NewGame); the Hyperspace
        // definition's startingScrap / startingFuel still apply (ShipManager_Extend::Initialize(restarting)). Oracle
        // sw2-switch: Kestrel 20 scrap 12 fuel -> MU_FED_BATTLESHIP 0 / 0, its 12 missiles and 7 reactor bars.
        player.setScrap(0);
        player.setFuelCount(0);
        xyz.znix.xftl.hs.ship.HsShipState.applyStartingResources(player);
        if (mainGame != null) shipUI = new PlayerShipUI(player, this);
        updatePlayerCrew();
        return old;
    }

    /**
     * CommandGui::ClearLocation (1.6.14 0x532940): its crew-control step (0x503110, also on a restart) empties the selected
     * and hovered crew lists. It runs from WorldManager::OnLoop once the enemy is destroyed or has escaped, and from
     * WorldManager::ClearLocation (0x614d00) when that does its work: the console's LOAD drops the selection (native
     * capture ps-fu4-van fu4-sel s-load-open), a jump does not (s-jumped).
     */
    public void commandGuiClearLocation() {
        if (shipUI != null) shipUI.clearCrewSelection();
    }

    /** The location part of WorldManager::ClearLocation (0x614d00): the scene is kept, the hazard and the event go. */
    public void clearLocation() {
        if (currentBeacon == null) return;
        // WorldManager::ClearLocation (0x614d00): the player's boarders on a ship still here are lost (KillEveryone(true)).
        if (enemy != null) xyz.znix.xftl.systems.BoardersLost.clearLocation(this, enemy);
        currentBeacon.captureSpace(gameMap);
        currentBeacon.clearEnvironment();
        // A hostile ship still here is saved into the event (ShipEvent::actualBlueprint), which ClearEvent's +0x1d0
        // branch reads: the location keeps its hazard, now and on every later clear.
        if (currentBeacon.getShip() != null) currentBeacon.shipSaved = true;
        currentBeacon.clearEvent(false, true);
    }
}
