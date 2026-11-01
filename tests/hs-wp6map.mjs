// WP6 map check (Hyperspace): the oracle's sessions/wp6-map.json replayed through the real UI (hangar seed box typed,
// start chain answered with its keys, the beacon map opened and clicked) with the oracle's own Lua driver
// (tools/hs-oracle/tracer/data/hs_oracle/wp6-map.lua, mounted at /app/tests/wp6-map.lua) read through hsWp6MapProbe.
// HS6_PART=main (Lua members, all connections, ForceWaitMessage), env (space status, sessions/wp6-map2.json) or inf
// (infinite + splitAllNodeView).
// Output: /out/<part>.jsonl (the driver's lines) and /out/<part>-*.png. Test-only.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8085,origin='http://localhost:'+port,part=process.env.HS6_PART||'main',out=process.env.HS6_OUT||'/out';
const data=join(process.cwd(),'.build/fixture-hs-wp6map');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const driver=await readFile('/app/tests/wp6-map.lua','utf8'),driver2=await readFile('/app/tests/wp6-left.lua','utf8');
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1280,height:720}}),page=await context.newPage();
const errors=[],logs=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{const t=e.text();if(e.type()==='error'){if(!expectedConsoleError(e))errors.push('CONSOLE '+t+' @'+JSON.stringify(e.location()));}else if(/Hyperspace|unsupported/i.test(t))logs.push(t);});
let failed=false;const rows=[];
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-core-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu');
 const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const move=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await flush();await page.waitForTimeout(150);};
 const key=async k=>{await page.locator('#game').press(k);await flush();};
 const wait=ms=>page.waitForTimeout(ms);
 const shot=async name=>{await page.locator('#game').screenshot({path:join(out,part+'-'+name+'.png')});};
 const probe=c=>page.evaluate(c=>{try{return window.hsWp6MapProbe(c);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,2000);}},c);
 const ok=async c=>{const r=await probe(c);assert.ok(!r.startsWith('FAILED'),c.slice(0,80)+': '+r);return r;};
 const state=async()=>JSON.parse(await ok('state'));
 const lua=async c=>{const r=await ok('lua:'+c);const [head,...lines]=r.split('\n');assert.equal(head,'PASS',c+': '+head);rows.push(...lines);return lines;};
 // oracle ["pick", EVENT, key]: wait until EVENT's box is the open one, then press the key.
 const pick=async(ev,k,s=30)=>{const end=Date.now()+s*1000;let b;while((b=await probe('box'))!==ev){if(Date.now()>end)throw Error('pick '+ev+': open box '+b);await wait(200);}
  await wait(400);await key(String(k));await wait(300);};
 // oracle ["answer", 3]: the autoplay policy until no box is open for 3 s.
 const openMap=async()=>{await click(572,48);await wait(1500);await move(640,700);await wait(700);};
 const openMap0=openMap;
 const chooseAll=async()=>{let quiet=Date.now();for(let i=0;i<60;i++){await wait(400);const r=await ok('answer');if(r){console.log('answer '+r);quiet=Date.now();await wait(800);}else if(Date.now()-quiet>3000)break;}};
 await wait(2000);
 if(part!=='rock'&&part!=='rock2')console.log(await ok('driver:'+driver));
 if(part==='inf')console.log(await ok('options:infinite,splitAllNodeView'));
 if(part==='left')console.log(await ok('driver:'+driver2));
 const newGame=async seed=>{
 // Main menu, NEW GAME (the port's menu layout), the hangar.
 if((await snapshot()).window==='WelcomeChoiceBox'){await key('1');}
 await click(1180,353);
 for(let i=0;i<20&&(await snapshot()).state!=='SelectShipState';i++){
  const s=await snapshot();if(s.window==='ConfirmationWindow'){const y=s.confirmation.yes;await click(y.x+y.width/2,y.y+12);}
  await wait(250);}
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='SelectShipState');
 await wait(1500);await move(640,700);await wait(500);
 // K8: the seed typed into the hangar's seed box, as the oracle does.
 await click(1150,81);await wait(300);await page.keyboard.type(String(seed),{delay:60});await wait(300);
 await click(640,450);await wait(300);await move(1150,40);await wait(500);await shot('00-hangar-seed');
 console.log('hangar ship '+(await snapshot()).ship);
 await click(1160,40);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:60000});
 };
 if(part==='rock'||part==='rock2'){
  // X29 on Hyperspace's own data (F2: vanilla sectors): the first seed whose tree has ROCK_HOME, a crystal crew member
  // named Ruwen (ShipManager::AddCrewMember sets the "ruwen" flag), then the sectors on the way there (console EXIT and
  // the next sector's key, as sessions/wp6-map-rock.json does), the Rock homeworld's map with its quest label.
  let seed=0,path=null;
  for(const s of (process.env.HS6_SEEDS||'12345,101,202,303,404,505,606,707,808,909').split(',')){
   await newGame(s);await wait(1500);
   const tree=(await ok('tree')).split('\n').map(l=>{const [c,i,t,n]=l.split(' ');return {c:+c,i:+i,t,n:n?n.split(',').map(Number):[]};});
   const rock=tree.find(x=>x.t==='ROCK_HOME');console.log('seed '+s+' rock '+JSON.stringify(rock));
   if(rock){const find=(node,keys)=>{if(node===rock)return keys;for(const [k,i] of node.n.entries()){const nx=tree.find(x=>x.c===node.c+1&&x.i===i);const r=nx&&find(nx,[...keys,k+1]);if(r)return r;}return null;};
    path=find(tree.find(x=>x.c===0),[]);if(path){seed=s;break;}}
   await ok('menu');await wait(1500);}
  assert.ok(path,'no seed with ROCK_HOME');console.log('ROCKPATH '+seed+' '+path.join(','));
  await chooseAll();
  // Ruwen as vanilla gives her: ZOLTAN_CREW_STUDY's STASIS_POD choice, then <crewMember class="crystal" id="name_Ruwen"/>
  // (ShipManager::AddCrewMemberFromBlueprint 0x49c4f0 sets the "ruwen" flag from the blueprint's name).
  if(part==='rock2'){console.log(await ok('crystal:Ruwen'));await wait(1000);}else{console.log('aug '+await ok('aug:STASIS_POD'));await wait(300);
  console.log('load '+await ok('load:ZOLTAN_CREW_STUDY'));await wait(1500);
  {const cs=(await ok('choices')).split('\n');console.log('CHOICES '+JSON.stringify(cs));const k=cs.findIndex(c=>/stasis/i.test(c))+1;
   console.log('STASISKEY '+k);assert.ok(k>0,'no stasis choice');await ok('pick:'+k);}}
  await wait(1000);await chooseAll();console.log('crew '+JSON.stringify(await state()));
  for(const [n,k] of path.entries()){
   const before=await state();
   console.log('sector '+(n+1)+' '+await ok('sector:'+k));
   const end=Date.now()+60000;let s;
   while(true){s=await state();if(s.sector!==before.sector&&!s.jumping)break;if(Date.now()>end)throw Error('no arrival '+JSON.stringify(s));await wait(300);}
   await wait(2000);await chooseAll();await wait(1000);
   console.log('arrived '+JSON.stringify(await state()));
  }
  console.log('QUESTS '+await ok('quests'));
  {const end=Date.now()+150000;while(!(await state()).ftl){if(Date.now()>end)throw Error('FTL not ready');await chooseAll();await wait(1000);}}
  await openMap0();await shot('r-map');
  await writeFile(join(out,part+'.jsonl'),rows.join('\n')+'\n');console.log('PASS hs-wp6map rock rows='+rows.length);
 }else{
 await newGame(12345);
 await pick('START_BEACON_REAL',1,60);await pick('START_BEACON_PRESELECT',1);await pick('LIGHTSPEED_DROPPOINT',1);
 await pick('START_BEACON_PREP',1);await pick('START_BEACON_PREP_OPTIONS',1);await pick('START_BEACON_PREP_OPTIONS 21',1);
 await pick('START_BEACON_PREP_OPTIONS 21 22',1);await pick('START_BEACON_EXPLAIN',2);await wait(3000);
 const startState=await state();const startAt=startState.at;
 console.log('state '+JSON.stringify(startState));
 if(part==='main'){
  await lua("WP6M.dump('start')");await lua("WP6M.write('start')");await lua("WP6M.dump('after-write')");
  await lua("WP6M.knownFar('k1')");await lua("WP6M.dump('known')");
  // Save/resume of the map state (Location::known, the picked sector names, the tree): the same dump after a reload.
  await ok('savereload');await wait(1500);
  {const [l]=await lua("WP6M.dump('known')");rows.pop();const a=JSON.parse(rows[rows.length-1]),b=JSON.parse(l);
   for(const k of ['locs','sectors','cs','wl','dz','pd','rev'])assert.deepEqual(b[k],a[k],'after reload: '+k);console.log('PASS reload known');}
  await wait(1000);
  await openMap();await shot('01-map');await move(577,385);await wait(1500);await shot('02-map-hover');await key('Escape');await wait(700);
  await lua('WP6M.wait(true)');
  await openMap();await shot('03-map-forcewait');await click(577,385);await wait(2500);await shot('04-forcewait-click');
  console.log('forcewait state '+JSON.stringify(await state()));
  await key('Escape');await wait(700);
  await lua("WP6M.dump('forcewait')");await lua('WP6M.wait(false)');
  await openMap();await shot('05-map-unwait');await key('Escape');await wait(700);
  // (the oracle's WP6M_* tracer events are not in the port's data: space status is part env)
  await openMap();await move(577,385);await wait(1500);await lua("WP6M.dump('hover-open')");await shot('10-map-open-dump');
 }else if(part==='left'){
  // sessions/wp6-left.json: WorldManager:AddBoarders / ClearLocation, killedCrew over a jump (WP6 leftovers driver).
  const st=async(c,t)=>{await lua(c);await wait(300);};
  await lua("WP6L.state('s0')");
  await lua("WP6L.board('b1','mantis',3,false)");await wait(2000);await lua("WP6L.state('s1')");
  await lua("WP6L.board('b2','rock',2,true)");await wait(2000);await lua("WP6L.state('s2')");
  await lua("WP6L.board('b3','human',9,false)");await wait(2000);await lua("WP6L.state('s3')");
  await lua("WP6L.board('b4','human',4,false)");await wait(2000);await lua("WP6L.state('s4')");
  await lua("WP6L.board('b5','human',1,false)");await wait(1000);await lua("WP6L.state('s5')");
  await lua("WP6L.killall('k1')");await wait(6000);await lua("WP6L.state('s6')");
  {const end=Date.now()+150000;while(!(await state()).ftl){if(Date.now()>end)throw Error('FTL not ready');await wait(1000);}}
  await click(572,48);await wait(2000);await click(577,385);
  {const end=Date.now()+60000;let s;while(true){s=await state();if(!s.jumping&&s.at!==startAt)break;if(Date.now()>end)throw Error('no arrival '+JSON.stringify(s));await wait(300);}}
  await wait(2000);await chooseAll();await wait(2000);
  await lua("WP6L.state('s7')");
  await lua("WP6L.clear('c1')");await wait(2000);await lua("WP6L.state('s8')");
 }else if(part==='env'){
  // sessions/wp6-map2.json wp6-map-env: Multiverse's own hazard events through CustomEventsParser:LoadEvent.
  await lua("WP6M.load('OSMIAN_ARRIVAL')");await wait(1500);await shot('e00-box');await key('1');await wait(1500);
  await move(640,700);await wait(700);await shot('e01-pds-allies');await move(690,100);await wait(2000);await shot('e02-tip');await move(640,700);
  await lua("WP6M.dump('e-pds')");
  await lua("WP6M.load('COMBAT_CHECK_HAZARD_ASTEROID')");await wait(1500);await key('1');await wait(1500);await move(640,700);await wait(700);await shot('e03-stack');
  await lua("WP6M.dump('e-ast')");
  await click(572,48);await wait(1500);await click(577,385);
  {const end=Date.now()+60000;let s;while(true){s=await state();if(!s.jumping&&s.at!==startAt)break;if(Date.now()>end)throw Error('no arrival '+JSON.stringify(s));await wait(300);}}
  await wait(1500);await pick('DROPPOINT_CRAZYZOLTAN',3);await pick('DROPPOINT_CRAZYZOLTAN 225',2);await wait(3000);
  await lua("WP6M.dump('e-b2')");
  await lua("WP6M.load('COMBAT_CHECK_HAZARD_IONSTORM')");await wait(1500);await key('1');await wait(1500);await move(640,700);await wait(700);await shot('e04-storm');
  await move(618,100);await wait(2000);await shot('e05-storm-tip');await move(640,700);
  await lua("WP6M.dump('e-storm')");
 }else{
  await lua("WP6M.dump('s0')");
  await openMap();await shot('i00-map-split');await move(360,566);await wait(1500);await shot('i01-grid-hover');
  await click(360,566);await move(640,700);await wait(700);await shot('i02-grid-off');
  await click(360,566);await move(640,700);await wait(700);await shot('i03-grid-on');await key('Escape');await wait(700);
  for(let n=1;n<=10;n++){
   const before=await state();
   // The oracle's console EXIT: StarMap bOpen + bChoosingNewSector (the sector map), then its key 1.
   await lua('Hyperspace.App.world.starMap.bChoosingNewSector = true');await wait(1000);
   if([1,3,4,8,9,10].includes(n)){await move(640,700);await wait(700);await shot('i'+String(n+10).padStart(2,'0')+'-sectors-'+n);}
   console.log('sector '+n+' '+await ok('sector'));
   const end=Date.now()+60000;let s;
   while(true){s=await state();if(s.sector!==before.sector&&!s.jumping)break;if(Date.now()>end)throw Error('no arrival in sector '+n+' '+JSON.stringify(s));await wait(300);}
   await wait(2000);await chooseAll();await wait(1000);
   await lua(`WP6M.dump('s${n}')`);console.log('arrived '+JSON.stringify(await state()));
   if(n===10){await ok('savereload');await wait(1500);const [l]=await lua(`WP6M.dump('s${n}')`);rows.pop();const a=JSON.parse(rows[rows.length-1]),b=JSON.parse(l);
    for(const k of ['locs','sectors','cs','wl'])assert.deepEqual(b[k],a[k],'after reload s'+n+': '+k);console.log('PASS reload s'+n);}
  }
  await lua('Hyperspace.App.world.starMap.bChoosingNewSector = true');await wait(1000);await move(640,700);await wait(700);await shot('i30-sectors-end');
 }
 await writeFile(join(out,part+'.jsonl'),rows.join('\n')+'\n');
 }
 const csp=errors.filter(e=>e.includes('Content Security Policy'));
 assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
 console.log('PASS hs-wp6map '+part+' rows='+rows.length);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join(' | '));
 await writeFile(join(out,part+'.jsonl'),rows.join('\n')+'\n').catch(()=>{});
 await page.screenshot({path:join(out,part+'-failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
