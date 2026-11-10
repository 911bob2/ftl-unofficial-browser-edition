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
// BT.pt logs the first beacon linked to the current one, at its place on the screen (the map's origin 386,122).
const PT="function BT.pt(tag) local l=Hyperspace.App.world.starMap.currentLoc.connectedLocations[0] log('{\"k\":\"pt\",\"tag\":\"'..tag..'\",\"x\":'..math.floor(l.loc.x+386)..',\"y\":'..math.floor(l.loc.y+122)..'}') end";
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
 ["shot", "10-fight-start"],
 // After the first jump (oracle session ps-u5-mvt in tools/hs-oracle/sessions/ps-u5-mv-tutorial.json).
 ["lua", "BT.pt('b1')"],
 ["click", 572, 48],
 ["wait", 2],
 ["shot", "11-map"],
 ["movetrace", "pt", "b1"],
 ["mdown", 1],
 ["mup", 1],
 ["wait", 8],
 ["shot", "12-pirate"],
 ["key", "1"],
 ["wait", 2],
 ["shot", "13-fight-check"],
 ["key", "4"],
 ["wait", 2],
 ["lua", "BT.box('fight_icon')"],
 ["shot", "14-fight-icon"],
 ["white", 1100, 221, 972, 300, 1100, 457],
 ["move", 1259, 507],
 ["wait", 2.5],
 ["move", 640, 600],
 ["wait", 0.5],
 ["lua", "BT.box('icon_hover')"],
 ["shot", "15-icon-hover"],
 ["lua", "Hyperspace.ships.enemy:DamageHull(100, true)"],
 ["wait", 8],
 ["shot", "16-empty-beacon"],
 ["key", "2"],
 ["wait", 2],
 ["shot", "17-notoriety"],
 ["key", "1"],
 ["wait", 2],
 ["lua", "BT.box('map_open')"],
 ["shot", "18-map-open"],
 ["click", 572, 48],
 ["wait", 2],
 ["shot", "19-immune"],
 ["click", 480, 400],
 ["wait", 1],
 ["shot", "20-immune-click"],
 ["click", 968, 113],
 ["wait", 2],
 ["shot", "21-sector-choice"],
 ["key", "1"],
 ["wait", 10],
 ["shot", "22-atlas"],
 ["choose", "1"],
 ["wait", 1],
 ["shot", "23-atlas-augment"],
 ["choose", "1 1 1"],
 ["wait", 1],
 ["shot", "24-noequipment"],
 ["wait", 4],
 ["key", "1"],
 ["wait", 4],
 ["key", "1"],
 ["waittrace", "\"event\":\"TUTORIAL_WARP\"", 15],
 ["wait", 1],
 ["shot", "25-warp"],
 ["pick", "TUTORIAL_WARP", "2", 20],
 ["pick", "TUTORIAL_REROUTE_MENU", "2", 20],
 ["wait", 1],
 ["shot", "26-reroute"],
 ["pick", "TUTORIAL_LIGHTSPEED_SECTOR_WARP", "1", 20],
 ["wait", 2],
 ["choose", "1"],
 ["wait", 10],
 ["shot", "27-fed"],
 ["choose", "1*"],
 ["wait", 3],
 ["shot", "28-guard"],
 ["choose", "1*"],
 ["wait", 3],
 ["lua", "BT.box('end')"],
 ["shot", "29-end"],
 ["lua", "BT.cont()"],
 ["wait", 4],
 ["shot", "30-menu"]];
