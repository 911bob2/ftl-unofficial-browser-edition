// One click acts once (HostInput, PlayerShipUI's windowTookPress, InGameState's mouseCaptured), through the website with
// the browser's own mouse, in vanilla or in a Hyperspace session (the loader's preload sets window.ftlModSession from
// FTL_HS_MOD_SESSION, as for console.mjs). Each case closes or replaces a window or screen with a press over something
// that acts on a click and checks that thing did not act:
// - the title menu's options: Close, clicked where a menu button lies under it, then Close held, dragged onto each menu
//   button and let go there (the release used to reach the menu, which acted on the release);
// - the title menus act on the press, as the original's (MainMenu::MouseClick; MainMenu::MouseUp only lets go of the
//   options' sliders): a menu button, the statistics' Back and the hangar's List each pressed and let go elsewhere;
// - Game Over: Main Menu and Hangar held and let go on the next screen's buttons (the main menu's, the hangar's ship
//   list);
// - in a run: the pause menu's Continue let go over an enemy room with a weapon armed (no shot is aimed), a right click
//   on the pause menu (the weapon stays armed, no order reaches the ship), the debug console closed while its press is
//   held over the Ship button (the game does not take the press when the console goes).
// Test-only. Output: /out/click-*.png, CLICK_OUT.
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] node /app/tests/click-through.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8087,origin='http://localhost:'+port,out=process.env.CLICK_OUT||'/out';
const data=join(process.cwd(),'.build/fixture-click');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=process.env.FTL_HS_MOD_SESSION?JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8')):null;
const mode=session?.hyperspace?'hs':'vanilla';
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e)&&!/Content Security Policy directive 'style-src/.test(e.text()))errors.push('CONSOLE '+e.text());});
let failed=false;
const pass=t=>console.log('PASS '+t);
const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
const settle=async()=>{for(let i=0;i<4;i++)await flush();};
const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),sc=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*sc)/2+x*sc,box.y+(box.height-720*sc)/2+y*sc];};
const move=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py,{steps:4});await settle();};
const down=async(x,y,button='left')=>{await move(x,y);await page.mouse.down({button});await settle();};
const up=async(button='left')=>{await page.mouse.up({button});await settle();};
const click=async(x,y,button='left')=>{await down(x,y,button);await up(button);};
const centre=r=>[Math.round(r.x+r.width/2),Math.round(r.y+r.height/2)];
const state=s=>page.waitForFunction(s=>JSON.parse(window.ftlEngine.debugSnapshot()).state===s,s,{timeout:120000});
const shot=n=>page.locator('#game').screenshot({path:join(out,'click-'+mode+'-'+n+'.png')});
const key=async k=>{await page.locator('#game').press(k);await settle();};
// The run's own boxes (the start event, Multiverse's start menu and lore) closed: a proceed/enough/continue choice, else
// the choices in turn, the rest with Escape (as console.mjs).
const closeWindows=async()=>{for(let i=0;i<40;i++){const s=await snapshot(),w=s.window;if(w==='game')return;
  if(w!=='DialogueWindow')await key('Escape');else if(s.dialogue?.ready){const p=JSON.parse(await page.evaluate(()=>window.consoleProbe())),c=(p.choices??[]).findIndex(t=>/proceed|enough|continue/i.test(t));await key(String(c>=0?c+1:1+i%Math.max(1,(p.choices??[]).length)));}
  await page.waitForTimeout(400);}throw Error('a window stays open: '+(await snapshot()).window);};
