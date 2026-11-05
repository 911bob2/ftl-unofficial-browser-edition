// The over-capacity boxes (docs/TESTS.md, round 21): a fourth augment, a weapon past full cargo, both at once, and a
// crew member past the limit, reached through the debug console's get (docs/CONSOLE.md), in vanilla or in a Hyperspace
// session (the loader's preload sets window.ftlModSession from FTL_HS_MOD_SESSION). For each state the engine's
// layout probe (BrowserEngine.overcapProbe) gives the boxes of the open ship window in the mouse layout and in the
// touch layout: on the mouse layout the boxes sit where the original's do (Equipment::SetPosition and CrewManifest's
// DropBox at (-275, 100); Hyperspace's extra crew box below the crew DropBox), in the touch layout no two of them
// overlap, every crew member has a box on the screen with its dismiss button, and the extra one is dismissed with it.
// Test-only. Output: /out/overcap-<mode>-*.png, OVERCAP_OUT.
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] node /app/tests/overcap.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8087,origin='http://localhost:'+port,out=process.env.OVERCAP_OUT||'/out';
const data=join(process.cwd(),'.build/fixture-overcap');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=process.env.FTL_HS_MOD_SESSION?JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8')):null;
const hs=!!session?.hyperspace;
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e)&&!/Content Security Policy directive 'style-src/.test(e.text()))errors.push('CONSOLE '+e.text());});
let failed=false,mode=hs?'hs':'vanilla';
const pass=t=>console.log('PASS '+t);
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='overcap-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const probe=async()=>JSON.parse(await page.evaluate(()=>window.consoleProbe()));
 const hasLayout=await page.evaluate(()=>typeof window.overcapProbe==='function');
 const layout=async touch=>JSON.parse(await page.evaluate(t=>window.overcapProbe(t,''),touch));
 const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const key=async k=>{await page.locator('#game').press(k);await flush();await flush();};
 const output=async()=>(await page.evaluate(()=>window.ftlEngine.consoleOutput())).split('\n');
 const run=async c=>{await page.evaluate(c=>window.ftlEngine.consoleExecute(c),c);await flush();await flush();};
 const last=async()=>(await output()).at(-1);
 const shot=async n=>page.locator('#game').screenshot({path:join(out,'overcap-'+mode+'-'+n+'.png')});
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();await page.mouse.down();await flush();await page.mouse.up();await flush();await page.waitForTimeout(300);};
 const closeWindows=async()=>{for(let i=0;i<40;i++){const s=await snapshot(),w=s.window;if(w==='game')return;
   if(w!=='DialogueWindow')await key('Escape');else if(s.dialogue?.ready){const p=await probe(),c=p.choices.findIndex(t=>/proceed|enough|continue/i.test(t));await key(String(c>=0?c+1:1+i%Math.max(1,p.choices.length)));}
   await page.waitForTimeout(400);}throw Error('a window stays open: '+(await snapshot()).window);};
 // the equipment or crew screen opened by a get; a mod's event box that came up first is answered
 const shipWindow=async what=>{for(let i=0;i<8;i++){const p=await probe();if(p.window==='ShipWindow')return;
   if(p.window==='DialogueWindow'){await key('Escape');await key('1');await page.waitForTimeout(400);continue;}
   await page.waitForTimeout(300);}assert.equal((await probe()).window,'ShipWindow',what+': the ship window opens on it');};
 const fill=async(kind,pick,until)=>{for(let i=0;i<40;i++){await run('get '+kind+' '+pick(i));const l=await last();if(until.test(l))return l;if(/not added/.test(l)&&!/stack/.test(l))return l;}throw Error(kind+': never full');};
 const items=async kind=>JSON.parse(await page.evaluate(k=>window.consoleItems('get',k),kind)).items.filter(i=>!i.tags.includes('no art'));

 await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:120000});
 await page.waitForTimeout(1500);await closeWindows();await page.locator('#game').focus();
 const multiple=(await probe()).multipleOver;if(multiple)mode='mv';
 console.log(mode+': multipleOverCapacity '+multiple+', layout probe '+hasLayout);
 const rectsOverlap=(a,b)=>a.x<b.x+b.w&&b.x<a.x+a.w&&a.y<b.y+b.h&&b.y<a.y+a.h;
 const inside=(r,s)=>r.x>=s.x&&r.y>=s.y&&r.x+r.w<=s.x+s.w&&r.y+r.h<=s.y+s.h;
 // The probe's places are relative to the window's origin, which sits 7 px right of the original's Equipment and
 // CrewManifest origin (ShipWindow.kt): the original's DropBox at (-275, 100) is the art at (-282, 100) here.
 // The over-capacity boxes of the equipment tab: on the mouse layout at the original's place (both at once there, as
 // the original draws them), on touch one at a time (the other a page away), on the screen and clear of the window.
 const checkEquipment=async(what,kinds,waiting)=>{
  if(!hasLayout)return;
  const m=await layout(false),t=await layout(true);
  console.log(what+' mouse '+JSON.stringify(m.over)+' touch '+JSON.stringify(t.over)+' pages '+t.overPages+' arrows '+JSON.stringify(t.overArrows));
  assert.deepEqual(m.over.map(b=>b.kind),kinds,what+": the mouse layout shows the original's boxes");
  for(const b of m.over){assert.equal(b.x,-282,what+': '+b.kind+' box x as Equipment::SetPosition');assert.equal(b.y,100,what+': '+b.kind+' box y');}
  assert.equal(t.over.length,Math.min(1,kinds.length),what+': touch shows one box at a time');
  if(!multiple)assert.equal(t.overPages,kinds.length,what+': touch pages through every box in use');
  // the touch pager (both boxes in use), or under multipleOverCapacity Hyperspace's own pager of the waiting items
  assert.equal(t.overArrows.length,waiting>1?2:0,what+": the pager's arrows");
  for(const b of t.over){assert.ok(inside(b,t.screen),what+': the touch box is on the screen '+JSON.stringify(b));
   if(b.slot)assert.ok(inside(b.slot,b),what+': its item is in it');
   for(const o of [...t.others,...t.overArrows.map(rect=>({name:'arrow',rect}))])assert.ok(!rectsOverlap(b,o.rect),what+': the touch box is clear of the '+o.name+' '+JSON.stringify(o.rect));}
 };

 // ---- a fourth augment: the augment box
 const augPool=(await items('augment')).filter(i=>!i.tags.includes('hidden')&&!i.tags.includes('event only'));
 let l=await fill('augment',i=>augPool[i].key,/over-capacity box/);console.log('augments full: '+l);
 assert.match(l,/over-capacity box/,'a fourth augment waits in the augment box');
 await shipWindow('augment');await page.waitForTimeout(500);await shot('1-augment');
 await checkEquipment('augment',['augment'],1);
 pass(mode+': the augment over-capacity box');
 // ---- a weapon past full cargo: the item box too
 await key('Escape');
 const weaponPool=(await items('weapon')).filter(i=>!i.tags.includes('event only'));
 l=await fill('weapon',i=>weaponPool[i%weaponPool.length].key,/over-capacity box/);console.log('weapons full: '+l);
 assert.match(l,/over-capacity box/,'the next weapon waits in the item box');
 await shipWindow('weapon');await page.waitForTimeout(500);await shot('2-both');
 // Multiverse's multipleOverCapacity shows one item at a time on every layout (CustomEquipment::AddOverCapacityItem)
 await checkEquipment('both',multiple?['augment']:['item','augment'],2);
 if(hasLayout&&!multiple){
  // touch starts on the box that took an item last (the weapon's), the pager's other page is the augment box
  const before=(await layout(true)).over.map(b=>b.kind);
  assert.deepEqual(before,['item'],'touch starts on the box that took an item last');
  await page.evaluate(()=>window.overcapProbe(true,'page'));const t=await layout(true);
  assert.deepEqual(t.over.map(b=>b.kind),['augment'],'the touch pager turns to the augment box');
  await page.evaluate(()=>window.overcapProbe(true,'page'));
 }
 pass(mode+': the item and augment over-capacity boxes');
 // ---- a crew member past the limit: the crew screen
 await key('Escape');
 const race=(await items('crew')).find(i=>i.id==='human')?.key||'Human';
 l=await fill('crew',()=>race,/crew full/);console.log('crew full: '+l);assert.match(l,/crew full/);
 await shipWindow('crew');await page.waitForTimeout(500);await shot('3-crew');
 if(hasLayout){
  const crew=(await probe()).crew.length;
  for(const touch of [false,true]){
   const c=await layout(touch),name=touch?'touch':'mouse';
   console.log('crew '+name+' '+JSON.stringify({drop:c.drop,boxes:c.crew,screen:c.screen}));
   assert.ok(c.drop,name+': the over-capacity box shows');
   assert.equal(c.crew.filter(b=>b.crew).length,crew,name+': a box for every crew member ('+crew+')');
   for(const b of c.crew){
    assert.ok(inside(b,c.screen),name+': crew box '+b.id+' on the screen');
    for(const o of [{name:'over-capacity box',rect:c.drop},...(b.over?c.others:[c.others[1]])])
     assert.ok(!rectsOverlap(b,o.rect),name+': crew box '+b.id+' clear of the '+o.name+' '+JSON.stringify(b)+' '+JSON.stringify(o.rect));
    if(b.crew)assert.ok(b.dismiss&&inside(b.dismiss,c.screen)&&!rectsOverlap(b.dismiss,c.drop),name+': '+b.crew+' has its dismiss button');}
   assert.ok(inside(c.drop,c.screen),name+': the over-capacity box on the screen');
   for(const o of c.others)assert.ok(!rectsOverlap(c.drop,o.rect),name+': the over-capacity box clear of the '+o.name);
   if(hs)assert.ok(c.crew.find(b=>b.over&&b.crew),name+": the extra crew member's own box");
   if(!touch){
    assert.deepEqual([c.drop.x,c.drop.y],[-275,107],"mouse: the crew DropBox as CrewManifest's (-275, 100), the widget 7 px in");
    if(hs){const extra=c.crew.find(b=>b.over);
     assert.equal(extra.x+c.origin.x,146,'Hyperspace: the extra crew box at x 146 of the screen (CustomCrewManifest::OnInit)');
     assert.equal(extra.y,100+170,'Hyperspace: the extra crew box right below the DropBox (y 100 + DropBox::GetHeight 170)');}
   }
  }
  // the extra crew member's dismiss button, then the confirmation's Yes, on the mouse layout
  const c=await layout(false),extra=c.crew.filter(b=>b.crew).at(-1),o=c.origin;
  await click(o.x+extra.dismiss.x+extra.dismiss.w/2,o.y+extra.dismiss.y+extra.dismiss.h/2);await page.waitForTimeout(300);await shot('4-dismiss');
  // the touch layout's confirmation stays on the screen too (above a box under the window)
  const tc=await layout(true);assert.ok(tc.confirm&&inside(tc.confirm,tc.screen),'touch: the dismiss confirmation on the screen '+JSON.stringify(tc.confirm));
  const yes=(await layout(false)).confirmYes;assert.ok(yes,'the dismiss confirmation opens');
  await click(o.x+yes.x+yes.w/2,o.y+yes.y+yes.h/2);await page.waitForTimeout(800);await shot('5-dismissed');
  assert.equal((await probe()).crew.length,crew-1,'the extra crew member is dismissed');
  pass(mode+': the extra crew member is visible and dismissed with its button, on both layouts');
 }
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS overcap '+mode);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,30),null,1));try{console.log('output',(await page.evaluate(()=>window.ftlEngine.consoleOutput())).split('\n').slice(-12).join('\n'));await page.locator('#game').screenshot({path:join(out,'overcap-'+mode+'-failure.png')});}catch{}}
await browser.close();server.kill('SIGTERM');process.exit(failed?1:0);
