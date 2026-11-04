// The one debug console (docs/CONSOLE.md) through the website, in vanilla or in a Hyperspace session (the loader's
// preload sets window.ftlModSession from FTL_HS_MOD_SESSION, as for hs-core.mjs): the gating rule with the Logs page's
// "Debug console" setting off (vanilla: closed on ` and \; Hyperspace: \ opens it while the mod has
// <console enabled="true"/>, ` stays HS's speed key, <console enabled="false"/> keeps it closed), then the setting
// turned on in the Logs page (Console button, ` and \ in every mode), typing with the cursor keys, the history on Up,
// help, and the Hyperspace and original commands run with their effect read back (BrowserEngine.consoleProbe); the
// Hyperspace-only ones answer that they need a mod in vanilla. Test-only. Output: /out/console-*.png, CONSOLE_OUT.
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] node /app/tests/console.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile} from 'node:fs/promises';
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

 // ---- the commands --------------------------------------------------------------------------------------------
 let s=await run('dev-rich');let snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],[5000,99,99,99]);
 await run('POOR');snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],[0,0,0,0],'POOR');
 await run('rich');snap=await snapshot();assert.deepEqual([snap.scrap,snap.fuel,snap.missiles,snap.drones],[9999999,800,800,800],'HS RICH');
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
 s=await run('CREDITS');await page.waitForTimeout(1000);console.log('CREDITS window '+(await snapshot()).window);await shot('credits');
 assert.match((await snapshot()).window,/GameOver/,'CREDITS: the victory screen');
 pass(mode+': CREDITS');
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS console '+mode);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,30),null,1));try{console.log('probe',await page.evaluate(()=>window.consoleProbe()));console.log('output',(await page.evaluate(()=>window.ftlEngine.consoleOutput())).split('\n').slice(-30).join('\n'));await page.locator('#game').screenshot({path:join(out,'console-'+mode+'-failure.png')});}catch{}}
await browser.close();server.kill('SIGTERM');process.exit(failed?1:0);