const newRun=async()=>{await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));await state('InGameState');await page.waitForTimeout(1500);await closeWindows();await page.locator('#game').focus();};
const mainMenu=async()=>{const s=await snapshot();assert.equal(s.state,'MainMenuState','the main menu stays');assert.equal(s.window,'main','no menu window');};
// Who is under a point of the main menu (its buttons from the menu's own diagnostics).
const under=(buttons,x,y)=>Object.entries(buttons).find(([,b])=>b.active&&x>=b.x&&x<b.x+b.width&&y>=b.y&&y<b.y+b.height)?.[0]??null;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='click-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await state('MainMenuState');
 if((await snapshot()).window==='WelcomeChoiceBox'){await key('1');await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='main');}
 pass(mode+' main menu');

 // ---- the title menu's options -------------------------------------------------------------------------------------
 const menu=(await snapshot()).buttons;console.log('menu buttons '+JSON.stringify(menu));
 const openOptions=async()=>{await click(...centre(menu.options));await state('SettingsMenuState');const s=await snapshot();assert.equal(s.window,'OptionsWindow');return s.close;};
 let close=await openOptions();console.log('Close '+JSON.stringify(close));
 // Close where a menu button is under it, if one is (the user's case: Tutorial under Close).
 let point=centre(close),hit=null;
 for(let y=close.y+2;y<close.y+close.height-2&&!hit;y+=2)for(let x=close.x+2;x<close.x+close.width-2;x+=2){const b=under(menu,x,y);if(b&&b!=='quit'){point=[x,y];hit=b;break;}}
 console.log('Close at '+point+' over '+(hit??'no menu button'));
 await shot('options');
 await click(...point);await state('MainMenuState');await settle();await mainMenu();
 pass(mode+': Close in the options returns to the main menu without clicking '+(hit??'the menu under it'));
 // Close held, let go on each menu button: the release is the options', not the menu's.
 for(const name of ['tutorial','stats','options','credits','start','continue'].filter(n=>menu[n]?.active)){
  close=await openOptions();
  await down(...centre(close));await state('MainMenuState');
  await move(...centre(menu[name]));await up();await page.waitForTimeout(300);
  await mainMenu();
 }
 pass(mode+': Close held and let go on each menu button clicks none of them');
 // A menu button acts on its press: Statistics pressed and let go away from it; then Back the same way.
 await down(...centre(menu.stats));await state('BrowserStatisticsState');
 await move(640,700);await up();assert.equal((await snapshot()).state,'BrowserStatisticsState','the statistics stay');
 await down(1150,650);await state('MainMenuState');await move(20,20);await up();await mainMenu();
 pass(mode+': the main menu and the statistics act on the press, not on the release');

 // ---- in a run --------------------------------------------------------------------------------------------------------
 await newRun();
 let s=await snapshot();const hud=s.clickTargets;console.log('HUD '+JSON.stringify(hud));
 // The debug console closed while its press is held over the Ship button: the game does not take that press (before
 // the enemy comes: in danger the Ship button only warns).
 await move(...centre(hud.shipButton));
 await page.evaluate(()=>window.ftlEngine.consoleOpen());await settle();assert.equal((await snapshot()).console,true);
 await page.mouse.down();await settle();
 await page.evaluate(()=>window.ftlEngine.consoleToggle());await settle();
 s=await snapshot();assert.equal(s.console,false);assert.equal(s.window,'game','closing the console did not hand its press to the Ship button');
 await up();assert.equal((await snapshot()).window,'game');
 await click(...centre(hud.shipButton));assert.equal((await snapshot()).window,'ShipWindow','the next click is the game\'s');
 await key('Escape');await closeWindows();
 pass(mode+': a press the console took stays the console\'s when it closes');
 // The pause menu (opened by Escape), Continue let go over an enemy room with a weapon armed: the press
 // was the menu's, no shot is aimed.
 await page.evaluate(()=>window.ftlEngine.consoleExecute('spawn-ship REBEL 1.normal'));await page.waitForTimeout(800);await closeWindows();
 s=await snapshot();const room=s.clickTargets.enemyRooms[0];assert.ok(room,'an enemy room on screen');
 // A weapon's key powers it first (WeaponControl::SelectArmament), then arms it.
 let slot=s.weapons.findIndex(w=>w.weapon&&w.powered);
 for(let i=s.weapons.length-1;slot<0&&i>=0;i--)if(s.weapons[i].weapon){await key('Digit'+(i+1));if((await snapshot()).weapons[i].powered)slot=i;}
 assert.ok(slot>=0,'a powered weapon: '+JSON.stringify((await snapshot()).weapons.filter(w=>w.weapon)));
 await key('Digit'+(slot+1));assert.equal((await snapshot()).clickTargets.armed,true,'the weapon is armed');
 // While a target is being picked the top bar's buttons take no click (CommandGui::MouseMove): Escape opens the menu.
 await click(...centre(hud.settingsButton));s=await snapshot();assert.equal(s.window,'game','the top bar takes no click while a weapon is armed');
 assert.equal(s.clickTargets.armed,true,'the click on the top bar keeps the weapon armed');
 await key('Escape');s=await snapshot();assert.equal(s.window,'PauseWindow','Escape opens the pause menu');
 assert.equal(s.clickTargets.armed,true,'the weapon stays armed under the pause menu');
 // a right click on the menu is the menu's: the weapon stays armed
 await click(...centre(s.clickTargets.windowButtons[0]),'right');s=await snapshot();
 assert.equal(s.window,'PauseWindow');assert.equal(s.clickTargets.armed,true,'a right click on the pause menu does not drop the armed weapon');
 await shot('pause-armed');
 await down(...centre(s.clickTargets.windowButtons[0]));assert.equal((await snapshot()).window,'game','Continue closes on the press');
 await move(room.x,room.y);await up();await page.waitForTimeout(300);
 s=await snapshot();assert.equal(s.weapons[slot].target,null,'no shot aimed at the room the release was over');
 assert.equal(s.clickTargets.armed,true,'the weapon is still armed');
 await key('Escape');await closeWindows();
 pass(mode+': the pause menu\'s Continue let go over an enemy room aims nothing; a right click on it keeps the weapon armed');

 // ---- Game Over: each button held and let go on the next screen's buttons -------------------------------------------
 const gameOver=async()=>{await page.evaluate(()=>window.ftlEngine.consoleExecute('gameover LOOSE_HULL'));
   await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='GameOverWindow');await settle();
   return (await snapshot()).clickTargets.windowButtons;};// restart, hangar, main menu, quit, stats
 let buttons=await gameOver();await shot('gameover');
 await down(...centre(buttons[2]));await state('MainMenuState');
 const target=['stats','tutorial','credits','options'].find(n=>menu[n]?.active);
 await move(...centre(menu[target]));await up();await page.waitForTimeout(300);await mainMenu();
 pass(mode+': Game Over\'s Main Menu let go on the menu\'s '+target+' button clicks nothing');
 await newRun();buttons=await gameOver();
 await down(...centre(buttons[1]));await state('SelectShipState');await settle();
 const hangar=await snapshot(),list=hangar.controls.find(c=>c.id==='list'&&c.enabled)??hangar.controls.find(c=>c.id==='next'&&c.enabled);
 assert.ok(list,'a hangar control to let go on: '+JSON.stringify(hangar.controls));
 await move(...centre(list));await up();await page.waitForTimeout(500);
 s=await snapshot();assert.equal(s.state,'SelectShipState','the hangar stays');
 assert.equal(s.shipList,false,'the ship list did not open');assert.equal(s.ship,hangar.ship,'the ship did not change');
 assert.equal(s.customizing,false);
 await shot('gameover-hangar');
 pass(mode+": Game Over's Hangar let go on the hangar's "+list.id+" button clicks nothing");
 // The hangar acts on the press: List pressed and let go away from it opens the ship list.
 const listControl=hangar.controls.find(c=>c.id==='list'&&c.enabled);
 if(listControl){
  await down(...centre(listControl));assert.equal((await snapshot()).shipList,true,'the ship list opens on the press');
  await move(5,710);await up();assert.equal((await snapshot()).shipList,true,'the ship list stays open');
  await shot('hangar-list-on-press');
  pass(mode+': the hangar acts on the press, not on the release');
 }

 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS click-through '+mode);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,30),null,1));try{console.log('snapshot',await page.evaluate(()=>window.ftlEngine.debugSnapshot()));await shot('failure');}catch{}}
await browser.close();server.kill('SIGTERM');process.exit(failed?1:0);
