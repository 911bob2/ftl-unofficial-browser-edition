package ftl.browser;
import java.util.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSBody;
import org.newdawn.slick.InputListener;
import xyz.znix.xftl.game.MainGame;
import xyz.znix.xftl.rendering.*;
import xyz.znix.xftl.sys.*;
public final class BrowserEngine {
    @JSBody(script="window.open('https://store.steampowered.com/app/590380/Into_the_Breach/', '_blank', 'noopener');")
    public static native void openOriginalGamePromotion();
    /** The native Fullscreen option toggles the website's game container. */
    @JSBody(script="return !!document.fullscreenElement;")
    public static native boolean isFullscreen();
    @JSBody(script="if(document.fullscreenElement)document.exitFullscreen().catch(function(){});"
        +"else{var c=document.getElementById('game-container');if(c&&c.requestFullscreen)c.requestFullscreen().catch(function(){});}")
    public static native void toggleFullscreen();
    /** A haptic tick of the touch HUD (TouchHaptics); the browser has none. */
    public static void haptic(int kind,int count){}
    private static MainGame game;private static final Container container=new Container();private static Graphics graphics;
    public static void main(String[] args){}
    @JSExport public static void start(boolean ae,String run,String profile)throws Exception{
        BrowserDebug.installOutputBridge(); BrowserDebug.tick=0;
        if(game!=null)game.shutdown();
        container.input.removeAllListeners();container.input.reset();
        BrowserFiles.enableAE=ae;BrowserFiles.hydrate(run,profile);BrowserFiles.hydrateSettings();xyz.znix.xftl.hs.HS.init();graphics=new Graphics();graphics.markCurrentImageTransformSource();ShaderProgramme.getSHADER_SCREEN_SIZE().set(1280,720);game=new MainGame(new MainGame.CommandLineArgs());game.init(container);
        // Hyperspace starts its Lua state at start-up (Global.cpp:138, new LuaScriptInit()), scripts or not: the console's LUA works on plain Hyperspace too
        if(xyz.znix.xftl.hs.HS.active||!xyz.znix.xftl.hs.HS.scripts.isEmpty())xyz.znix.xftl.hs.lua.HsLua.startForSession(game,graphics);
    }
    /** CFPS::OnLoop (0x5ba020) caps a frame at half a speed unit, 1/32 s: a slower frame slows the game down. */
    static final float MAX_STEP = 1f / 32f;
    /** With HS scripts a real frame (dt > 0) goes through Hyperspace.FPS (CFPS::OnLoop: speedLevel scales the step), MouseControl::Reset and OnLoop, and fires ON_TICK once. The console's SPEED sets speedLevel in any run. */
    @JSExport public static void step(float dt)throws Exception{boolean hs=xyz.znix.xftl.hs.HS.luaActive&&dt>0;float step=hs||dt>0&&xyz.znix.xftl.hs.lua.LuaFps.speedLevel!=0?xyz.znix.xftl.hs.lua.LuaFps.onLoop(dt):Math.min(dt,MAX_STEP);if(hs)xyz.znix.xftl.hs.lua.LuaAdaptersWP7Ui.frame(step);game.update(container,step);if(hs)xyz.znix.xftl.hs.lua.LuaEvents.onTick();if(dt>0)BrowserDebug.tick++;container.input.clearInputPressedRecord();if(BrowserDebug.enabled)BrowserDebug.record("simulation","state", "{\"dt\":"+dt+",\"state\":"+BrowserDebug.snapshot(game)+"}");}
    @JSExport public static void render()throws Exception{game.render(container,graphics);}
    /** Diagnostics that render frames (BrowserWp4DroneOrderDiagnostics). */ static Graphics diagnosticGraphics(){return graphics;}
    /** Diagnostics that move or click the pointer (BrowserHsSweepWorldDiagnostics): type 0 move, 1 press, 2 release. */ static void diagnosticMouse(int x,int y,int type,int button){mouse(x,y,type,button);}
    @JSExport public static void renderFrame(float dt)throws Exception{
        if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state) state.setRenderingDeltaTime(dt);
        else if(game.getCurrentState() instanceof xyz.znix.xftl.game.SettingsMenuState state) state.prepareBrowserRender(dt);
        render();
    }
    @JSExport public static void pause(){game.autoPauseIfInFlight();}
    /** Mods: which loaded Hyperspace features are not implemented (JSON, see HS.capabilityReport). */
    /** ships workstream diagnostics: "defs" | "ships" | "start <ship> [difficulty]" | "menu" (JSON / text). */
    @JSExport public static String hsShipsProbe(String what){return BrowserHsShipsDiagnostics.INSTANCE.run(game,what,container,graphics);}
    @JSExport public static String hsCapabilityReport(){return xyz.znix.xftl.hs.HS.capabilityReport();}
    /** Hyperspace's start-up error boxes of this start (HS.errorBoxes), a JSON array of texts; the page shows each before the first frame. */
    @JSExport public static String hsErrorBoxes(){StringBuilder b=new StringBuilder("[");for(String t:xyz.znix.xftl.hs.HS.errorBoxes){if(b.length()>1)b.append(',');b.append(BrowserDebug.quote(t));}return b.append(']').toString();}
    /** Mods: per-callback Lua calls/instructions/time and bridge state (JSON); reset clears the counters. */
    @JSExport public static String hsProfile(boolean reset){return xyz.znix.xftl.hs.lua.HsLua.profileJson(reset);}
    /** Diagnostics mode hs-lua: Lua runtime against real objects (bench, in-situ frames, script API semantics). */
    @JSExport public static String hsLuaProbe(int frames){return xyz.znix.xftl.hs.lua.LuaProbe.INSTANCE.run(game,container,graphics,frames);}
    // Hyperspace Lua bridge: imports of web/vendor/hs-lua.wasm (see web/hs-lua.mjs). Reached only with Hyperspace.
    @JSExport public static void hsLuaLog(int kind,String text){xyz.znix.xftl.hs.lua.HsLua.log(kind,text);}
    @JSExport public static int hsLuaRand32(){return xyz.znix.xftl.hs.lua.LuaRng.rand32();}
    @JSExport public static void hsLuaSrand32(int seed){xyz.znix.xftl.hs.lua.LuaRng.srand32(seed);}
    @JSExport public static double hsLuaGetn(int h,int a){return xyz.znix.xftl.hs.lua.LuaBridge.getn(h,a);}
    @JSExport public static String hsLuaGets(int h,int a){return xyz.znix.xftl.hs.lua.LuaBridge.gets(h,a);}
    @JSExport public static double hsLuaGeto(int h,int a){return xyz.znix.xftl.hs.lua.LuaBridge.geto(h,a);}
    @JSExport public static String hsLuaSetn(int h,int a,double v){return xyz.znix.xftl.hs.lua.LuaBridge.setn(h,a,v);}
    @JSExport public static String hsLuaSets(int h,int a,String v){return xyz.znix.xftl.hs.lua.LuaBridge.sets(h,a,v);}
    @JSExport public static String hsLuaSeto(int h,int a,int h2,int t2){return xyz.znix.xftl.hs.lua.LuaBridge.seto(h,a,h2,t2);}
    @JSExport public static int hsLuaCall(int mid,int n){return xyz.znix.xftl.hs.lua.LuaBridge.call(mid,n);}
    @JSExport public static void hsLuaRelease(int h){xyz.znix.xftl.hs.lua.Handles.release(h);}
    @JSExport public static int hsLuaTableid(int h){return xyz.znix.xftl.hs.lua.Handles.tableId(h);}
    @JSExport public static void hsLuaRegistered(int kind,int id){xyz.znix.xftl.hs.lua.HsLua.registered(kind,id);}
    /** Mods: HS gate and modded run/profile save checks (BrowserHsDiagnostics); returns "PASS ..." or throws. */
    @JSExport public static String hsCoreProbe(String ship){return BrowserHsDiagnostics.INSTANCE.run(game,ship);}
    @JSExport public static String hsRenderProbe(String cmd){return BrowserHsRenderDiagnostics.INSTANCE.run(game,container,cmd);}
    @JSExport public static String hsWeaponsProbe(String part){return BrowserHsWeaponsDiagnostics.INSTANCE.run(game,container,part);}
    /** Mods: HS races and per-ship starting crew stats (BrowserStatCrewDiagnostics); returns JSON. */
    @JSExport public static String hsStatCrewProbe(String ships){return BrowserStatCrewDiagnostics.INSTANCE.run(game,ships);}
    /** Mods: the oracle statcrew-powers Lua scenario replayed here (BrowserStatCrewDiagnostics.luaScenario). */
    @JSExport public static String hsStatCrewLua(String code,String keys,int frames,String reloadAt){return BrowserStatCrewDiagnostics.INSTANCE.luaScenario(game,code,keys,frames,reloadAt==null?"":reloadAt);}
    @JSExport public static String hsEventsProbe(String script){return BrowserHsEventsDiagnostics.INSTANCE.run(game,container,graphics,script);}
    /** Mods: a full policy-driven run over all sectors (BrowserHsFullRunDiagnostics); returns JSON lines. */
    @JSExport public static String hsFullRunProbe(String script){return BrowserHsFullRunDiagnostics.INSTANCE.run(game,container,graphics,script);}
    /** fix-runtime diagnostics: "ships [from] [count]" (evasion, dodge, paths and crew AI on every enemy blueprint; JSON). */
    @JSExport public static String hsRuntimeProbe(String what){return BrowserHsRuntimeDiagnostics.INSTANCE.run(game,container,what);}
    @JSExport public static String hsSystemsProbe(String part){return BrowserHsSystemsDiagnostics.INSTANCE.run(game,container,part);}
    @JSExport public static String hsWp2Probe(String cmd){return BrowserHsWp2Diagnostics.INSTANCE.run(game,container,cmd);}
    @JSExport public static String hsCrewUiProbe(String part){return BrowserHsCrewUiDiagnostics.INSTANCE.run(game,container,graphics,part);}
    @JSExport public static String hsWp1Probe(String part){return BrowserHsWp1Diagnostics.INSTANCE.run(game,container,graphics,part);}
    @JSExport public static String hsWp6MapProbe(String cmd){return BrowserHsWp6MapDiagnostics.INSTANCE.run(game,container,graphics,cmd);}
    @JSExport public static String hsWp6EventsProbe(String cmd){return BrowserHsWp6EventsDiagnostics.INSTANCE.run(game,container,graphics,cmd);}
    @JSExport public static String hsWp7UiProbe(String cmd){return BrowserHsWp7UiDiagnostics.INSTANCE.run(game,container,graphics,cmd);}
    @JSExport public static void parityNoBackground(boolean value){BrowserDebug.parityNoBackground=value;}
    /** UI parity captures only: start the original's continue.sav (base64). */
    @JSExport public static void parityImportVanillaSave(String base64){game.startVanillaSaveForParity(java.util.Base64.getDecoder().decode(base64));}
    @JSExport public static String nativeImportProbe(String run,String profile,String prefix){return BrowserNativeImportDiagnostics.INSTANCE.run(game,run,profile,prefix);}
    @JSExport public static String parityDescribeSave(String base64){return BrowserParity.INSTANCE.describeSave(game.parseVanillaSaveForParity(java.util.Base64.getDecoder().decode(base64)));}
    @JSExport public static boolean parityShowEvent(String name,String needle){return game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state && BrowserParity.INSTANCE.showEvent(state,name,needle);}
    @JSExport public static String soundTrace(boolean start){if(start)xyz.znix.xftl.game.SoundTrace.start();return xyz.znix.xftl.game.SoundTrace.json();}
    @JSExport public static void focusChanged(boolean focused){if(game!=null)game.setBrowserFocus(focused);}
    @JSExport public static String optionsProbe(){return BrowserOptionsDiagnostics.INSTANCE.run(game,container,graphics);}
    @JSExport public static String gameplayParityProbe(){return BrowserGameplayParityDiagnostics.INSTANCE.run(game,container,graphics);}
    /** Diagnostics: the reward icons across content swaps, compared pixel for pixel (GameplayParityAssets7Checks). */
    @JSExport public static String resourceIconsProbe(){return GameplayParityAssets7Checks.INSTANCE.run(game,container,graphics);}
    /** Diagnostics: the tree, map and created events for given seeds as JSON (see BrowserParityGeneration). */
    @JSExport public static String parityGeneration(String json){return BrowserParityGeneration.INSTANCE.run(game,container,json);}
    @JSExport public static String optionsFixture(String kind){return BrowserOptionsDiagnostics.INSTANCE.fixture(game,container,graphics,kind);}
    @JSExport public static void resetInput(){if(game!=null&&game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)state.getShipUI().cancelDrag();container.input.reset();}
    @JSExport public static void shutdown(){if(xyz.znix.xftl.hs.HS.luaActive)xyz.znix.xftl.hs.lua.HsLua.shutdown();if(game!=null){game.shutdown();game=null;}container.input.removeAllListeners();container.input.reset();}
    @JSExport public static boolean inFlight(){return game.isInFlight();}
    @JSExport public static void save(){
        if(game.isTutorialRunning()){
            BrowserDebug.record("persistence","save.ignored","{\"kind\":\"run-profile\",\"reason\":\"tutorial\"}");
            return;
        }
        game.writeRunSave();BrowserFiles.setProfile(BrowserXml.write(game.getProfile().save()));
    }
    @JSExport public static String runSave(){return BrowserFiles.getRun();}
    private static java.util.List<String> reportedDropped=xyz.znix.xftl.savegame.MissingContent.getDropped();
    /** What the last run load dropped because the game files lack it (MissingContent), newline separated, once per load; null when nothing new. */
    @JSExport public static String missingContent(){
        java.util.List<String> dropped=xyz.znix.xftl.savegame.MissingContent.getDropped();
        if(dropped==reportedDropped)return null;
        reportedDropped=dropped;
        StringBuilder text=new StringBuilder();
        for(String name:dropped){if(text.length()>0)text.append('\n');text.append(name);}
        return text.length()==0?null:text.toString();
    }
    /**
     * What a save made now keeps, for Save and close: "run" (an ordinary run, saved whole), "ended" (the run is over, so only
     * the profile is kept), "tutorial" (never saved, as in the original where CommandGui::CanSave is false) or "profile"
     * (no run is in progress, e.g. the hangar or the main menu).
     */
    @JSExport public static String saveKind(){
        if(game==null)return "profile";
        if(game.isTutorialRunning())return "tutorial";
        if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)return state.browserRunEnded?"ended":"run";
        return "profile";
    }
    /** Completes what a save cannot hold (a jump in progress) so the next save() is a consistent point. Returns whether anything changed. */
    @JSExport public static boolean saveSettle(){return game!=null&&game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state&&!game.isTutorialRunning()&&state.browserSettleForSave();}
    @JSExport public static String profileSave(){return BrowserFiles.getProfile();}
    @JSExport public static int frameRateLimit(){return game.getProfile().getFrameRateLimit();}
    @JSExport public static void diagnosticFrameLimit(int limit){game.getProfile().setFrameRateLimit(limit);}
    @JSExport public static void diagnosticSeed(String seed){BrowserRandom.restore(seed);}
    @JSExport public static String diagnosticRandom(){return BrowserRandom.snapshot();}
    @JSExport public static void newRunLayout(String ship,int difficulty,int layoutSeed){NativeRandom.setLayoutSeedOverride(layoutSeed);NativeRandom.trace=new ArrayList<Integer>();game.startNewGame(ship,xyz.znix.xftl.game.Difficulty.values()[difficulty],null);}
    /** Diagnostics: the draws recorded since newRunLayout (seeds are Integer.MIN_VALUE then the seed), comma separated. */
    @JSExport public static String nativeTrace(){ArrayList<Integer> t=NativeRandom.trace;if(t==null)return "";StringBuilder b=new StringBuilder();for(int i=0;i<t.size();i++){if(i>0)b.append(',');b.append(t.get(i));}return b.toString();}
    /** Diagnostics: the start beacon's scene built again from a native capture's draws (comma separated), then shown. */
    @JSExport public static String sceneReplay(String csv){String[] p=csv.split(",");int[] v=new int[p.length];for(int i=0;i<p.length;i++)v[i]=Integer.parseInt(p[i].trim());xyz.znix.xftl.game.InGameState g=(xyz.znix.xftl.game.InGameState)game.getCurrentState();g.getGameMap().setSpaceBack(null);g.getGameMap().setSpacePlanet(null);NativeRandom.startReplay(v);xyz.znix.xftl.environment.BeaconScene sc=xyz.znix.xftl.environment.BeaconScene.create(g,g.getCurrentBeacon());NativeRandom.startReplay(null);g.getCurrentBeacon().clearEnvironment();return "{\"back\":\""+sc.backdrop+"\",\"planet\":\""+sc.planet+"\",\"box\":["+sc.planetW+","+sc.planetH+","+sc.planetX+","+sc.planetY+","+sc.planetTurned+"]}";}
    @JSExport public static void newRun(String ship,int difficulty){game.startNewGame(ship,xyz.znix.xftl.game.Difficulty.values()[difficulty],null);}
    @JSExport public static void restore(String xml){game.loadSavedGame(BrowserXml.parse(xml));}
    public static MainGame getGame(){return game;}
    @JSExport public static void debugConfig(boolean enabled){BrowserDebug.enabled=enabled;}
    @JSExport public static String debugSnapshot(){return game==null?"{}":BrowserDebug.snapshot(game);}
    @JSExport public static String diagnosticRun(){return game!=null&&game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state ? BrowserXml.write(state.saveGameState()) : null;}
    @JSExport public static String diagnosticProfile(){return game==null ? null : BrowserXml.write(game.getProfile().save());}
    @JSExport public static String diagnosticSettings(){return game==null ? null : BrowserXml.write(game.getProfile().saveSettings());}
    @JSExport public static void consoleOpen(){if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)state.openDebugConsole();}
    /** The Console button: closes the console when it is open (as Escape does), opens it otherwise. Returns whether it is open now. */
    @JSExport public static boolean consoleToggle(){
        if(!(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state))return false;
        if(state.isDebugConsoleVisible())state.cancelMenu();else state.openDebugConsole();
        return state.isDebugConsoleVisible();
    }
    @JSExport public static void consoleExecute(String command){if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)state.getDebugConsole().execute(command);}
    /** The device's "Debug console" setting (docs/CONSOLE.md): the console opens on ` and \ in every mode. */
    @JSExport public static void consoleConfig(boolean enabled){xyz.znix.xftl.devutil.ConsoleGate.setting=enabled;}
    /** Tests (tests/console.mjs): what the console's commands change, as JSON. */
    @JSExport public static String consoleProbe(){return game==null?"{}":BrowserConsoleDiagnostics.INSTANCE.state(game);}
    /** tests/console.mjs: the get/unlock picker's items of a kind and the data's names (BrowserConsoleDiagnostics.items). */
    @JSExport public static String consoleItems(String command,String kind){return game==null?"{}":BrowserConsoleDiagnostics.INSTANCE.items(game,command,kind);}
    /** tests/overcap.mjs: the open ship window's over-capacity and crew boxes for the mouse or touch layout (BrowserOvercapDiagnostics); action "page" turns the touch pager. */
    @JSExport public static String overcapProbe(boolean touch,String action){return game==null?"{}":BrowserOvercapDiagnostics.INSTANCE.probe(game,touch,action);}
    /** Whether the profile has that ship layout unlocked, in any state (the hangar too). */
    @JSExport public static boolean consoleLayoutUnlocked(String ship){return game!=null&&game.getProfile().isShipUnlocked(ship);}
    /** Tests: the console's last output lines, newest last. */
    @JSExport public static String consoleOutput(){if(!(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state))return "";java.util.List<String> l=state.getDebugConsole().outputText();return String.join("\n",l.subList(Math.max(0,l.size()-200),l.size()));}
    @JSExport public static void menuCancel(){
        MainGame.GameState current = game.getCurrentState();
        if(current instanceof xyz.znix.xftl.game.InGameState state) state.cancelMenu();
        else {
            current.keyPressed(xyz.znix.xftl.sys.Input.KEY_ESCAPE,(char)0);
            if (game.getCurrentState() == current) current.keyReleased(xyz.znix.xftl.sys.Input.KEY_ESCAPE,(char)0);
        }
    }
    @JSExport public static String shipList(){return BrowserDiagnostics.INSTANCE.shipList(game);}
    @JSExport public static String probe(String ship,int difficulty){return BrowserDiagnostics.INSTANCE.probe(game,ship,difficulty,container,graphics);}
    @JSExport public static String encounterList(){return BrowserDiagnostics.INSTANCE.encounterList(game);}
    @JSExport public static String encounterProbe(String name,int sector){return BrowserDiagnostics.INSTANCE.encounterProbe(game,name,sector,container,graphics);}
    @JSExport public static String mechanicsProbe(){return BrowserDiagnostics.INSTANCE.mechanicsProbe(game,container,graphics);}
    @JSExport public static String progressProbe(){return BrowserDiagnostics.INSTANCE.progressProbe(game,container,graphics);}
    @JSExport public static String combatProbe(){return BrowserDiagnostics.INSTANCE.combatProbe(game,container,graphics);}
    @JSBody(params="phase",script="if(window.ftlTestProgress)window.ftlTestProgress(phase);") public static native void diagnosticProgress(String phase);
    @JSBody(params="step",script="if(window.ftlTestCapture)window.ftlTestCapture(step);") public static native void diagnosticCapture(String step);
    @JSExport public static String interactionProbe(){return BrowserDiagnostics.INSTANCE.interactionProbe(game,container,graphics);}
    @JSExport public static String warningsProbe(){return BrowserWarningDiagnostics.INSTANCE.run(game,container,graphics);}
    @JSExport public static void warningFixture(){BrowserWarningDiagnostics.INSTANCE.boarderFixture(game,container);}
    @JSExport public static String aeProbe(){return BrowserDiagnostics.INSTANCE.aeProbe(game,container,graphics);}
    @JSExport public static String hangarProbe(){return BrowserHangarDiagnostics.INSTANCE.run(game,container,graphics);}
    @JSExport public static String droneProbe(){return BrowserDiagnostics.INSTANCE.droneProbe(game,container,graphics);}
    @JSExport public static String unlockProbe(){return BrowserDiagnostics.INSTANCE.unlockProbe(game,container,graphics);}
    @JSExport public static String achievementProbe(){return BrowserDiagnostics.INSTANCE.achievementProbe(game,container,graphics);}
    @JSExport public static String tutorialProbe(){BrowserTutorialDiagnostics.INSTANCE.definition(game);return "PASS 33 official desktop tutorial steps, instructions, continuation flags and arrows; ordinary run/profile/mode/RNG unchanged. Data coverage only; native presentation and human play remain unverified.";}
    @JSExport public static String tutorialIsolationProbe(){BrowserTutorialDiagnostics.INSTANCE.isolation(game,container,graphics);return "PASS tutorial save/profile/RNG/mode isolation, Continue, restart, failed initialization and shutdown; instruction flow is tested separately; native presentation and human play remain unverified.";}
    @JSExport public static String tutorialGatesProbe(){BrowserTutorialDiagnostics.INSTANCE.gates(game);return "PASS 33 desktop controller steps, 15 automatic gates, 4 latched Continue gates, wrong-action rejection and equipment/upgrade acceptance; native presentation and human play remain unverified.";}
    @JSExport public static String tutorialAdapterProbe(){BrowserTutorialAdapterDiagnostics.INSTANCE.run(game,container,graphics);return "PASS 33 tutorial steps observed from live engine actions, explicit/modal pause, crew movement/repair, airlocks/fire, weapon firing/reload and window acceptance; native presentation and human play remain unverified.";}
    @JSExport public static String tutorialOverlayProbe(){BrowserTutorialOverlayDiagnostics.INSTANCE.run(game,container,graphics);return "PASS all 33 tutorial instruction panels/arrows, Continue/Exit, modal priority, captured click/drag/reload, native power-box flashes/arrow pulse and unchanged state/RNG; native presentation and human play remain unverified.";}
    @JSExport public static String tutorialEntryProbe(){BrowserTutorialEntryDiagnostics.INSTANCE.run(game,container);return "PASS tutorial entry power, engine/crew damage, localized crew placement, random fire attempts, real repair/venting, save/reload idempotence and ordinary-run guards; encounter coverage is separate; native presentation and human play remain unverified.";}
    @JSExport public static String tutorialEncountersProbe(){BrowserTutorialEncounterDiagnostics.INSTANCE.run(game,container,graphics);return "PASS tutorial starting/map events, actual jump arrival, once-only missile delivery, modal deferral, ammunition replenishment, both pirate reward routes and save restoration; controlled encounter fixtures, not a complete played tutorial.";}
    @JSExport public static String tutorialPlayProbe(){BrowserTutorialPlayDiagnostics.INSTANCE.run(game,container,graphics);return "PASS complete 33-step tutorial action traversal, real repairs/venting, map jump, normal combat/rewards, equipment drag, upgrades and isolated exit; time-stepped automation, human play and native presentation comparison remain pending.";}
    @JSExport public static void performancePreset(int phase){BrowserDiagnostics.INSTANCE.performancePreset(game,container,phase);}
    @JSExport public static String campaignProbe(int difficulty,int seed){return BrowserDiagnostics.INSTANCE.campaignProbe(game,container,graphics,difficulty,seed);}
    @JSExport public static void mouse(int x,int y,int type,int button){BrowserInput in=container.input;if(xyz.znix.xftl.hs.HS.luaActive&&xyz.znix.xftl.hs.lua.LuaEvents.mouse(x,y,in.x,in.y,type,button,in.buttons.contains(0),in.buttons.contains(1),in.buttons.contains(2)))return;in.mouse(x,y,type,button);}
    /** The wheel with its distance too (web/game.mjs, from deltaY in screen units): the debug console scrolls by it (ConsoleScroller), everything else by the notch [change]. */
    @JSExport public static void wheel(int x,int y,int change,float pixels){xyz.znix.xftl.devutil.ConsoleScroller.hostWheelPixels=pixels;try{mouse(x,y,3,change);}finally{xyz.znix.xftl.devutil.ConsoleScroller.hostWheelPixels=Float.NaN;}}
    @JSExport public static void key(int key,int character,boolean down){if(xyz.znix.xftl.hs.HS.luaActive&&xyz.znix.xftl.hs.lua.LuaEvents.key(key,down))return;container.input.key(key,(char)character,down);}
    @JSBody(params="path",script="if(window.ftlSetCursor)window.ftlSetCursor(path);") private static native void cursor(String path);
    @JSBody(script="if(window.ftlExit)window.ftlExit();") private static native void exit();
    private static class Container implements GameContainer {
        final BrowserInput input=new BrowserInput();
        public xyz.znix.xftl.sys.Input getInput(){return input;} public int getWidth(){return 1280;}public int getHeight(){return 720;}
        public void exit(){BrowserEngine.save();BrowserEngine.exit();}
        public void setCursor(Cursor c){cursor(c==null?null:c.getKey());}
    }
    /** The mouse and keys, held buttons with the screen that took the press (HostInput). */
    private static class BrowserInput extends xyz.znix.xftl.sys.HostInput {}
}