// The original's answers to BT.box (oracle bt-mv-tutorial4, trace t4.jsonl; from the first jump on, oracle ps-u5-mvt, t5.jsonl).
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
 {"k":"tbox","tag":"fight_start","key":"tutorial_fight_start","n":1,"x":970,"y":220,"h":60.0},
 {"k":"tbox","tag":"auto","key":"tutorial_fight_start","n":0},
 {"k":"tbox","tag":"auto","key":"tutorial_fight_check","n":1,"x":970,"y":220,"h":180.0},
 {"k":"tbox","tag":"auto","key":"tutorial_fight_icon","n":1,"x":970,"y":220,"h":240.0},
 {"k":"tbox","tag":"fight_icon","key":"tutorial_fight_icon","n":1,"x":970,"y":220,"h":240.0},
 {"k":"tbox","tag":"auto","key":"tutorial_fight_icon","n":0},
 {"k":"tbox","tag":"icon_hover","key":"tutorial_fight_icon","n":0},
 {"k":"tbox","tag":"auto","key":"tutorial_empty_beacon","n":1,"x":970,"y":220,"h":300.0},
 {"k":"tbox","tag":"auto","key":"tutorial_notoriety_change","n":1,"x":970,"y":180,"h":400.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_open","n":1,"x":643,"y":109,"h":60.0},
 {"k":"tbox","tag":"map_open","key":"tutorial_map_open","n":1,"x":643,"y":109,"h":60.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_immune_beacon","n":2,"x":65,"y":174,"h":180.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_sector_choice","n":1,"x":65,"y":246,"h":200.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_sector_choice","n":0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_atlas","n":1,"x":982,"y":186,"h":320.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_atlas_augment","n":1,"x":970,"y":220,"h":80.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_atlas_augment","n":0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_atlas_reroute","n":1,"x":970,"y":220,"h":240.0},
 {"k":"tbox","tag":"auto","key":"tutorial_map_atlas_reroute","n":0},
 {"k":"tbox","tag":"auto","key":"tutorial_guard","n":1,"x":970,"y":220,"h":260.0},
 {"k":"tbox","tag":"auto","key":"tutorial_end","n":1,"x":970,"y":220,"h":260.0},
 {"k":"tbox","tag":"end","key":"tutorial_end","n":1,"x":970,"y":220,"h":260.0},
 {"k":"tbox","tag":"auto","key":"tutorial_end","n":0}];
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
 // One game pixel's colour from a 2x2 page screenshot at its place (as tests/ui-presentation.mjs reads it).
 const pixel=async(x,y)=>{const [px,py]=await at(x,y);const png=await page.screenshot({clip:{x:px,y:py,width:2,height:2}});
  return page.evaluate(async b=>{const m=await createImageBitmap(new Blob([Uint8Array.from(atob(b),c=>c.charCodeAt(0))],{type:'image/png'}));
   const c=new OffscreenCanvas(m.width,m.height),g=c.getContext('2d');g.drawImage(m,0,0);m.close();return [...g.getImageData(0,0,1,1).data].slice(0,3);},png.toString('base64'));};
 await page.waitForTimeout(2000);
 if((await snap()).window==='WelcomeChoiceBox'){await page.locator('#game').press('1');await page.waitForTimeout(500);}
 await shot('00-menu');
 await click(1070,423);await page.waitForTimeout(3000);
 const s=await snap();await writeFile(join(out,'snapshot.json'),JSON.stringify(s,null,1));
 assert.equal(s.state,'InGameState','the tutorial runs');assert.equal(s.ship,'PLAYER_SHIP_TUTORIAL');
 // The steps of the oracle session bt-mv-tutorial4: the same clicks and keys, the same Lua probes of tutorial.lua's text
 // boxes (BT.box logs the current lesson and its first box), BT.cont for a lesson that only waits for its Continue.
 const key=async k=>{await page.locator('#game').press(k);await page.waitForTimeout(250);};
 // A choice box's answer: wait until the box takes input, press, then until it is gone or another opened (true then).
 const box=d=>d&&d.event+JSON.stringify(d.choices);
 const answer=async k=>{let d=null;for(let i=0;i<80&&!(d=(await snap()).dialogue)?.ready;i++)await page.waitForTimeout(250);if(!d?.ready)return false;
  await key(k);for(let i=0;i<16;i++){const e=(await snap()).dialogue;if(e&&box(e)!==box(d))return true;await page.waitForTimeout(250);}return false;};
 // The console opens only once the start jump's fly-in is over (HS: not while the ship jumps), as in the original.
 const consoleOpen=()=>page.evaluate(()=>/^open=true/.test(window.hsWp7UiProbe('console')));
 const command=async c=>{for(let i=0;!await consoleOpen();i++){assert.ok(i<40,'the console opens');await key('Backslash');await page.waitForTimeout(250);}
  await page.keyboard.type('LUA '+c,{delay:5});await key('Enter');await page.waitForTimeout(500);
  // HS's console box closes on Enter (InputBox::TextEvent, TEXT_CONFIRM); the port's console stays open to show its
  // output (docs/CONSOLE.md), so it is closed here before the next click goes to the game, as in the oracle session.
  if(await consoleOpen()){await key('Escape');await page.waitForTimeout(250);}
  assert.ok(!await consoleOpen(),'the console closes');};
 // Lua runs through HS's console command LUA, as the oracle's lua step types it.
 await command(BOX);await command(CONT);await command(WATCH);await command(PT);
 for(const step of STEPS){
  if(step[0]==='click')await click(step[1],step[2]);else if(step[0]==='key')await key(step[1]);else if(step[0]==='lua')await command(step[1]);
  else if(step[0]==='wait')await page.waitForTimeout(step[1]*1000);else if(step[0]==='shot')await shot(step[1]);
  // tutorial.lua draws its boxes in the SHIP_STATUS render event, which the original draws after the enemy's target
  // window: the box's white frame over the target window stays white (oracle ps-u5-mvt 14-fight-icon, frame 4 px wide).
  else if(step[0]==='white'){for(let i=1;i<step.length;i+=2){const c=await pixel(step[i],step[i+1]);assert.ok(c.every(v=>v>=235),'the tutorial box frame at '+step[i]+','+step[i+1]+' is '+c+', not white over the target window');}}
  else if(step[0]==='move'){const [px,py]=await at(step[1],step[2]);await page.mouse.move(px,py);}
  else if(step[0]==='movetrace'){const line=logs.map(l=>l.slice(l.indexOf('{'))).find(l=>l.includes('"k":"'+step[1]+'"')&&l.includes('"tag":"'+step[2]+'"'));
   assert.ok(line,'no '+step[1]+' line tagged '+step[2]);const p=JSON.parse(line);const [px,py]=await at(p.x,p.y);await page.mouse.move(px,py);await page.waitForTimeout(300);}
  else if(step[0]==='mdown'){await page.mouse.down();await page.waitForTimeout(150);}else if(step[0]==='mup'){await page.mouse.up();await page.waitForTimeout(400);}
  // The oracle's choice-box steps: pick answers the box showing now, choose answers boxes in turn ("1*": 1 until none opens).
  else if(step[0]==='pick'){assert.ok(await answer(step[2]),'pick '+step[1]+': no box opened next');}
  else if(step[0]==='choose'){const keys=step[1].split(' '),repeat=keys.at(-1).endsWith('*');if(repeat)keys[keys.length-1]=keys.at(-1).slice(0,-1);
   for(let i=0;i<40;i++){const k=keys[Math.min(i,keys.length-1)],opened=await answer(k),last=i>=keys.length-1;if(last&&(!repeat||!opened))break;assert.ok(opened,'choose '+step[1]+': press '+(i+1)+' opened no box');}}
  else if(step[0]==='waittrace'){for(let i=0;i<step[2]*4&&!(await snap()).dialogue;i++)await page.waitForTimeout(250);}}
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
