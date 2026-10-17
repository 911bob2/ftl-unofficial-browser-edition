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
    private static MainGame game;private static final Container container=new Container();private static Graphics graphics;
    public static void main(String[] args){}
    @JSExport public static void start(boolean ae,String run,String profile)throws Exception{
        BrowserDebug.installOutputBridge(); BrowserDebug.tick=0;
        if(game!=null)game.shutdown();
        container.input.removeAllListeners();container.input.reset();
        BrowserFiles.enableAE=ae;BrowserFiles.hydrate(run,profile);graphics=new Graphics();graphics.markCurrentImageTransformSource();ShaderProgramme.getSHADER_SCREEN_SIZE().set(1280,720);game=new MainGame(new MainGame.CommandLineArgs());game.init(container);
    }
    /** CFPS::OnLoop (0x5ba020) caps a frame at half a speed unit, 1/32 s: a slower frame slows the game down. */
    static final float MAX_STEP = 1f / 32f;
    @JSExport public static void step(float dt)throws Exception{game.update(container,Math.min(dt,MAX_STEP));if(dt>0)BrowserDebug.tick++;container.input.clearInputPressedRecord();if(BrowserDebug.enabled)BrowserDebug.record("simulation","state", "{\"dt\":"+dt+",\"state\":"+BrowserDebug.snapshot(game)+"}");}
    @JSExport public static void render()throws Exception{game.render(container,graphics);}
    @JSExport public static void renderFrame(float dt)throws Exception{
        if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state) state.setRenderingDeltaTime(dt);
        else if(game.getCurrentState() instanceof xyz.znix.xftl.game.SettingsMenuState state) state.prepareBrowserRender(dt);
        render();
    }
    @JSExport public static void pause(){game.autoPauseIfInFlight();}
    @JSExport public static void parityNoBackground(boolean value){BrowserDebug.parityNoBackground=value;}
    /** UI parity captures only: start the original's continue.sav (base64). */
    @JSExport public static void parityImportVanillaSave(String base64){game.startVanillaSaveForParity(java.util.Base64.getDecoder().decode(base64));}
    @JSExport public static String parityDescribeSave(String base64){return BrowserParity.INSTANCE.describeSave(game.parseVanillaSaveForParity(java.util.Base64.getDecoder().decode(base64)));}
    @JSExport public static boolean parityShowEvent(String name,String needle){return game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state && BrowserParity.INSTANCE.showEvent(state,name,needle);}
    @JSExport public static void focusChanged(boolean focused){if(game!=null)game.setBrowserFocus(focused);}
    @JSExport public static String optionsProbe(){return BrowserOptionsDiagnostics.INSTANCE.run(game,container,graphics);}
    @JSExport public static String gameplayParityProbe(){return BrowserGameplayParityDiagnostics.INSTANCE.run(game,container,graphics);}
    /** Diagnostics: the tree, map and created events for given seeds as JSON (see BrowserParityGeneration). */
    @JSExport public static String parityGeneration(String json){return BrowserParityGeneration.INSTANCE.run(game,container,json);}
    @JSExport public static String optionsFixture(String kind){return BrowserOptionsDiagnostics.INSTANCE.fixture(game,container,graphics,kind);}
    @JSExport public static void resetInput(){if(game!=null&&game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)state.getShipUI().cancelDrag();container.input.reset();}
    @JSExport public static void shutdown(){if(game!=null){game.shutdown();game=null;}container.input.removeAllListeners();container.input.reset();}
    @JSExport public static boolean inFlight(){return game.isInFlight();}
    @JSExport public static void save(){
        if(game.isTutorialRunning()){
            BrowserDebug.record("persistence","save.ignored","{\"kind\":\"run-profile\",\"reason\":\"tutorial\"}");
            return;
        }
        game.writeRunSave();BrowserFiles.setProfile(BrowserXml.write(game.getProfile().save()));
    }
    @JSExport public static String runSave(){return BrowserFiles.getRun();}
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
    @JSExport public static void newRun(String ship,int difficulty){game.startNewGame(ship,xyz.znix.xftl.game.Difficulty.values()[difficulty],null);}
    @JSExport public static void restore(String xml){game.loadSavedGame(BrowserXml.parse(xml));}
    public static MainGame getGame(){return game;}
    @JSExport public static void debugConfig(boolean enabled){BrowserDebug.enabled=enabled;}
    @JSExport public static String debugSnapshot(){return game==null?"{}":BrowserDebug.snapshot(game);}
    @JSExport public static String diagnosticRun(){return game!=null&&game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state ? BrowserXml.write(state.saveGameState()) : null;}
    @JSExport public static String diagnosticProfile(){return game==null ? null : BrowserXml.write(game.getProfile().save());}
    @JSExport public static void consoleOpen(){if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)state.openDebugConsole();}
    /** The Console button: closes the console when it is open (as Escape does), opens it otherwise. Returns whether it is open now. */
    @JSExport public static boolean consoleToggle(){
        if(!(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state))return false;
        if(state.isDebugConsoleVisible())state.cancelMenu();else state.openDebugConsole();
        return state.isDebugConsoleVisible();
    }
    @JSExport public static void consoleExecute(String command){if(game.getCurrentState() instanceof xyz.znix.xftl.game.InGameState state)state.getDebugConsole().execute(command);}
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
    @JSExport public static void mouse(int x,int y,int type,int button){container.input.mouse(x,y,type,button);}
    @JSExport public static void key(int key,int character,boolean down){container.input.key(key,(char)character,down);}
    @JSBody(params="path",script="if(window.ftlSetCursor)window.ftlSetCursor(path);") private static native void cursor(String path);
    @JSBody(script="if(window.ftlExit)window.ftlExit();") private static native void exit();
    private static class Container implements GameContainer {
        final BrowserInput input=new BrowserInput();
        public xyz.znix.xftl.sys.Input getInput(){return input;} public int getWidth(){return 1280;}public int getHeight(){return 720;}
        public void exit(){BrowserEngine.save();BrowserEngine.exit();}
        public void setCursor(Cursor c){cursor(c==null?null:c.getKey());}
    }
    private static class BrowserInput implements xyz.znix.xftl.sys.Input {
        int x,y;final Set<Integer> held=new HashSet<>(),pressed=new HashSet<>(),buttons=new HashSet<>();final List<InputListener> listeners=new ArrayList<>();
        public int getMouseX(){return x;}public int getMouseY(){return y;}public boolean isMouseButtonDown(int b){return buttons.contains(b);}
        public boolean isKeyPressed(int k){return pressed.remove(k);}public boolean isKeyDown(int k){return held.contains(k);}
        public void addListener(InputListener l){listeners.add(l);}public void removeAllListeners(){listeners.clear();}
        public void clearInputPressedRecord(){pressed.clear();}
        void reset(){for(int k:new ArrayList<>(held))key(k,(char)0,false);List<Integer> released=new ArrayList<>(buttons);buttons.clear();for(int b:released)for(InputListener l:new ArrayList<>(listeners))l.mouseReleased(b,x,y);held.clear();pressed.clear();}
        void mouse(int nx,int ny,int type,int b){int ox=x,oy=y;x=nx;y=ny;if(type==1)buttons.add(b);if(type==2&&!buttons.remove(b))return;
            for(InputListener l:new ArrayList<>(listeners)){if(type==0){l.mouseMoved(ox,oy,x,y);if(!buttons.isEmpty())l.mouseDragged(ox,oy,x,y);}else if(type==1)l.mousePressed(b,x,y);else if(type==2){l.mouseReleased(b,x,y);l.mouseClicked(b,x,y,1);}else if(type==3)l.mouseWheelMoved(b);}}
        void key(int k,char c,boolean down){if(down){held.add(k);pressed.add(k);}else held.remove(k);for(InputListener l:new ArrayList<>(listeners)){if(down)l.keyPressed(k,c);else l.keyReleased(k,c);}}
    }
}
