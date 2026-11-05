// Multiverse's tutorial (MV 5.5.1: <disableDefaultTutorial>, data/mv_scripts/tutorial.lua) from the real main menu,
// against the original (oracle session bt-mv-tutorial4 in tools/hs-oracle/sessions/bugs-tests.json). Screenshots of each
// step go to MVT_OUT; the probe lines come from the page's engine snapshot.
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> [MVT_OUT=/out] node /app/tests/hs-mv-tutorial.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,out=process.env.MVT_OUT||join(process.cwd(),'.build/hs-mv-tutorial');
const data=join(process.cwd(),'.build/fixture-hs-mvt');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8'));
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1280,height:720}}),page=await context.newPage();
// Every Lua log line to the page console (the engine only warns about errors there by itself).
await context.addInitScript(()=>{window.ftlHsLuaLog=(kind,text)=>console.log('[Lua] '+text);});
const errors=[],logs=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>errors.push('PAGE CRASHED'));
page.on('console',e=>{const t=e.text();if(e.type()==='error'&&!expectedConsoleError(e))errors.push('CONSOLE '+t);else logs.push(t);});
const BOX="BT={} function BT.box(tag) local t=mods.multiverse.TutorialLuaManager.new() local b=t.textBoxes[1] log('{\"k\":\"tbox\",\"tag\":\"'..tag..'\",\"key\":\"'..tostring(t.currentEventKey)..'\",\"n\":'..#t.textBoxes..(b and (',\"x\":'..b.x..',\"y\":'..b.y..',\"h\":'..b.height) or '')..'}') end";
const CONT="function BT.cont() local t=mods.multiverse.TutorialLuaManager.new() local s=t.currentEvent t:clear_event() if s then s:on_stop() end end";
// WATCH logs every change of lesson or of its box count, also while a choice box keeps the console closed.
const WATCH="BT.last='' script.on_internal_event(Defines.InternalEvents.ON_TICK, function() local t=mods.multiverse.TutorialLuaManager.new() local k=tostring(t.currentEventKey)..':'..#t.textBoxes if k~=BT.last then BT.last=k BT.box('auto') end end)";
const STEPS=[["lua", "BT.box('start')"],
 ["shot", "01-start"],
 ["click", 975, 284],
 ["wait", 2],
 ["lua", "BT.box('crew_power')"],
 ["shot", "02-crew-power"],
 ["click", 50, 166],
 ["wait", 1],
 ["key", "p"],
 ["wait", 2],
 ["lua", "BT.box('toggle_1')"],
 ["shot", "03-toggle-1"],
 ["click", 126, 131],
 ["wait", 2],
 ["shot", "04-toggle-2"],
 ["key", "1"],
 ["wait", 2],
 ["lua", "BT.box('info')"],
 ["shot", "05-info"],
 ["lua", "BT.cont()"],
 ["wait", 1],
 ["lua", "BT.box('crew_order')"],
 ["shot", "06-crew-order"],
 ["lua", "BT.cont()"],
 ["wait", 1],
 ["lua", "BT.box('tile')"],
 ["shot", "07-tile"],
 ["lua", "BT.cont()"],
 ["wait", 1],
 ["lua", "BT.box('storage_initial')"],
 ["shot", "08-storage-initial"],
 ["click", 739, 48],
 ["wait", 2],
 ["shot", "09-storage-main"],
 ["key", "1"],
 ["wait", 2],
 ["lua", "BT.box('fight_start')"],
 ["shot", "10-fight-start"]];
