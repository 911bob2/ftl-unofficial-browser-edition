import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';
import {warningUiChecks} from './warnings-ui.mjs';
import {optionsUiChecks,colorblindNoticeUiCheck} from './options-ui.mjs';
import {hangarUiChecks} from './hangar-ui.mjs';
import {presentationUiChecks} from './ui-presentation.mjs';
import {back,enterFullscreen,gameBarUiChecks,consoleToggleUiCheck,escapeExitUiChecks,hangarEscapeCheck,saveCloseUiChecks} from './gamebar-ui.mjs';
const origin=process.env.MENU_ORIGIN??'http://localhost:8084',checks=[];
assert.ok(['http://localhost:8083','http://localhost:8084'].includes(origin),'Use an isolated menu fixture');
const readyDeadline=Date.now()+20000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)) {
 if(Date.now()>readyDeadline)throw Error('Isolated menu instance did not become ready');
 await new Promise(resolve=>setTimeout(resolve,250));
}
const initial=new Map((await readFile((process.env.DEBUG_FIXTURE_DIR??'.build/debug-data')+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const selected=(process.env.MENU_BROWSERS??'Chrome,Edge,Firefox').split(',');
assert.ok(selected.length&&selected.every(name=>['Chrome','Edge','Firefox'].includes(name)),'Unknown menu browser');
for(const [name,type,options,user] of [['Chrome',chromium,{channel:'chrome'},'user03'],['Edge',chromium,{channel:'msedge'},'user04'],['Firefox',firefox,{},'user07']].filter(([name])=>selected.includes(name))) {
 const browser=withOnboarding(await type.launch({headless:!(name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'),...options}),origin),page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
 page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
 const pass=label=>{checks.push({browser:name,label});console.log('PASS '+name+': '+label);};
 try {
  const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me'));await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
  await page.evaluate(async({username,initial})=>{
   let csrf=(await (await fetch('/api/auth/csrf')).json()).token;
   const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)});
   const password='menu-fixture-password-'+username;let r=await post('login',{username,password});
   if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('Fixture login '+r.status);
   csrf=(await (await fetch('/api/auth/csrf')).json()).token;
   if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('Fixture rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}
  },{username:user,initial:initial.get(user)});
  await page.goto(origin);await page.locator('#launch').click();await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden,{},{timeout:90000});
  if(!await page.evaluate(()=>!!window.ftlEngine))await page.locator('#takeover').click();await page.waitForFunction(()=>!!window.ftlEngine,{},{timeout:90000});
  const snapshot=()=>page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()));
  const state=label=>page.waitForFunction(s=>JSON.parse(window.ftlEngine.debugSnapshot()).state===s,label);
  const windowIs=label=>page.waitForFunction(s=>JSON.parse(window.ftlEngine.debugSnapshot()).window===s,label);
  async function click(x,y){const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);await page.mouse.click(box.x+(box.width-1280*scale)/2+x*scale,box.y+(box.height-720*scale)/2+y*scale);}
  async function hover(x,y){const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);await page.mouse.move(box.x+(box.width-1280*scale)/2+x*scale,box.y+(box.height-720*scale)/2+y*scale);}
  // Native Options rows: Frame Limit (VIDEO, third row) cycles 30/60/90/120/240/360/
  // Unlimited on click; while it is hovered, Right and Left step forwards and back.
  const limit=()=>page.evaluate(()=>window.ftlEngine.frameRateLimit());
  async function cycleFrameLimit(r){const before=await limit();await click(r.x+80,r.y+162);await page.waitForFunction(b=>window.ftlEngine.frameRateLimit()!==b,before);}
  async function frameLimitTo(r,value){for(let i=0;i<8&&await limit()!==value;i++)await cycleFrameLimit(r);assert.equal(await limit(),value);}
  // A newly created profile first shows the native welcome ChoiceBox; key 1 continues.
  await state('MainMenuState');
  if((await snapshot()).window==='WelcomeChoiceBox'){await page.locator('#game').press('1');await windowIs('main');}
  if(process.env.MENU_OPTIONS_ONLY==='1'){
   await optionsUiChecks(page,{snapshot,state,windowIs,click,pass,name});assert.deepEqual(errors,[]);continue;
  }
  if(process.env.MENU_WARNINGS_ONLY==='1'){
   await enterFullscreen(page);
   await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   await warningUiChecks(page,{snapshot,state,windowIs,click,pass,name});assert.deepEqual(errors,[]);continue;
  }
  const saved=await page.evaluate(()=>!!window.ftlEngine.runSave());
  if(process.env.MENU_HANGAR_ONLY==='1') {
   await click(1180,353);
   if(saved){await windowIs('ConfirmationWindow');const yes=(await snapshot()).confirmation.yes;await click(yes.x+yes.width/2,yes.y+12);}
   await state('SelectShipState');await hangarUiChecks(page,{snapshot,state,click,pass,name});await presentationUiChecks(page,{snapshot,state,click,pass,name});assert.deepEqual(errors,[]);continue;
  }
  await gameBarUiChecks(page,{pass,name});
  await escapeExitUiChecks(page,{snapshot,state,windowIs,click,pass,name});
  await click(1180,527);await state('SettingsMenuState');await windowIs('OptionsWindow');
  await colorblindNoticeUiCheck(page,{snapshot,windowIs,click,pass,name,context:'title'});
  await click(712,370);await windowIs('HotkeysWindow');await click(1140,635);await windowIs('OptionsWindow');
  const popups=()=>page.evaluate(()=>/achPopups="true"/.test(window.ftlEngine.diagnosticProfile()));
  const waitPopups=expected=>page.waitForFunction(value=>/achPopups="true"/.test(window.ftlEngine.diagnosticProfile())===value,expected);
  const popupsBefore=await popups();await click(702,302);await waitPopups(!popupsBefore);
  await click(600,491);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).soundVolume<.9);
  const volume=(await snapshot()).soundVolume;assert.ok(volume>=0&&volume<.9);await click(967,457);await state('MainMenuState');
  await click(1180,527);await state('SettingsMenuState');assert.equal((await snapshot()).soundVolume,volume);
  assert.equal(await popups(),!popupsBefore);await click(702,302);await waitPopups(popupsBefore);
  await back(page);await state('MainMenuState');pass('Main-menu Options, Controls, visible Back and applied volume persistence');
  const originalStatistics=await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),ae:JSON.parse(window.ftlEngine.debugSnapshot()).ae}));
  // Populated panels catch overlap and portrait crashes that empty profiles hide.
  // These values are confined to the disposable fixture and restored afterwards.
  await page.evaluate(({profile,run,ae})=>{
   const doc=new DOMParser().parseFromString(profile,'application/xml'),stats=doc.querySelector('browserStats');
   for(const [key,value] of Object.entries({games:15,wins:4,scrap:45000,ships:220,beacons:610,crew:95,mostScrap:9500,mostShips:70,mostBeacons:115,mostCrew:18}))stats.setAttribute(key,String(value));
   for(const child of [...stats.querySelectorAll('score,crewRecord')])child.remove();
   for(let i=0;i<5;i++){
    const score=doc.createElement('score');
    for(const [key,value] of Object.entries({ship:'PLAYER_SHIP_HARD',title:'The Kestrel',difficulty:i%2?'HARD':'NORMAL',advanced:true,scrap:9500-i*300,beacons:110-i,ships:65-i,complete:true,outcome:i%2?'WIN':'LOOSE_HULL',crew:18-i,crewComplete:true,sector:8-i}))score.setAttribute(key,String(value));
    stats.append(score);
   }
   for(const [kind,species,female] of [['REPAIRS','human',true],['KILLS','slug',false],['EVASIONS','energy',false],['JUMPS','rock',false],['SKILLS','anaerobic',false]]){
    const record=doc.createElement('crewRecord');
    for(const [key,value] of Object.entries({kind,species,female,name:'Record holder',value:123}))record.setAttribute(key,String(value));
    stats.append(record);
   }
   window.start(ae,run,new XMLSerializer().serializeToString(doc));
  },originalStatistics);
  const statisticsBefore=await page.evaluate(()=>window.ftlEngine.diagnosticProfile());
  await click(1180,469);await state('BrowserStatisticsState');
  const statsTab=value=>page.waitForFunction(value=>JSON.parse(window.ftlEngine.debugSnapshot()).statisticsTab===value,value);
  await statsTab('STATISTICS');
  await click(480,65);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).statisticsScoresTab==='SHIP_BEST');
  await click(140,170);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).statisticsShipList);
  await back(page);await page.waitForFunction(()=>!JSON.parse(window.ftlEngine.debugSnapshot()).statisticsShipList);
  await state('BrowserStatisticsState');
  await click(180,65);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).statisticsScoresTab==='TOP_SCORES');
  await enterFullscreen(page);
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await page.screenshot({path:'.build/statistics-'+name.toLowerCase()+'.png'});
  await click(1000,65);await statsTab('ACHIEVEMENTS');
  await click(480,65);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).statisticsScoresTab==='SHIP_BEST');
  assert.equal((await snapshot()).statisticsTab,'ACHIEVEMENTS','Left score tab changed right statistics tab');
  await page.screenshot({path:'.build/statistics-achievements-'+name.toLowerCase()+'.png'});
  await click(730,65);await statsTab('STATISTICS');await click(180,65);
  // Ship Best's LIST opens the hangar's ShipSelect, whose Open (0x00529940) marks the list tutorial shown.
  const withoutListTutorial=xml=>xml.replace(/ listTutorial="\d"/,'');
  const statisticsAfter=await page.evaluate(()=>window.ftlEngine.diagnosticProfile());
  assert.match(statisticsAfter,/ listTutorial="2"/,'Ship Best list did not mark its tutorial shown');
  assert.equal(withoutListTutorial(statisticsAfter),withoutListTutorial(statisticsBefore),'Statistics navigation changed progression');
  await click(1150,650);await state('MainMenuState');
  assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
  const ordinaryTutorial=await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),storedProfile:window.ftlEngine.profileSave()}));
  const overlayReport=await page.evaluate(()=>window.tutorialOverlayProbe());
  assert.ok(overlayReport.startsWith('PASS')&&overlayReport.includes('33 tutorial instruction panels'));
  await state('MainMenuState');
  assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
  // This seed reproduced a neighbour hidden beneath the tutorial panel.
  // The synchronous probe must also restore the focus/settings it receives.
  const traversalReport=await page.evaluate(()=>{
   const before=JSON.parse(window.ftlEngine.debugSnapshot());
   try {
    window.diagnosticSeed('24055526093');window.focusChanged(false);
    const report=window.tutorialPlayProbe(),after=JSON.parse(window.ftlEngine.debugSnapshot());
    if(after.focused!==false||['autoPause','beaconPaths','dialogKeys'].some(k=>before[k]!==after[k]))throw Error('Tutorial probe changed focus/settings');
    return report;
   } finally {window.diagnosticSeed(before.rng);window.focusChanged(before.focused);}
  });
  assert.ok(traversalReport.startsWith('PASS')&&traversalReport.includes('33-step tutorial action traversal'));
  await state('MainMenuState');
  assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
  assert.deepEqual(await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),storedProfile:window.ftlEngine.profileSave()})),ordinaryTutorial,'Tutorial fullscreen fixtures changed ordinary progress');
  pass('Tutorial panel/Exit and normal-mechanics completion preserve fullscreen and ordinary progress');
  await page.evaluate(async()=>{await document.exitFullscreen();await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);});
  await page.evaluate(({profile,run,ae})=>window.start(ae,run,profile),originalStatistics);
  await state('MainMenuState');
  pass('Scores/statistics/achievements tabs render, leave progression unchanged and close with fullscreen intact');
  await click(1180,527);await state('SettingsMenuState');
  const videoRect=(await snapshot()).windowRect;
  const fps=value=>page.waitForFunction(value=>window.ftlEngine.frameRateLimit()===value,value);
  await frameLimitTo(videoRect,30);
  await page.locator('#game').press('ArrowRight');await fps(60);
  await page.locator('#game').press('ArrowLeft');await fps(30);
  const beforeTick=(await snapshot()).tick;
  const capped=await page.evaluate(()=>window.ftlEngine.benchmark(1000));
  const simulationTicks=(await snapshot()).tick-beforeTick;
  assert.ok(capped.fps>=25&&capped.fps<=34,JSON.stringify(capped));
  assert.ok(simulationTicks>capped.frames*3,'Render cap throttled simulation updates');
  for(const value of [60,90,120,240,360,0]){await cycleFrameLimit(videoRect);await fps(value);}
  const unlimited=await page.evaluate(async()=>{
   let browserFrames=0,id;const count=()=>{browserFrames++;id=requestAnimationFrame(count);};id=requestAnimationFrame(count);
   try{return {...await window.ftlEngine.benchmark(1000,0),browserFrames};}finally{cancelAnimationFrame(id);}
  });
  // Display refresh and competing work bound actual FPS. Unlimited must draw
  // each available animation frame, rather than promise a hardware throughput.
  assert.ok(Math.abs(unlimited.frames-unlimited.browserFrames)<=2,JSON.stringify({capped,unlimited}));
  await cycleFrameLimit(videoRect);await fps(30);
  await page.locator('#game').press('ArrowLeft');await fps(0);await page.locator('#game').press('ArrowLeft');await fps(360);
  await page.screenshot({path:'.build/options-'+name.toLowerCase()+'.png'});
  const sync=page.waitForResponse(r=>r.url().endsWith('/api/saves/current')&&r.request().method()==='PUT');
  await page.locator('#save').click();assert.ok((await sync).ok());
  await page.reload();await page.locator('#launch').click();
  await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden,{},{timeout:90000});
  if(!await page.evaluate(()=>!!window.ftlEngine))await page.locator('#takeover').click();
  await page.waitForFunction(()=>!!window.ftlEngine,{},{timeout:90000});await fps(360);
  await click(1180,527);await state('SettingsMenuState');
  const restoredRect=(await snapshot()).windowRect;
  await cycleFrameLimit(restoredRect);await fps(0);
  const syncUnlimited=page.waitForResponse(r=>r.url().endsWith('/api/saves/current')&&r.request().method()==='PUT');
  await page.locator('#save').click();assert.ok((await syncUnlimited).ok());
  await page.reload();await page.locator('#launch').click();
  await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden,{},{timeout:90000});
  if(!await page.evaluate(()=>!!window.ftlEngine))await page.locator('#takeover').click();
  await page.waitForFunction(()=>!!window.ftlEngine,{},{timeout:90000});await fps(0);
  pass(`Frame Limit cycles 30/60/90/120/240/360/Unlimited by click and arrows, with cloud/reload persistence and independent simulation (${capped.fps.toFixed(1)} / ${unlimited.fps.toFixed(1)} FPS)`);
  const progressBefore=await page.evaluate(()=>window.ftlEngine.diagnosticProfile());
  await click(1180,585);await state('CreditsState');
  const credits=await snapshot();assert.ok(credits.backers>1000&&credits.creditLines>credits.backers);assert.ok(credits.scroll<=-70);
  await page.waitForFunction(s=>JSON.parse(window.ftlEngine.debugSnapshot()).scroll<s,credits.scroll);
  assert.equal(await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),progressBefore,'Credits modified player progression');
  await page.screenshot({path:'.build/credits-'+name.toLowerCase()+'.png'});
  // The original's credits ignore clicks (MainMenu 0x004d47e0) and any key leaves them (0x004d1900).
  await click(1180,675);assert.equal((await snapshot()).state,'CreditsState','A click left the credits');
  await page.locator('#game').press('Space');await state('MainMenuState');
  await click(1180,585);await state('CreditsState');
  await enterFullscreen(page);
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await back(page);await state('MainMenuState');assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
  await page.evaluate(async()=>{await document.exitFullscreen();await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);});pass('Credits includes the original backers, scrolls, returns and preserves fullscreen/progression');
  await click(1180,353);
  if(saved){await windowIs('ConfirmationWindow');const yes=(await snapshot()).confirmation.yes;await click(yes.x+yes.width/2,yes.y+12);}
  await state('SelectShipState');
  await hangarEscapeCheck(page,{click,state,pass});
  await hangarUiChecks(page,{snapshot,state,click,pass,name});
  await presentationUiChecks(page,{snapshot,state,click,pass,name});
  const before=await snapshot();
  await click(before.ae?1040:1175,436);await page.waitForFunction(a=>JSON.parse(window.ftlEngine.debugSnapshot()).ae!==a,before.ae);
  // The original hangar has no Options button (removed for parity); the AE toggle must keep the ship.
  const after=await snapshot();assert.notEqual(after.ae,before.ae);assert.equal(after.ship,before.ship);pass('Hangar AE toggle retains selection');
  await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));await state('InGameState');
  await enterFullscreen(page);
  await back(page);await windowIs('PauseWindow');
  // Pause menu's visible Controls button: menu geometry is reported by the engine.
  const rect=await page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()).windowRect);
  assert.ok(rect,'Missing menu geometry');await click(rect.x+70,rect.y+220);await windowIs('HotkeysWindow');
  await back(page);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window!=='HotkeysWindow');
  assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');pass('Pause Controls and semantic Back preserve fullscreen');
  // Controls returns to Options through the shared settings flow.
  await windowIs('OptionsWindow');
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).windowRect?.x>0);
  const runVideo=(await snapshot()).windowRect;
  const aimingBefore=(await snapshot()).autofire;
  await hover(runVideo.x+80,runVideo.y+162);const runLimit=await limit();
  await page.locator('#game').press('ArrowRight');await page.waitForFunction(b=>window.ftlEngine.frameRateLimit()!==b,runLimit);
  assert.equal((await snapshot()).autofire,aimingBefore,'FPS input activated a gameplay hotkey');
  await frameLimitTo(runVideo,0);
  await back(page);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window!=='OptionsWindow');
  assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
  pass('FPS settings work during a run and visible Back retains fullscreen');
  await warningUiChecks(page,{snapshot,state,windowIs,click,pass,name});
  await optionsUiChecks(page,{snapshot,state,windowIs,click,pass,name});
  await page.evaluate(async()=>{if(document.fullscreenElement)await document.exitFullscreen();});await page.locator('#console').click();await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).console);
  await page.locator('#game').pressSequentially('rich');await page.locator('#game').press('Enter');await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).scrap===5000);
  await back(page);await page.locator('#game').press('Backquote');await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).console);
  pass('Console typing consumes gameplay shortcuts and physical backquote opens it');
  await consoleToggleUiCheck(page,{pass,name});
  await page.screenshot({path:'.build/menus-'+name.toLowerCase()+'.png'});
  await saveCloseUiChecks(page,{snapshot,state,click,pass});
  assert.deepEqual(errors,[]);
 }catch(e){console.log(name+' state:',await page.evaluate(()=>{try{return {engine:window.ftlEngine?.debugSnapshot(),status:document.getElementById('status')?.textContent,message:document.getElementById('message')?.textContent};}catch(e){return String(e);}}));console.log(name+' errors:',errors);await page.screenshot({path:'.build/menu-failure-'+name.toLowerCase()+'.png'});throw e;}
 finally {await browser.close();}
}
await writeFile('.build/menu-results.json',JSON.stringify({at:new Date().toISOString(),checks},null,2));
