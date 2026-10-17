import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';
import {back,enterFullscreen} from './gamebar-ui.mjs';

// Real browser input and the normal frame clock. No engine commands, probes,
// grants, controller observations, seed changes or accelerated time steps.
const origin='http://localhost:8084',checks=[],timeline=[];
const readyDeadline=Date.now()+30000;
while(!await fetch(origin+'/health/ready').then(response=>response.ok).catch(()=>false)) {
 if(Date.now()>readyDeadline)throw Error('Disposable tutorial fixture did not start');
 await new Promise(resolve=>setTimeout(resolve,250));
}
const initial=new Map((await readFile((process.env.DEBUG_FIXTURE_DIR??'.build/debug-data')+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const selected=(process.env.TUTORIAL_UI_BROWSERS??'Chrome,Edge,Firefox').split(',');
const full=(process.env.TUTORIAL_UI_FULL_BROWSERS??'Chrome,Edge,Firefox').split(',').filter(Boolean);
assert.ok([...selected,...full].every(name=>['Chrome','Edge','Firefox'].includes(name)));
for(const [name,type,options] of [['Chrome',chromium,{channel:'chrome'}],['Edge',chromium,{channel:'msedge'}],['Firefox',firefox,{}]].filter(([name])=>selected.includes(name))) {
 const browser=withOnboarding(await type.launch({headless:!(name==='Firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1'),...options}),origin),page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],networkFailures=[];
 await page.addInitScript(()=>{window.tutorialRuntimeErrors=[];window.tutorialFetchFailures=[];const originalFetch=window.fetch;window.fetch=function(...args){const stack=new Error().stack;return originalFetch.apply(this,args).catch(error=>{window.tutorialFetchFailures.push({url:String(args[0]),message:error.message,stack});throw error;});};window.addEventListener('ftl-engine-error',event=>window.tutorialRuntimeErrors.push({message:String(event.detail?.message??event.detail),stack:event.detail?.stack??null}));});
 page.setDefaultTimeout(90000);page.on('requestfailed',request=>networkFailures.push({url:request.url(),failure:request.failure(),at:new Date().toISOString()}));page.on('pageerror',error=>errors.push({message:error.message,stack:error.stack,url:page.url(),networkFailures:[...networkFailures]}));
 const pass=label=>{checks.push({browser:name,label});console.log('PASS '+name+': '+label);};
 const snapshot=()=>page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()));
 const wait=predicate=>page.waitForFunction(predicate,undefined,{timeout:180000});
 const state=label=>page.waitForFunction(label=>JSON.parse(window.ftlEngine.debugSnapshot()).state===label,label,{timeout:15000});
 async function click(x,y,button='left') {
  const r=await page.locator('#game').boundingBox(),scale=Math.min(r.width/1280,r.height/720);
  await page.mouse.click(r.x+(r.width-1280*scale)/2+x*scale,r.y+(r.height-720*scale)/2+y*scale,{button});
 }
 const point=async(x,y)=>{const r=await page.locator('#game').boundingBox(),scale=Math.min(r.width/1280,r.height/720);return {x:r.x+(r.width-1280*scale)/2+x*scale,y:r.y+(r.height-720*scale)/2+y*scale};};
 const press=async key=>{console.log('INPUT '+name+' key '+key);await page.locator('#game').press(key);};
 const progress=()=>page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),storedProfile:window.ftlEngine.profileSave()}));
 // The load event does not wait for this module's asynchronous imports/auth.
 // Await the already-loading module before navigating or using its controls.
 async function appReady() {await page.evaluate(async()=>{const script=document.querySelector('script[type="module"][src*="app.mjs"]');await import(script.src);});}
 async function launch(navigate=true) {
  console.log('TEST '+name+' launch');if(navigate)await page.goto(origin);await appReady();console.log('TEST '+name+' page ready');await page.locator('#launch').click();
  console.log('TEST '+name+' Launch clicked');await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden);
  if(!await page.evaluate(()=>!!window.ftlEngine))await page.locator('#takeover').click();
  await page.waitForFunction(()=>!!window.ftlEngine);await state('MainMenuState');
  // A newly created profile first shows the native welcome ChoiceBox; key 1 continues.
  if(JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot())).window==='WelcomeChoiceBox'){
   await page.locator('#game').press('1');await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window!=='WelcomeChoiceBox');
  }
 }
 async function fullscreen() {
  await enterFullscreen(page);
  // Fullscreen changes cancel held input. Wait for its layout/input reset before clicking.
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 }
 async function startTutorial() {
  const saved=await page.evaluate(()=>!!window.ftlEngine.runSave());console.log('TEST '+name+' Tutorial click saved='+saved);
  await page.waitForTimeout(250);await click(1180,411);
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tutorial,undefined,{timeout:15000});
  await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='DialogueWindow');
  console.log('INPUT '+name+' opening dialogue');await page.waitForTimeout(500);
  await dismiss();
  await page.waitForFunction(()=>!!JSON.parse(window.ftlEngine.debugSnapshot()).tutorialUI?.panel,undefined,{timeout:15000});
  assert.equal((await snapshot()).tutorialStep,'SHIP_INTRO_1');
 }
 async function exitTutorial() {await back(page);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='PauseWindow');const r=(await snapshot()).windowRect;await click(r.x+r.width/2,r.y+81);await state('MainMenuState');}
 async function next() {const s=await snapshot();assert.ok(s.tutorialContinue);const r=s.tutorialUI.continue;await click(r[0]+r[2]/2,r[1]+r[3]/2);await page.waitForFunction(step=>{const s=JSON.parse(window.ftlEngine.debugSnapshot());return !s.tutorial||s.tutorialStep!==step;},s.tutorialStep);}
 async function pause(value) {if((await snapshot()).playerPaused!==value){await press('Space');await page.waitForFunction(value=>JSON.parse(window.ftlEngine.debugSnapshot()).playerPaused===value,value);}}
 async function dismiss() {for(let i=0;i<8&&(await snapshot()).window==='DialogueWindow';i++){await press('1');await page.waitForTimeout(100);}assert.notEqual((await snapshot()).window,'DialogueWindow');}
 async function target(slot) {await press(String(slot+1));const room=(await snapshot()).tutorialUI.enemyRooms.find(r=>r.system==='shields');assert.ok(room);await click(room.x,room.y);await page.waitForFunction(slot=>!!JSON.parse(window.ftlEngine.debugSnapshot()).weapons[slot].target,slot);}
 try {
  console.log('TEST '+name+' login');await page.goto(origin);await appReady();await page.evaluate(async({initial})=>{
   const username='user05',password='tutorial-ui-fixture-password-2026';let csrf=(await(await fetch('/api/auth/csrf')).json()).token;
   const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)});
   let current=password,r=await post('login',{username,password:current});
   if(r.status===401){current='baseline-fixture-password-2026';r=await post('login',{username,password:current});}
   if(r.status===401){current=initial;r=await post('login',{username,password:current});}if(!r.ok)throw Error('Tutorial fixture login '+r.status);
   csrf=(await(await fetch('/api/auth/csrf')).json()).token;
   if((await(await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:current,newPassword:password});if(!r.ok)throw Error('Tutorial fixture rotation');csrf=(await(await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}
  },{initial:initial.get('user05')});
  console.log('TEST '+name+' logged in');await launch();await fullscreen();console.log('TEST '+name+' fullscreen ready');
  // Set up and save an ordinary run through the same visible controls a player uses.
  const hasRun=await page.evaluate(()=>!!window.ftlEngine.runSave());
  console.log('TEST '+name+' ordinary run saved='+hasRun);await click(1180,hasRun?295:353);if(!hasRun){await state('SelectShipState');console.log('TEST '+name+' hangar Start');await click(1170,50);}
  await state('InGameState');await dismiss();await pause(true);
  const ordinary=await snapshot();assert.equal(ordinary.tutorial,false);
  await back(page);await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='PauseWindow');
  const quit=(await snapshot()).windowRect;await click(quit.x+70,quit.y+260);await state('MainMenuState');
  const saved=page.waitForResponse(r=>r.url().endsWith('/api/saves/current')&&r.request().method()==='PUT');await page.locator('#save').click();assert.ok((await saved).ok());
  const before=await progress();assert.ok(before.run);

  await startTutorial();assert.equal((await snapshot()).ae,false);
  await next();await exitTutorial();assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');assert.deepEqual(await progress(),before);
  await startTutorial();await next();await page.reload();await launch(false);assert.deepEqual(await progress(),before);
  pass('Menu entry, pause-menu Main Menu and reload keep ordinary progress; leaving retains fullscreen');
  if(full.includes(name)) {
   await fullscreen();await startTutorial();
   const started=Date.now(),visited=[];
   while((await snapshot()).tutorial) {
    let s=await snapshot();const step=s.tutorialStep;visited.push(step);timeline.push({browser:name,step,elapsedMs:Date.now()-started});console.log('TEST real-time tutorial '+visited.length+'/33 '+step);
    assert.equal(s.tutorialUI.invulnerable,false);assert.equal(s.tutorialUI.enemyFireDisabled,false);assert.ok(s.hull>0);
    if(['DAMAGE_SYSTEM_4','COMBAT_2','INVENTORY_2','DONE_1'].includes(step))await page.screenshot({path:'.build/tutorial-ui-'+name.toLowerCase()+'-'+step+'.png'});
    if(s.tutorialContinue){await next();continue;}
    switch(step) {
     case 'POWER_SYSTEM_2':await press('s');break;
     case 'DAMAGE_SYSTEM_2':{const crew=s.tutorialUI.crew[0];await click(crew.x,crew.y);break;}
     case 'DAMAGE_SYSTEM_3':{const room=s.tutorialUI.rooms.find(r=>r.system==='engines');await click(room.x,room.y,'right');break;}
     case 'DAMAGE_SYSTEM_4':break;
     case 'FIRE_PC_1':{const door=s.tutorialUI.doors.find(d=>d.airlock&&(d.left===9||d.right===9));await click(door.x,door.y);break;}
     case 'FIRE_END':break;
     case 'DAMAGE_SYSTEM_6':{
      for(const door of s.tutorialUI.doors.filter(d=>d.airlock&&d.open))await click(door.x,door.y);
      const crew=s.tutorialUI.crew[0];await click(crew.x,crew.y);const pilot=s.tutorialUI.rooms.find(r=>r.system==='pilot');await click(pilot.x,pilot.y,'right');break;
     }
     case 'DAMAGE_SYSTEM_7':await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tutorialUI.ftlReady);await click(565,50);break;
     case 'STAR_MAP_1':{
      await wait(()=>{const s=JSON.parse(window.ftlEngine.debugSnapshot());return s.window==='JumpWindow'&&s.windowRect.x>0;});s=await snapshot();
      const panel=s.tutorialUI.panel;
      // The map hit area extends 12px around a beacon. Its centre can be
      // covered while an edge is still clickable; use only observed geometry.
      const points=s.tutorialUI.beacons.filter(b=>!b.exit).flatMap(b=>[[0,0],[-11,0],[11,0],[0,-11],[0,11]].map(([dx,dy])=>({x:b.x+dx,y:b.y+dy})));
      const beacon=points.find(b=>!panel||b.x<panel[0]||b.x>=panel[0]+panel[2]||b.y<panel[1]||b.y>=panel[1]+panel[3]);
      assert.ok(beacon,'No accessible neighbouring beacon: '+JSON.stringify({rng:s.rng,panel,beacons:s.tutorialUI.beacons}));await click(beacon.x,beacon.y);
      await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='DialogueWindow');await dismiss();break;
     }
     case 'COMBAT_0':await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tutorialUI.enemyVolleys>0);await pause(true);break;
     case 'COMBAT_1':await press('1');break;
     case 'COMBAT_2':await target(0);await pause(false);await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tutorialStep!=='COMBAT_2');await pause(true);break;
     case 'COMBAT_4':{
      await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='DialogueWindow');await dismiss();
      await click(290,640,'right');await press('2');await target(1);await pause(false);break;
     }
     case 'COMBAT_4_6':
      if(!s.autofire)await press('v');await target(1);await pause(false);
      await wait(()=>!JSON.parse(window.ftlEngine.debugSnapshot()).tutorialUI.enemyPresent);
      await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).window==='DialogueWindow');await dismiss();break;
     case 'INVENTORY_1':await press('i');break;
     case 'INVENTORY_2':{
      const w=s.windowRect,cargo=s.tutorialUI.cargo.find(c=>c.beam);assert.ok(cargo);
      const width=await page.evaluate(()=>{const b=window.ftlFiles.get('img/upgradeUI/Equipment/box_weapons_off.png');return new DataView(b.buffer,b.byteOffset,b.byteLength).getUint32(16);});
      const weaponX=(579-(width+8)*2)/2+width+8;
      const a=await point(w.x+23+130*(cargo.slot%2)+30,w.y+293+80*Math.floor(cargo.slot/2)+20),b=await point(w.x+weaponX+30,w.y+90);
      await page.mouse.move(a.x,a.y);await page.mouse.down();await page.mouse.move(b.x,b.y,{steps:12});await page.mouse.up();
      await wait(()=>JSON.parse(window.ftlEngine.debugSnapshot()).weapons[1].weapon==='BEAM_2');await click(w.x+480,w.y+485);break;
     }
     case 'COMBAT_5':await press('u');break;
     case 'UPGRADES_1_2':{const w=s.windowRect,index=s.tutorialUI.systems.indexOf('weapons');assert.ok(index>=0);await click(w.x+62+66*index,w.y+180);await click(w.x+480,w.y+485);break;}
     case 'UPGRADES_2':await press('2');break;
     default:throw Error('Unknown real-time tutorial action '+step);
    }
    await page.waitForFunction(step=>{const s=JSON.parse(window.ftlEngine.debugSnapshot());return !s.tutorial||s.tutorialStep!==step||s.tutorialContinue;},step,{timeout:180000});
    s=await snapshot();if(s.tutorial&&s.tutorialStep===step&&s.tutorialContinue)await next();
   }
   assert.equal(visited.length,33);assert.equal(new Set(visited).size,33);await state('MainMenuState');assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');assert.deepEqual(await progress(),before);
   pass('All 33 lessons complete through real-time pointer/keyboard input, normal enemy fire and paid upgrades; fullscreen/progress retained');
  }
  await click(1180,295);await state('InGameState');const continued=await snapshot();
  assert.equal(continued.tutorial,false);for(const key of ['ship','ae','difficulty','hull'])assert.equal(continued[key],ordinary[key],key+' changed across Tutorial/Continue');
  pass('Ordinary Continue restores the saved ship, mode, difficulty and hull after tutorial completion/exit/reload');
  assert.deepEqual(errors,[]);assert.deepEqual(await page.evaluate(()=>window.tutorialRuntimeErrors),[]);
 } catch(error) {const failed=await snapshot().catch(()=>null);console.log('Tutorial UI failure state:',{state:failed?.state,step:failed?.tutorialStep,window:failed?.window,hull:failed?.hull});await writeFile('.build/tutorial-ui-failure-'+name.toLowerCase()+'.json',JSON.stringify({state:failed,errors,runtime:await page.evaluate(()=>window.tutorialRuntimeErrors),fetchFailures:await page.evaluate(()=>window.tutorialFetchFailures),connection:await page.locator('#connection-message').textContent(),timeline},null,2));console.log('Tutorial UI page errors:',errors);await page.screenshot({path:'.build/tutorial-ui-failure-'+name.toLowerCase()+'.png'});throw error;}
 finally {await browser.close();}
}
await writeFile('.build/tutorial-ui-results.json',JSON.stringify({at:new Date().toISOString(),checks,timeline},null,2));
