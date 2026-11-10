// The one debug console (docs/CONSOLE.md) through the website, in vanilla or in a Hyperspace session (the loader's
// preload sets window.ftlModSession from FTL_HS_MOD_SESSION, as for hs-core.mjs): the gating rule with the Logs page's
// "Debug console" setting off (vanilla: closed on ` and \; Hyperspace: \ opens it while the mod has
// <console enabled="true"/>, ` stays HS's speed key, <console enabled="false"/> keeps it closed), then the setting
// turned on in the Logs page (Console button, ` and \ in every mode), typing with the cursor keys, the history on Up,
// help, and the Hyperspace and original commands run with their effect read back (BrowserEngine.consoleProbe); the
// Hyperspace-only ones answer that they need a mod in vanilla; the new get and unlock commands (their pickers' lists
// against the data, by name, a wildcard, the pickers on screen, the suggestions' scroll, help's reference, all of it
// through Save and close and Continue, the unlock in the hangar); the look and the feel (round 22: the picker's rows at
// 1280x720, a cut tile at the view's edge, pixel wheel and drag scrolling, the scroll bar, the keys, frame strips of the
// scrolling as console-*-strip-*.png). Test-only. Output: /out/console-*.png, CONSOLE_OUT.
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] node /app/tests/console.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8086,origin='http://localhost:'+port,out=process.env.CONSOLE_OUT||'/out';
const data=join(process.cwd(),'.build/fixture-console');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=process.env.FTL_HS_MOD_SESSION?JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8')):null;
const hs=!!session?.hyperspace,mode=hs?'hs':'vanilla';
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e)&&!/Content Security Policy directive 'style-src/.test(e.text()))errors.push('CONSOLE '+e.text());});
let failed=false;
const pass=t=>console.log('PASS '+t);
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='console-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 pass(mode+' main menu');
 const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const probe=async()=>JSON.parse(await page.evaluate(()=>window.consoleProbe()));
 const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const key=async k=>{await page.locator('#game').press(k);await flush();await flush();};
 const isOpen=async()=>(await snapshot()).console===true;
 const output=async()=>(await page.evaluate(()=>window.ftlEngine.consoleOutput())).split('\n');
 const run=async c=>{await page.evaluate(c=>window.ftlEngine.consoleExecute(c),c);await flush();await flush();return probe();};
 const shot=async n=>page.locator('#game').screenshot({path:join(out,'console-'+mode+'-'+n+'.png')});
 // the run's own boxes (the start event, a store, the map) closed: an event box with its proceed/enough/continue
 // choice (Multiverse's start menu and lore chain), else choices in turn, the rest with Escape
 const closeWindows=async()=>{for(let i=0;i<40;i++){const s=await snapshot(),w=s.window;if(w==='game')return;
   if(w!=='DialogueWindow')await key('Escape');else if(s.dialogue?.ready){const p=await probe(),c=p.choices.findIndex(t=>/proceed|enough|continue/i.test(t));await key(String(c>=0?c+1:1+i%Math.max(1,p.choices.length)));}
   await page.waitForTimeout(400);}throw Error('a window stays open: '+(await snapshot()).window);};
 const hsProbe=c=>page.evaluate(c=>window.hsWp7UiProbe(c),c);

 await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:120000});
 await page.waitForTimeout(1500);await closeWindows();
 if(await isOpen())await key('Escape');

 // ---- the setting off (a new browser) ----------------------------------------------------------------------------
 assert.equal(await page.evaluate(()=>localStorage.getItem('ftl-debug-console')),null,'off by default');
 assert.ok(await page.locator('#console').isHidden(),'no Console button without the setting');
 if(!hs){
  await key('Backquote');assert.equal(await isOpen(),false,'vanilla: ` without the setting');
  await key('Backslash');assert.equal(await isOpen(),false,'vanilla: \\ without the setting');
  pass('vanilla without the setting: the console stays closed');
 }else{
  assert.equal((await probe()).gate,true,'the mod has <console enabled="true"/>');
  await key('Backquote');assert.equal(await isOpen(),false,'` stays the speed hotkey');
  await key('Backquote');
  await key('Backslash');assert.equal(await isOpen(),true,'\\ opens it as Hyperspace does');
  await shot('hs-open');
  await key('Escape');assert.equal(await isOpen(),false);
  console.log(await hsProbe('xml:<console enabled="false"/>'));
  assert.equal((await probe()).gate,false);
  await key('Backslash');assert.equal(await isOpen(),false,'<console enabled="false"/> keeps it closed');
  pass('Hyperspace without the setting: \\ opens it while the mod enables it, ` is speed, enabled="false" keeps it closed');
 }

 // ---- the setting on, from the Logs page ------------------------------------------------------------------------
 await page.locator('#logs-button').click();await page.locator('#console-enabled').check();await page.locator('#logs-panel .close-panel').click();
 await page.locator('#game-panel').waitFor({state:'visible'});
 await page.locator('#game').focus();await closeWindows(); // leaving for the Logs page paused the run (its pause menu)
 assert.equal(await page.evaluate(()=>localStorage.getItem('ftl-debug-console')),'1');
 assert.ok(await page.locator('#console').isVisible(),'the Console button with the setting');
 await page.locator('#game').focus();
 await key('Backquote');assert.equal(await isOpen(),true,'` with the setting');
 await key('Escape');assert.equal(await isOpen(),false);
 await key('Backslash');assert.equal(await isOpen(),true,'\\ with the setting');
 await key('Backquote');assert.equal(await isOpen(),false,'` closes it');
 await page.locator('#console').click();assert.equal(await isOpen(),true,'the Console button opens it');
 await page.locator('#console').click();assert.equal(await isOpen(),false,'and closes it');
 pass(mode+': the setting opens the console on ` and \\ and shows the Console button'+(hs?' (the mod says enabled="false")':''));
 if(hs)console.log(await hsProbe('xml:<console enabled="true"/>'));

 // ---- an empty picker says why (round 22's message, checked on the web in round 23): a search with no match, and
 // (plain Hyperspace, CONSOLE_PART=empty) a kind the run has none of: Hyperspace's own achievements
 {
  const panelText=async()=>(await probe()).panel;
  const emptyNote=async()=>{const p=await panelText(),m=/ emptyAt=(-?\d+),(-?\d+),(\d+) empty=(.*)$/.exec(p),v=/ view=(-?\d+),(-?\d+),(\d+),(\d+)/.exec(p);
   return m?{x:+m[1],y:+m[2],w:+m[3],text:m[4],view:v?v.slice(1).map(Number):null,raw:p}:{text:null,raw:p};};
  const shut=async()=>{for(let i=0;i<4&&await isOpen();i++)await key('Escape');assert.equal(await isOpen(),false);};
  const visible=n=>assert.ok(n.view&&n.x>=n.view[0]&&n.x+n.w<=n.view[0]+n.view[2]&&n.y>n.view[1]&&n.y<n.view[1]+n.view[3],'the message is inside the panel: '+n.raw);
  const multiverse=(await probe()).multipleOver,fixture=multiverse?'mv':hs?'hs':'vanilla';
  await key('Backquote');await page.locator('#game').pressSequentially('get weapon',{delay:15});await key('Enter');await page.waitForTimeout(300);
  let n=await emptyNote();assert.match(n.raw,/ItemPicker kind=WEAPON/,'get weapon opens the picker');assert.equal(n.text,null,'a picker with items has no message');
  // the line reads "get weapon " after Enter: the search follows that space
  const line=/text=(.*) cursor=/.exec((await probe()).console)[1];
  await page.locator('#game').pressSequentially((line.endsWith(' ')?'':' ')+'zzqx',{delay:15});await flush();await flush();await page.waitForTimeout(300);
  n=await emptyNote();console.log('no match: '+n.raw);
  // the search as the line now reads it (plain Hyperspace's <console invertCaps="true"/> types it in capitals)
  const search=/text=(.*) cursor=/.exec((await probe()).console)[1].split(' ').at(-1);assert.equal(search.toLowerCase(),'zzqx');
  assert.match(n.raw,/ shown=0 /,'nothing matches zzqx');assert.equal(n.text,"Nothing matches '"+search+"'.",'the message names the search');visible(n);
  await page.mouse.move(640,40);await shot('r23-nothing-matches');
  await shut();
  await key('Backquote');await page.locator('#game').pressSequentially('unlock achievement',{delay:15});await key('Enter');await page.waitForTimeout(300);
  n=await emptyNote();console.log('achievements: '+n.raw);
  if(hs&&!multiverse){
   assert.match(n.raw,/ItemPicker kind=ACHIEVEMENT.* shown=0 /,'plain Hyperspace has no achievements of its own');
   assert.equal(n.text,'This run has none.','an empty kind says so');visible(n);
  }else{assert.match(n.raw,/ItemPicker kind=ACHIEVEMENT/);assert.equal(n.text,null,fixture+': the achievements are listed');}
  await page.mouse.move(640,40);await shot('r23-achievements');
  await shut();
  pass(fixture+': an empty picker says why ("Nothing matches \'zzqx\'."'+(hs&&!multiverse?', "This run has none."':'')+'), inside the panel');
  // Hyperspace starts its Lua state with or without mod scripts (Global.cpp:138, LuaScriptInit), so LUA runs on plain
  // Hyperspace too (sweep M1, O-A1-2)
  if(hs){
   await run('LUA print("ps-m1 " .. 6 * 7)');const prof=JSON.parse(await page.evaluate(()=>window.hsProfile(false)));
   assert.equal(prof.active,true,fixture+': the Lua runtime is up');
   assert.ok(prof.log.includes('[print] ps-m1 42'),fixture+': LUA ran (log: '+prof.log.slice(-3).join(' / ')+')');
   pass(fixture+': LUA runs'+(multiverse?'':' without mod scripts'));
  }
  if(process.env.CONSOLE_PART==='empty'){assert.deepEqual(errors,[],'browser errors');console.log('PASS console empty pickers '+fixture);await browser.close();server.kill('SIGTERM');process.exit(0);}
 }

 // ---- typing, the cursor keys, the history ----------------------------------------------------------------------
 const scrap0=(await snapshot()).scrap;
 await key('Backquote');
 await page.locator('#game').pressSequentially('crap 1',{delay:20});await key('Home');await page.locator('#game').pressSequentially('s');await key('End');await page.locator('#game').pressSequentially('0');await flush();await flush();
 assert.match((await probe()).console,/text=scrap 10 cursor=8/);
 await key('Enter');
 await key('ArrowUp');assert.match((await probe()).console,/text=scrap 10/,'Up recalls the last command');
 await key('Enter');
 assert.equal((await snapshot()).scrap,scrap0+20,'SCRAP 10 twice');
 assert.equal((await output()).filter(l=>l==='> scrap 10').length,2);
 await page.locator('#game').pressSequentially('help');await flush();await key('Enter');
 const help=await output();
 for(const n of ['RICH','LOADEVENT','LUA','SHIP','EVENT','dev-rich','dev-event','upall','spawn-ship','help'])assert.ok(help.some(l=>l.startsWith(n+'\t')),'help lists '+n);
 await shot('help');
 await key('Escape');
 pass(mode+': typing with Left/Home/End, the history on Up, help lists every command');

 // ---- get and unlock, the pickers, help (round 20, docs/CONSOLE.md "get and unlock") ------------------------------
 const items=async(c,k)=>JSON.parse(await page.evaluate(([c,k])=>window.consoleItems(c,k),[c,k]));
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),sc=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*sc)/2+x*sc,box.y+(box.height-720*sc)/2+y*sc];};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();await page.mouse.down();await page.mouse.up();await flush();await flush();};
 const wheel=async(x,y,dy)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();await page.mouse.wheel(0,dy);await flush();await flush();};
 const type=async t=>{await page.locator('#game').pressSequentially(t,{delay:15});await flush();await flush();};
 const panel=async()=>(await probe()).panel;
 const consoleText=async()=>/text=(.*) cursor=/.exec((await probe()).console)[1];
 const owned=s=>[...s.weapons,...s.drones,...s.cargo,...s.augments];
 const tagCount=list=>{const c={};for(const i of list)for(const t of i.tags)c[t]=(c[t]||0)+1;return c;};
 // the pickers' lists hold every item of the data (the names read straight from the run's blueprint files), once each
 const lists={};
 for(const kind of ['weapon','drone','augment','crew','system']){
  const r=await items('get',kind);lists[kind]=r.items;const ids=r.items.map(i=>i.id);
  const missing=r.data.filter(n=>!ids.includes(n)),extra=ids.filter(n=>!r.data.includes(n));
  console.log('picker '+kind+': '+ids.length+' items, data '+r.data.length+', tags '+JSON.stringify(tagCount(r.items))+(missing.length?', not offered '+missing.slice(0,12).join(' '):''));
  assert.deepEqual(extra,[],kind+': nothing the data lacks');
  if(kind!=='system')assert.deepEqual(missing,[],kind+': every '+kind+' of the data');
  assert.equal(new Set(r.items.map(i=>i.key.toLowerCase())).size,r.items.length,kind+': one key each');
  assert.ok(r.items.every(i=>i.key&&!/\s/.test(i.key)&&i.english),kind+': English names, spaces as underscores');
 }
 lists.ship=(await items('unlock','ship')).items;lists.achievement=(await items('unlock','achievement')).items;
 for(const k of ['ship','achievement'])assert.equal(new Set(lists[k].map(i=>i.key.toLowerCase())).size,lists[k].length,k+': one key each');
 console.log('picker ships: '+lists.ship.length+' layouts, '+lists.ship.filter(i=>i.tags.includes('locked')).length+' locked; achievements '+lists.achievement.length+'; e.g. '+lists.ship.slice(0,4).map(i=>i.key).join(' '));
 const keyOf=(kind,id)=>lists[kind].find(i=>i.id===id)?.key;
 if(!hs){
  assert.equal(keyOf('weapon','LASER_BURST_3'),'Burst_Laser_Mark_II','the title is the key');
  for(const [id,k] of [['PLAYER_SHIP_HARD','The_Kestrel_A'],['PLAYER_SHIP_HARD_2','The_Kestrel_B']])assert.equal(keyOf('ship',id),k,'ships per layout: '+k);
 }
 pass(mode+': the pickers list the data\'s items under their English names (spaces as underscores), one key each');

 // round 2: items that share a name get keys that end in their id, each key is one item; art the game lacks is tagged
 // (the tile shows a placeholder); hidden and event-only items are listed and searchable (never filtered out)
 for(const kind of ['weapon','drone','augment','crew','system','ship','achievement']){
  const list=lists[kind],byName={};for(const i of list)(byName[i.english.toLowerCase()]??=[]).push(i);
  const shared=Object.values(byName).filter(g=>g.length>1);
  for(const g of shared)for(const i of g)assert.ok(i.key.endsWith('_'+i.id)||i.key.startsWith(i.id),kind+' '+i.id+': a shared name ends in the id ('+i.key+')');
  const noArt=list.filter(i=>i.tags.includes('no art'));
  console.log('sweep '+kind+': '+shared.length+' shared names ('+shared.slice(0,6).map(g=>g.map(i=>i.key).join('/')).join(', ')+(shared.length>6?', ...':'')+'); no art '+noArt.length+(noArt.length?': '+noArt.slice(0,40).map(i=>i.id).join(' '):''));
 }
 if(!hs){
  for(const id of ['ARTILLERY_FED','ARTILLERY_FED_C'])assert.ok(lists.weapon.find(i=>i.id===id).tags.includes('no art'),id+': the game has no art for it (weapons/blank.png)');
  assert.ok(lists.weapon.find(i=>i.id==='LASER_BURST_3').tags.every(t=>t!=='no art'),'a weapon with art');
  // and the boss's CRYSTAL_1, whose weaponArt crystal_shard is an <anim>, not a <weaponAnim>
  assert.deepEqual(lists.weapon.filter(i=>i.tags.includes('no art')).map(i=>i.id).sort(),['ARTILLERY_FED','ARTILLERY_FED_C','CRYSTAL_1'],'vanilla: only these weapons lack art');
  for(const k of ['drone','crew','system','ship','achievement'])assert.equal(lists[k].filter(i=>i.tags.includes('no art')).length,0,'vanilla '+k+': every one has its art');
  assert.equal(keyOf('weapon','MISSILES_2'),'Artemis_Missiles_MISSILES_2');assert.equal(keyOf('weapon','MISSILES_2_PLAYER'),'Artemis_Missiles_MISSILES_2_PLAYER');
  assert.equal(keyOf('weapon','BEAM_FIRE'),'Fire_Beam_BEAM_FIRE');assert.equal(keyOf('weapon','DRONE_FIREBEAM'),'Fire_Beam_DRONE_FIREBEAM');
 }else{
  const hidden=lists.augment.filter(i=>i.tags.includes('hidden'));
  console.log('hidden augments '+hidden.length+', event-only weapons '+lists.weapon.filter(i=>i.tags.includes('event only')).length+', drones '+lists.drone.filter(i=>i.tags.includes('event only')).length+', crew '+lists.crew.filter(i=>i.tags.includes('event only')).length);
  assert.ok(hidden.length>0,'Multiverse: hidden augments are listed');
  assert.ok(lists.achievement.filter(i=>i.tags.includes('no art')).length<lists.achievement.length/10,'Multiverse achievements have their icons');
 }
 pass(mode+': shared names end in the id, the art the game lacks is tagged, hidden and event-only items are listed');

 // by name, through the old commands
 let s;let before=await probe();
 const burst=keyOf('weapon','LASER_BURST_3')||lists.weapon.find(i=>!i.tags.length)?.key;
 s=await run('get weapon '+burst);const burstId=lists.weapon.find(i=>i.key===burst).id;
 assert.equal(owned(s).filter(n=>n===burstId).length,owned(before).filter(n=>n===burstId).length+1,'get weapon '+burst+' (WEAPON)');
 assert.match((await output()).at(-1),/^Granted: .* - (in a weapon slot|to the cargo)/,'get says what it granted and where');
 if(!hs){const n0=owned(s).filter(n=>n==='LASER_BURST_3').length;s=await run('get weapon burst laser ii');assert.equal(owned(s).filter(n=>n==='LASER_BURST_3').length,n0+1,'the short name, any case, spaces: Burst Laser II');}
 if(!hs){
  s=await run('get weapon Fire_Beam');assert.match((await output()).at(-1),/^Granted: Fire Beam .*WEAPON BEAM_FIRE\)$/,'a shared name: the one a store sells');
  assert.match((await output()).at(-2),/^'Fire_Beam' is Fire_Beam_BEAM_FIRE here; also Fire_Beam_DRONE_FIREBEAM/);
  await run('get weapon Artemis');assert.match((await output()).at(-1),/^'Artemis' could be 2 weapons: Artemis_Missiles_MISSILES_2, Artemis_Missiles_MISSILES_2_PLAYER/,'both event only: the keys tell them apart');
  s=await run('get weapon Artemis_Missiles_MISSILES_2_PLAYER');assert.match((await output()).at(-1),/^Granted: .*WEAPON MISSILES_2_PLAYER\)$/);
 }
 const combat=keyOf('drone','COMBAT_1');s=await run('get drone '+combat);assert.ok(owned(s).includes('COMBAT_1'),'get drone '+combat+' (DRONE)');
 const aug=keyOf('augment','SCRAP_COLLECTOR');s=await run('get augment '+aug);assert.ok(s.augments.includes('SCRAP_COLLECTOR'),'get augment '+aug+' (AUG)');
 const crewBefore=s.crew.length;const mantis=keyOf('crew','mantis');s=await run('get crew '+mantis);assert.equal(s.crew.length,crewBefore+1,'get crew '+mantis+' (CREW)');
 assert.ok(s.crew.some(c=>c.startsWith('mantis:')));
 const cloak=keyOf('system','cloaking');s=await run('get system '+cloak);console.log('get system '+cloak+': '+(await output()).at(-1));
 const scrapBefore=(await snapshot()).scrap;await run('get scrap 100');assert.equal((await snapshot()).scrap,scrapBefore+100,'get scrap 100 (SCRAP)');
 await run('get weapon');assert.equal(await consoleText(),'get weapon ','no name: the picker');
 // the ship switch is gone (user, 2026-11-05): unlock the ship and start a new run; SWITCH/SWITCHALL stay
 await run('get ship The_Torus_B');assert.match((await output()).at(-1),/^get ship is gone: unlock the ship/);
 assert.ok(!(await items('get','ship')).items,'no ship kind for get');
 const lockedShip=lists.ship.find(i=>i.tags.includes('locked'));assert.ok(lockedShip,'a locked layout to unlock');
 s=await run('unlock ship '+lockedShip.key);console.log((await output()).at(-1));
 assert.equal(await page.evaluate(id=>window.consoleLayoutUnlocked(id),lockedShip.id),true,'unlock ship '+lockedShip.key);
 const lockedAch=lists.achievement.find(i=>i.tags.includes('locked'));
 if(lockedAch){await run('unlock achievement '+lockedAch.key);console.log((await output()).at(-1));
  assert.ok((await items('unlock','achievement')).items.find(i=>i.id===lockedAch.id).tags.includes('unlocked'),'unlock achievement '+lockedAch.key);}
 // a wildcard: every layout of one ship
 const family=lists.ship.find(i=>i.layout===1&&i.tags.includes('locked')&&i.id!==lockedShip.id);
 if(family){const stem=family.key.slice(0,-1);await run('unlock ship '+stem+'*');
  const now=(await items('unlock','ship')).items.filter(i=>i.key.startsWith(stem));
  console.log('unlock ship '+stem+'*: '+now.map(i=>i.key+'='+i.tags).join(' '));assert.ok(now.every(i=>i.tags.includes('unlocked')),'unlock ship '+stem+'*');}
 assert.deepEqual((await output()).filter(l=>/^Command failed|^Unknown command/.test(l)),[],'no get/unlock failed');
 pass(mode+': get weapon/drone/augment/crew/system/scrap and unlock ship/achievement by name, with a wildcard, through WEAPON DRONE AUG CREW SYS SCRAP and the unlocks');

 // the pickers on screen: the store's boxes, the hangar's cards; a click gets one and keeps the picker
 await key('Backquote');
 for(const [line,label] of [['get weapon','weapon'],['get drone','drone'],['get augment','augment'],['get crew','crew'],['get system','system'],['unlock ship','ship'],['unlock achievement','achievement']]){
  await type(line);await key('Enter');await page.waitForTimeout(200);
  assert.match(await panel(),/^ItemPicker/,line+' opens its picker');
  await shot('picker-'+label);
  if(label==='weapon'){
   const s0=await probe();await click(120,250);const s1=await probe();
   assert.equal(owned(s1).length,owned(s0).length+1,'a click on a weapon gets it');assert.equal(await consoleText(),'get weapon ','the picker stays');
   assert.match((await output()).at(-1),/^Granted: /,'a tap says what it granted');
   await wheel(600,600,400);assert.match(await panel(),/scroll=[1-9]/,'the wheel scrolls the picker');await shot('picker-weapon-scrolled');
   await type('burst');await shot('picker-weapon-search');assert.match(await panel(),/shown=[1-9]/);
   for(const [q,n] of hs?[['artillery','noart'],['artemis','shared']]:[['artillery','noart'],['artemis','shared'],['fire beam','shared2']]){
    await key('Escape');await key('Backquote');await type('get weapon '+q);await page.waitForTimeout(200);await shot('r2-picker-weapon-'+n);assert.match(await panel(),/shown=[1-9]/,'search '+q);}
  }
  if(label==='augment'&&hs){const h=lists.augment.find(i=>i.tags.includes('hidden'));await type(h.key);await page.waitForTimeout(200);
   assert.match(await panel(),/shown=[1-9]/,'a hidden augment is found by its name');await shot('r2-picker-augment-hidden');}
  if(label==='crew'&&hs){await type('admiral');await page.waitForTimeout(200);await shot('r2-picker-crew-shared');}
  await key('Escape');await key('Backquote');
 }
 pass(mode+': each picker opens without a name, a click gets the item and keeps it open, the wheel scrolls, typing searches');

 // ---- the look and the feel (round 22, docs/CONSOLE.md "The window", "Scrolling", "Keys"): the picker takes most of
 // the screen with as many rows as fit, tiles at the edge draw cut (a clip), the wheel and a drag scroll by pixels and
 // glide, the scroll bar drags and pages, the keys select and take; frame strips of the scrolling in /out
 const info=async()=>{const p=await panel(),g=k=>{const m=new RegExp(' '+k+'=([^ ]+)').exec(p);return m?m[1]:null;},xs=k=>(g(k)||'0,0,0,0').split(',').map(Number);
  return {raw:p,scroll:+g('scroll'),height:+g('height'),max:+g('max'),target:+g('target'),moving:g('moving')==='true',selected:g('selected')==='true',view:xs('view'),thumb:g('thumb')==='null'?null:xs('thumb'),
   cols:+g('cols'),rows:+g('rows'),gap:+g('gap'),step:+g('step'),top:+g('top'),left:+g('left'),rowsInView:+g('rowsInView'),tile:(g('tile')||'0x0').split('x').map(Number)};};
 const settle=async()=>{for(let i=0;i<200;i++){const p=await info();if(!p.moving)return p;await flush();}throw Error('the panel never settles: '+(await panel()));};
 const cssScale=async()=>{const b=await page.locator('#game').boundingBox();return Math.min(b.width/1280,b.height/720);};
 const pixels=(x,y,w,h)=>page.evaluate(([x,y,w,h])=>{const c=document.querySelector('#game'),o=document.createElement('canvas');o.width=w;o.height=h;const g=o.getContext('2d');g.drawImage(c,x,y,w,h,0,0,w,h);return Array.from(g.getImageData(0,0,w,h).data);},[x,y,w,h]);
 const same=(a,b)=>{let n=0;for(let i=0;i<a.length;i+=4)if(a[i]===b[i]&&a[i+1]===b[i+1]&&a[i+2]===b[i+2])n++;return n/(a.length/4);};
 const distinct=a=>{const s=new Set();for(let i=0;i<a.length;i+=4)s.add(a[i]<<16|a[i+1]<<8|a[i+2]);return s.size;};
 // consecutive frames (each frame's [x,y,w,h], in rows of 6, magenta between) while [act] runs, and their scroll
 const strip=async(name,region,frames,act)=>{
  for(let i=0;i<6;i++)await flush(); // still frames first, so the motion stands apart in the trace
  await page.evaluate(([r,n])=>{const c=document.querySelector('#game'),s={n,frames:[],at:[]};window.__strip=s;
   const tick=t=>{if(s.frames.length>=n)return;const o=document.createElement('canvas');o.width=r[2];o.height=r[3];o.getContext('2d').drawImage(c,r[0],r[1],r[2],r[3],0,0,r[2],r[3]);s.frames.push(o);s.at.push(t);requestAnimationFrame(tick);};
   requestAnimationFrame(tick);},[region,frames]);
  await act();
  await page.waitForFunction(()=>window.__strip.frames.length>=window.__strip.n,{},{timeout:60000});
  const url=await page.evaluate(([w,h])=>{const f=window.__strip.frames,cols=6,rows=Math.ceil(f.length/cols),o=document.createElement('canvas');o.width=cols*(w+4)-4;o.height=rows*(h+4)-4;
   const g=o.getContext('2d');g.fillStyle='#f0f';g.fillRect(0,0,o.width,o.height);f.forEach((c,i)=>g.drawImage(c,(i%cols)*(w+4),Math.floor(i/cols)*(h+4)));return o.toDataURL('image/png');},[region[2],region[3]]);
  await writeFile(join(out,'console-'+mode+'-'+name+'.png'),Buffer.from(url.split(',')[1],'base64'));
  const trace=(await probe()).trace.split(' ').map(t=>t.split(':').map(Number));
  return trace;
 };
 // a trace's panel scroll, from the frame it first moved to where it stopped
 // (the last motion in the trace: back from where it stopped to six still frames before it)
 const motion=trace=>{const p=trace.map(t=>t[1]);let b=p.length-1;while(b>0&&p[b]===p[b-1])b--;let a=b,still=0;
  while(a>0){if(p[a-1]===p[a]){if(++still>=6){a+=still-1;break;}}else still=0;a--;}
  const m=p.slice(a,b+1);m.dt=trace.slice(a+1,b+1).map(t=>t[0]);return m;};
 const smooth=(what,m,total)=>{const d=m.slice(1).map((v,i)=>v-m[i]);console.log(what+' per frame: '+d.join(' ')+' (frame ms '+(m.dt||[]).join(' ')+'; px/ms '+d.map((x,i)=>(x/((m.dt||[])[i]||1)).toFixed(2)).join(' ')+')');
  assert.ok(m.length>=5,what+': over several frames, not a jump: '+m.join(' '));
  assert.ok(d.every(x=>Math.sign(x)===Math.sign(total)||x===0),what+': one way only (no jitter, no spring back): '+d.join(' '));
  assert.ok(Math.max(...d.map(Math.abs))<=Math.abs(total)*0.5,what+': no step takes half the way: '+d.join(' '));};

 if(!(await isOpen()))await key('Backquote');
 await type('get weapon');await key('Enter');await page.waitForTimeout(300);
 await page.mouse.move(...await at(640,40));await flush();
 let p=await settle();console.log('weapon picker: '+p.raw);
 const [vx,vy,vw,vh]=p.view;
 assert.ok(p.rowsInView>=3,'at 1280x720 the weapon picker shows three rows and more, not one: '+p.rowsInView);
 assert.ok(vy<=200&&vy+vh>=620&&vw>=1150,'the picker takes most of the screen: view '+p.view);
 const span=p.cols*(p.tile[0]+p.gap)-p.gap,rightGap=vw-p.left-span;
 assert.ok(p.cols>=8&&Math.abs(rightGap-p.left)<=1,'the tiles keep their size and centre in the view: '+p.cols+' columns, '+p.left+' / '+rightGap);
 await shot('r22-picker-weapon');
 // the clip: scrolled so the first row is cut 60 pixels into its art (its name box), that row draws from the view's top edge on,
 // exactly as it looked 60 pixels lower at the top, and nothing of it lands on the header above the view
 const sc=await cssScale(),cut=p.top+60,tx=vx+p.left+20;
 const art0=await pixels(tx,vy+cut,60,6),head0=await pixels(vx,vy-10,vw,10);
 await wheel(640,400,cut*sc);await page.mouse.move(...await at(640,40));p=await settle();
 assert.equal(p.scroll,cut,'the wheel scrolls by pixels: '+cut+' asked');
 const art1=await pixels(tx,vy,60,6),head1=await pixels(vx,vy-10,vw,10);
 await shot('r22-picker-weapon-cut');
 // and where the gap between two rows is at the top, only the window's background is there
 const gapAt=p.top+p.tile[1]+2;await wheel(640,400,(gapAt-cut)*sc);await page.mouse.move(...await at(640,40));p=await settle();
 assert.equal(p.scroll,gapAt);const bg=await pixels(tx,vy,60,6);
 console.log('clip: cut row '+(same(art0,art1)*100).toFixed(1)+'% as drawn unscrolled, '+(same(art1,bg)*100).toFixed(1)+'% like the background, '+distinct(art1)+' colours; header '+(same(head0,head1)*100).toFixed(1)+'% unchanged');
 assert.ok(same(art0,art1)>=0.95&&same(art1,bg)<0.5,'a half-visible tile draws cut at the view\'s edge');
 assert.equal(same(head0,head1),1,'nothing drawn past the clip into the header');
 // the wheel: by its distance, glided to, exactly; to the end and no further
 await wheel(640,400,-2000);p=await settle();assert.equal(p.scroll,0,'back at the top');
 await wheel(640,400,400);p=await settle();const want=Math.min(p.max,Math.round(400/sc));
 assert.ok(Math.abs(p.scroll-want)<=1,'the wheel: 400 screen pixels are '+want+' here, scrolled '+p.scroll);
 await wheel(640,400,100000);p=await settle();assert.equal(p.scroll,p.max,'the wheel stops at the end');
 await wheel(640,400,-100000);p=await settle();
 // a mouse wheel's notches glide (a frame strip of the view's top edge, cut tiles included)
 let tr=await strip('strip-wheel-pc',[vx,vy-12,420,200],24,async()=>{for(let i=0;i<3;i++){await page.mouse.wheel(0,100);}});
 p=await settle();smooth('wheel',motion(tr),p.scroll);
 // a drag: the content follows the pointer pixel for pixel; the mouse does not fling; a drag is not a click
 const own0=owned(await probe()).length,s0=p.scroll;
 tr=await strip('strip-drag-pc',[vx,vy-12,420,200],24,async()=>{const [px,py]=await at(640,520);await page.mouse.move(px,py);await page.mouse.down();
  for(let i=1;i<=12;i++){await page.mouse.move(px,py-i*10*sc);await flush();}await page.mouse.up();});
 p=await settle();
 assert.equal(p.scroll,s0+120,'a drag of 120 pixels scrolls 120');assert.equal(owned(await probe()).length,own0,'a drag gets nothing');
 smooth('drag',motion(tr),120);
 // the scroll bar: its thumb drags, its track pages
 assert.ok(p.thumb,'a scroll bar while the content is taller than the view');
 const [bx,by,bw,bh]=p.thumb,thumb0=p.scroll;
 {const [px,py]=await at(bx+bw/2,by+bh/2);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.move(px,py+40*sc,{steps:4});await flush();await page.mouse.up();}
 p=await settle();assert.ok(p.scroll>thumb0+20,'dragging the thumb scrolls: '+thumb0+' -> '+p.scroll);await shot('r22-picker-weapon-bar');
 const s1=p.scroll;await click(bx+bw/2,Math.min(vy+vh-4,p.thumb[1]+p.thumb[3]+30));p=await settle();
 assert.ok(p.scroll>s1,'a click on the track under the thumb pages down: '+s1+' -> '+p.scroll);
 // the keys: Page Up and Page Down scroll a page; Down selects the first tile in view, Right the next, Enter gets it
 // (the picker stays), Escape drops the selection and the console stays; the next Escape closes it. On the crew
 // picker, so no equipment box fills up for the commands below.
 const s2=(await settle()).scroll;await key('PageUp');p=await settle();assert.ok(p.scroll<s2,'Page Up scrolls up: '+s2+' -> '+p.scroll);
 const s3=p.scroll;await key('PageDown');p=await settle();assert.ok(p.scroll>s3,'Page Down scrolls down');
 await key('ArrowDown');p=await info();assert.ok(p.selected,'Down selects');await shot('r22-picker-weapon-keys');
 await key('Escape');assert.equal(await isOpen(),true,'Escape drops the selection first');assert.equal((await info()).selected,false);
 await key('Escape');assert.equal(await isOpen(),false,'then closes');
 await key('Backquote');await type('get crew');await key('Enter');await page.waitForTimeout(300);
 await key('ArrowDown');await key('ArrowRight');assert.ok((await info()).selected);
 const crew1=(await probe()).crew.length;await key('Enter');
 assert.equal((await probe()).crew.length,crew1+1,'Enter gets the selected item');assert.equal(await consoleText(),'get crew ','the picker stays after Enter');
 assert.match((await output()).at(-1),/^Granted: /);await shot('r22-picker-crew-keys');
 await key('Escape');await key('Escape');assert.equal(await isOpen(),false);
 // a large window: the same layout, larger
 await page.setViewportSize({width:2560,height:1440});await page.waitForTimeout(500);await key('Backquote');await type('get weapon');await key('Enter');await page.waitForTimeout(300);
 await shot('r22-picker-weapon-large');assert.ok((await info()).rowsInView>=3);
 await key('Escape');await page.setViewportSize({width:1440,height:1000});await page.waitForTimeout(500);
 // every picker and help, one screenshot each, scrolled half a row so the cut shows
 for(const [line,label] of [['get drone','drone'],['get augment','augment'],['get crew','crew'],['get system','system'],['unlock ship','ship'],['unlock achievement','achievement'],['help','help']]){
  await key('Backquote');await type(line);await key('Enter');await page.waitForTimeout(300);await page.mouse.move(...await at(640,40));
  p=await settle();await shot('r22-'+label);
  if(p.max>0){await wheel(640,400,Math.round((p.step?p.step/2:60)*sc));await page.mouse.move(...await at(640,40));p=await settle();await shot('r22-'+label+'-scrolled');}
  console.log(label+': '+p.raw);
  if(label!=='help')assert.ok(p.rowsInView>=2.5,label+': rows in view '+p.rowsInView);
  await key('Escape');if(await isOpen())await key('Escape');
 }
 // help: Down selects the first command, Enter fills it in
 await key('Backquote');await type('help');await key('Enter');await page.waitForTimeout(200);await key('ArrowDown');await key('Enter');
 assert.equal(await consoleText(),'get ','help: Enter fills the selected command in');await key('Escape');if(await isOpen())await key('Escape');
 await key('Backquote');
 pass(mode+': the picker fills the screen with whole rows, cut tiles at the edges, pixel wheel and drag scrolling, the scroll bar and the keys');

 // completion: the suggestions scroll; the new commands glow (screenshots); help is the reference
 await type('s');await shot('suggestions');await wheel(600,600,600);assert.match(await panel(),/CommandCompleter.*scroll=[1-9]/,'the wheel scrolls the suggestions');
 await shot('suggestions-scrolled');await key('Escape');await key('Backquote');
 await type('get');await shot('glow-input');await key('Escape');await key('Backquote');
 await type('help');await key('Enter');assert.match(await panel(),/^ConsoleReference/,'help opens the reference');await shot('help');
 await type('unlock');await shot('help-search');await key('Escape');await key('Backquote');
 await type('help');await key('Enter');await page.waitForTimeout(200);{const [hx,hy]=(await info()).view;await click(hx+60,hy+30);}assert.equal(await consoleText(),'get ','a click on a command fills it in');
 await key('Enter');await shot('get-kinds');assert.match(await panel(),/^KindCompleter/,'get alone lists the kinds');
 await key('Escape');
 pass(mode+': the suggestions scroll, help is the searchable reference and fills commands in');

 // all of it survives Save and close and Continue; the hangar shows the unlocked layout
 const kept=await probe();
 const reloaded=page.waitForEvent('framenavigated',{timeout:60000});
 await page.locator('#save-close').click();await reloaded;
 await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 assert.equal(await page.evaluate(id=>window.consoleLayoutUnlocked(id),lockedShip.id),true,'the unlock is in the profile after a reload');
 await click(1180,295);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:120000});
 await page.waitForTimeout(1500);await closeWindows();
 const back=await probe();
 for(const k of ['weapons','drones','cargo','augments'])assert.deepEqual([...back[k]].sort(),[...kept[k]].sort(),k+' after Continue');
 assert.equal(back.crew.length,kept.crew.length,'crew after Continue');
 await page.locator('#game').focus();
 pass(mode+': what get and unlock gave is there after Save and close and Continue, the hangar shows the unlock');

 // ---- the commands --------------------------------------------------------------------------------------------
 s=await run('dev-rich');let snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],[5000,99,99,99]);
 await run('POOR');snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],[0,0,0,0],'POOR');
 await run('rich');snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],hs?[9999999,800,800,800]:[1999,80,80,80],hs?'HS RICH':'the original RICH (RunCommand: +1999 scrap, +80 of the rest)');
 await run('poor');for(const c of ['SCRAP 100','FUEL 5','MISSILES 7','DRONES 3'])await run(c);
 snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],[100,5,7,3],'the original SCRAP FUEL MISSILES DRONES');
 const hull0=snap.hull;await run('damage 10');await run('HULL 4');assert.equal((await snapshot()).hull,hull0-6,'HULL 4 heals 4');
 await run('fix');
 s=await run('WEAPON LASER_BURST_1');assert.ok([...s.weapons,...s.cargo].includes('LASER_BURST_1'),'WEAPON');
 s=await run('DRONE COMBAT_1');assert.ok([...s.drones,...s.cargo].includes('COMBAT_1'),'DRONE');
 s=await run('AUG ADV_SCANNERS');assert.ok(s.augments.includes('ADV_SCANNERS'),'AUG');
 const crew0=s.crew.length;s=await run('CREW engi');assert.equal(s.crew.length,crew0+1,'CREW');
 const added=s.crew.find(c=>c.startsWith('engi:'));assert.ok(added,'an engi');
 s=await run('SKILL');for(const c of s.crew)assert.ok(c.split(':')[3].split('/').every(v=>Number(v)===1),'SKILL masters '+c);
 s=await run('KILL '+added.split(':')[1]);await page.waitForTimeout(500);s=await probe();
 assert.ok(!s.crew.some(c=>c.split(':')[1]===added.split(':')[1]&&Number(c.split(':')[2])>0),'KILL <name>');
 const sys0=s.systems.length;s=await run('SYS ALL');assert.ok(s.systems.length>=sys0,'SYS ALL');console.log('systems '+sys0+' -> '+s.systems.join(' '));
 s=await run('GOD');assert.equal(s.reactor,s.maxReactor,'GOD: the reactor');
 s=await run('DAMAGESYS shields');const sh=s.systems.find(x=>x.startsWith('shields:')).split(':');assert.equal(sh[2],sh[1],'DAMAGESYS breaks shields');
 await run('fix');
 const fleet0=s.fleet;s=await run('FLEET 3');assert.equal(s.fleet,fleet0+3,'FLEET');
 s=await run('SPEED 2');assert.equal(s.speed,2,'SPEED');s=await run('SPEED 0');assert.equal(s.speed,0);
 await run('SQUISHY console test');await shot('squishy');
 await run('DEBUG');
 s=await run('ACH ACH_FULL_ARSENAL');if(!hs)assert.ok(s.achievements.includes('ACH_FULL_ARSENAL'),'ACH unlocks the achievement');
 s=await run('SHIP ALL');console.log('layouts '+s.layouts+'/'+s.allLayouts);if(!hs)assert.equal(s.layouts,s.allLayouts,'SHIP ALL unlocks every layout');
 pass(mode+': RICH POOR SCRAP FUEL MISSILES DRONES HULL WEAPON DRONE AUG CREW SKILL KILL SYS GOD DAMAGESYS FLEET SPEED SQUISHY DEBUG ACH SHIP ALL');
 s=await run('STORE');assert.equal(s.window,'StoreWindow','STORE opens a store');await shot('store');await closeWindows();
 const ev=s.someEvent;assert.ok(ev,'an event to load');
 s=await run('LOADEVENT '+ev);const loaded=s.window;await shot('loadevent');await closeWindows();
 s=await run('EVENT '+ev);const replaced=s.window;await shot('event');await closeWindows();
 console.log('LOADEVENT/EVENT '+ev+': '+loaded+' / '+replaced);
 assert.equal(loaded,'DialogueWindow','LOADEVENT '+ev);assert.equal(replaced,'DialogueWindow','EVENT '+ev);
 s=await run('EXIT');console.log('EXIT window '+s.window);assert.notEqual(s.window,'game','EXIT opens the map');await closeWindows();
 s=await run('spawn-ship REBEL 1.normal');await page.waitForTimeout(500);s=await probe();assert.ok(s.enemy,'an enemy');
 s=await run('DELETECREW');await page.waitForTimeout(500);s=await probe();assert.equal(s.enemy.crew,0,'DELETECREW');
 s=await run('DELETE');assert.ok(s.enemy===null||s.enemy.dead,'DELETE');
 await page.waitForTimeout(1500);await closeWindows();
 pass(mode+': STORE LOADEVENT EVENT EXIT DELETECREW DELETE');
 // the Hyperspace-only ones
 const hsOnly=['FORCE_STORE','LUA','REMOVESYS','SHIP_CUSTOM','SHIP_CUSTOM_LOCK','VAR','VARIABLE','METAVAR','METAVARIABLE','MVAR','ACH_LOCK','SWITCHALL','SWITCH'];
 for(const n of hsOnly){
  await run(n.toLowerCase()+' CONSOLE_TEST');const last=(await output()).at(-1);
  if(hs)assert.notEqual(last,n+' needs a Hyperspace mod, and this run has none.',n);
  else assert.equal(last,n+' needs a Hyperspace mod, and this run has none.',n);
  if(hs&&n==='SWITCH')break; // SWITCH CONSOLE_TEST: an unknown ship, nothing to switch to
 }
 if(hs){await run('LUA consoleTestValue = 6 * 7');console.log('lua '+(await hsProbe('lua:print(consoleTestValue)')));}
 pass(mode+(hs?': the Hyperspace-only commands run':': the Hyperspace-only commands say they need a mod'));
 const failedLines=(await output()).filter(l=>/^Command failed|^Unknown command/.test(l));
 assert.deepEqual(failedLines,[],'no command failed');
 // ---- full equipment (round 2): with no room a get goes where an event's reward goes, and while that box waits
 // another of its kind is refused (the original's one at a time); Multiverse's multipleOverCapacity keeps every one.
 // A new run, so no box is taken yet (the commands above filled the cargo).
 await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:120000});
 await page.waitForTimeout(1500);await closeWindows();await page.locator('#game').focus();
 const multiple=(await probe()).multipleOver;console.log('multipleOverCapacity '+multiple);
 const last=async()=>(await output()).at(-1);
 const fill=async(kind,pick,until)=>{for(let i=0;i<40;i++){const k=pick(i);await run('get '+kind+' '+k);const l=await last();if(until.test(l))return l;if(/not added/.test(l)&&!/stack/.test(l))return l;}throw Error(kind+': never full');};
 const plain=list=>list.filter(i=>!i.tags.includes('no art'));
 const weaponPool=plain(lists.weapon).filter(i=>!i.tags.includes('event only'));
 let l=await fill('weapon',i=>weaponPool[i%weaponPool.length].key,/over-capacity box/);
 console.log('weapons full: '+l);assert.match(l,/^Granted: .* - cargo full: it waits in the equipment screen's over-capacity box/,'the next weapon waits in the over-capacity box');
 // the equipment screen opens on the box (Equipment::AddWeapon calls Equipment::Open); a mod's event box that came up
 // first goes before it, and closing it opens the screen (the event window's own path)
 const equipment=async what=>{for(let i=0;i<6;i++){const p=await probe();if(p.window==='ShipWindow')return p;
   if(p.window==='DialogueWindow'){console.log(what+': an event box first: '+JSON.stringify(p.choices));await key('Escape');await key('1');await page.waitForTimeout(400);if(!(await isOpen()))await key('Backquote');continue;}
   await page.waitForTimeout(300);}assert.equal((await probe()).window,'ShipWindow',what+': the equipment screen opens on it');};
 s=await equipment('weapon');await shot('r2-full-weapon');
 await key('Escape');await shot('r2-full-weapon-equipment');await key('Backquote');
 await run('get weapon '+weaponPool[0].key);l=await last();console.log('one more weapon: '+l);
 if(multiple)assert.match(l,/^Granted: .*over-capacity box \(2 waiting\)/,'multipleOverCapacity keeps a second one');
 else assert.match(l,/^Cargo full: sell or discard the pending .* first \(equipment screen\); .* not added$/,'a second one is refused while the first waits');
 await run('get drone '+keyOf('drone','COMBAT_1'));l=await last();console.log('a drone too: '+l);
 if(!multiple)assert.match(l,/^Cargo full: sell or discard the pending .*; Combat Drone Mark I not added$/,'drones share the box');
 // augments: different ones (a second copy of one that does not stack is the game's AUGMENT_FULL scrap)
 const augPool=plain(lists.augment).filter(i=>!i.tags.includes('hidden')&&!i.tags.includes('event only')&&!s.augments.includes(i.id));
 l=await fill('augment',i=>augPool[i].key,/over-capacity box/);console.log('augments full: '+l);
 assert.match(l,/^Granted: .* - augments full: it waits in the equipment screen's over-capacity box/,'a fourth augment waits in the augment box');
 s=await equipment('augment');await shot('r2-full-augment');await key('Escape');await shot('r2-full-augment-equipment');await key('Backquote');
 await run('get augment '+augPool.at(-1).key);l=await last();console.log('one more augment: '+l);
 if(multiple)assert.match(l,/^Granted: .*waiting\)/);else assert.match(l,/^Augments full: sell or discard the pending .* first \(equipment screen\); .* not added$/,'a second augment is refused');
 // crew: past the limit the crew screen asks for one to go; another is refused until then
 const race=keyOf('crew',hs?'human':'human')||lists.crew[0].key;
 l=await fill('crew',()=>race,/crew full/);console.log('crew full: '+l);assert.match(l,/^Granted: .* - crew full: dismiss one in the crew screen/);
 await page.waitForTimeout(300);s=await probe();console.log('window '+s.window);await shot('r2-full-crew');
 await run('get crew '+race);l=await last();console.log('one more crew: '+l);assert.match(l,/^Crew full: dismiss the extra crew member first \(crew screen\); .* not added$/,'a second extra crew member is refused');
 const extra=s.crew.at(-1).split(':')[1];await run('KILL '+extra);await page.waitForTimeout(1500);
 pass(mode+': full equipment: the over-capacity box and the crew screen take one, '+(multiple?'multipleOverCapacity keeps more':'a second is refused while one waits'));
 s=await run('CREDITS');await page.waitForTimeout(1000);console.log('CREDITS window '+(await snapshot()).window);await shot('credits');
 assert.match((await snapshot()).window,/GameOver/,'CREDITS: the victory screen');
 pass(mode+': CREDITS');
 // the hangar shows the layout unlocked by name earlier: the next launch, New Game (the won run asks first), the list
 const reload2=page.waitForEvent('framenavigated',{timeout:60000});await page.reload();await reload2;
 await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 await click(1180,353);await page.waitForTimeout(1500);
 if((await snapshot()).state==='MainMenuState'){await shot('new-game-warning');await click(592,414);}
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='SelectShipState',{},{timeout:60000});
 assert.equal(await page.evaluate(id=>window.consoleLayoutUnlocked(id),lockedShip.id),true,'the hangar has the unlock');
 await page.waitForTimeout(800);await click(100,158);await page.waitForTimeout(500);await key('Digit1');await key('Digit1');// its tutorial's two pages
 if(!hs&&lockedShip.layout>0)await click(720+85*lockedShip.layout,135);
 await page.waitForTimeout(300);await shot('hangar-list-after-unlock');
 pass(mode+': the hangar list shows '+lockedShip.key+' unlocked');
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS console '+mode);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,30),null,1));try{console.log('probe',await page.evaluate(()=>window.consoleProbe()));console.log('output',(await page.evaluate(()=>window.ftlEngine.consoleOutput())).split('\n').slice(-30).join('\n'));await page.locator('#game').screenshot({path:join(out,'console-'+mode+'-failure.png')});}catch{}}
await browser.close();server.kill('SIGTERM');process.exit(failed?1:0);