// The original's answers to BT.box (oracle bt-mv-tutorial4, trace t4.jsonl).
const NATIVE=[
 {"k":"tbox","tag":"auto","key":"tutorial_start","n":1,"x":850,"y":50,"h":220.0},
 {"k":"tbox","tag":"start","key":"tutorial_start","n":1,"x":850,"y":50,"h":220.0},
 {"k":"tbox","tag":"auto","key":"tutorial_crew_power","n":1,"x":123,"y":222,"h":280.0},
 {"k":"tbox","tag":"crew_power","key":"tutorial_crew_power","n":1,"x":123,"y":222,"h":280.0},
 {"k":"tbox","tag":"auto","key":"tutorial_toggle_1","n":1,"x":123,"y":173,"h":380.0},
 {"k":"tbox","tag":"toggle_1","key":"tutorial_toggle_1","n":1,"x":123,"y":173,"h":380.0},
 {"k":"tbox","tag":"auto","key":"tutorial_toggle_2","n":1,"x":970,"y":10,"h":660.0},
 {"k":"tbox","tag":"auto","key":"tutorial_info_button","n":1,"x":956,"y":312,"h":140.0},
 {"k":"tbox","tag":"info","key":"tutorial_info_button","n":1,"x":956,"y":312,"h":140.0},
 {"k":"tbox","tag":"auto","key":"tutorial_crew_order","n":1,"x":123,"y":222,"h":100.0},
 {"k":"tbox","tag":"crew_order","key":"tutorial_crew_order","n":1,"x":123,"y":222,"h":100.0},
 {"k":"tbox","tag":"auto","key":"tutorial_tile_control","n":1,"x":123,"y":222,"h":180.0},
 {"k":"tbox","tag":"tile","key":"tutorial_tile_control","n":1,"x":123,"y":222,"h":180.0},
 {"k":"tbox","tag":"auto","key":"tutorial_storage_initial","n":1,"x":801,"y":26,"h":180.0},
 {"k":"tbox","tag":"storage_initial","key":"tutorial_storage_initial","n":1,"x":801,"y":26,"h":180.0},
 {"k":"tbox","tag":"auto","key":"tutorial_storage_main","n":1,"x":970,"y":220,"h":240.0},
 {"k":"tbox","tag":"auto","key":"tutorial_fight_start","n":1,"x":970,"y":220,"h":60.0},
 {"k":"tbox","tag":"fight_start","key":"tutorial_fight_start","n":1,"x":970,"y":220,"h":60.0}];
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-mvt-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu');
 const snap=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await page.waitForTimeout(400);};
 const shot=async name=>page.locator('#game').screenshot({path:join(out,name+'.png')});
 await page.waitForTimeout(2000);
 if((await snap()).window==='WelcomeChoiceBox'){await page.locator('#game').press('1');await page.waitForTimeout(500);}
 await shot('00-menu');
 await click(1070,423);await page.waitForTimeout(3000);
 const s=await snap();await writeFile(join(out,'snapshot.json'),JSON.stringify(s,null,1));
 assert.equal(s.state,'InGameState','the tutorial runs');assert.equal(s.ship,'PLAYER_SHIP_TUTORIAL');
 // The steps of the oracle session bt-mv-tutorial4: the same clicks and keys, the same Lua probes of tutorial.lua's text
 // boxes (BT.box logs the current lesson and its first box), BT.cont for a lesson that only waits for its Continue.
 const key=async k=>{await page.locator('#game').press(k);await page.waitForTimeout(250);};
 // The console opens only once the start jump's fly-in is over (HS: not while the ship jumps), as in the original.
 const consoleOpen=()=>page.evaluate(()=>/^open=true/.test(window.hsWp7UiProbe('console')));
 const command=async c=>{for(let i=0;!await consoleOpen();i++){assert.ok(i<40,'the console opens');await key('Backslash');await page.waitForTimeout(250);}
  await page.keyboard.type('LUA '+c,{delay:5});await key('Enter');await page.waitForTimeout(500);
  // HS's console box closes on Enter (InputBox::TextEvent, TEXT_CONFIRM); the port's console stays open to show its
  // output (docs/CONSOLE.md), so it is closed here before the next click goes to the game, as in the oracle session.
  if(await consoleOpen()){await key('Escape');await page.waitForTimeout(250);}
  assert.ok(!await consoleOpen(),'the console closes');};
 // Lua runs through HS's console command LUA, as the oracle's lua step types it.
 await command(BOX);await command(CONT);await command(WATCH);
 for(const step of STEPS){
  if(step[0]==='click')await click(step[1],step[2]);else if(step[0]==='key')await key(step[1]);else if(step[0]==='lua')await command(step[1]);
  else if(step[0]==='wait')await page.waitForTimeout(step[1]*1000);else if(step[0]==='shot')await shot(step[1]);}
 await page.waitForTimeout(500);
 const boxes=logs.filter(l=>l.includes('"k":"tbox"')).map(l=>JSON.parse(l.slice(l.indexOf('{'),l.lastIndexOf('}')+1)));
 await writeFile(join(out,'tbox.json'),JSON.stringify(boxes,null,1));
 console.log('boxes '+JSON.stringify(boxes));
 assert.deepEqual(boxes,NATIVE,'the lessons and their text boxes as in the original');
 assert.deepEqual(logs.filter(l=>/\[Lua\] \[error\]/.test(l)),[],'Lua errors');
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS hs-mv-tutorial '+session.hash.slice(0,12));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,20),null,1));await page.screenshot({path:join(out,'failure.png')}).catch(()=>{});}
finally{console.log('Lua errors '+JSON.stringify(logs.filter(l=>/\[Lua\] \[error\]|attempt to/.test(l)).slice(0,20),null,1));await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
