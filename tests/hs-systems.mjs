// Hyperspace systems check (systems workstream): boot the real launch path with the Multiverse session, then run
// hsSystemsProbe's parts: units (customSystems tables, temporal manipulator incl. save/load, clonebay jump heal,
// PowerManager statuses and Lua iTempPowerCap, Lua ShipSystem members, additionalPowerLoss, SET_BONUS_POWER,
// SetHackingLevel). Test-only; needs window.ftlModSession from the loader's preload and the modded /assets.
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> [HSS_PARTS=units] node /app/tests/hs-systems.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,parts=(process.env.HSS_PARTS||'units').split(',');
const data=join(process.cwd(),'.build/fixture-hs-systems');await mkdir(data,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=process.env.FTL_HS_MOD_SESSION?JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8')):null;
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1280,height:720}}),page=await context.newPage();
const errors=[],logs=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{const t=e.text();if(e.type()==='error'){if(!expectedConsoleError(e)&&!/Content Security Policy directive 'style-src/.test(t))errors.push('CONSOLE '+t);}else if(/Hyperspace|unsupported/i.test(t))logs.push(t);});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-core-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 const t0=Date.now();
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu after '+(Date.now()-t0)+' ms');
 assert.ok(session&&session.hyperspace,'needs a Hyperspace mod session');
 await page.waitForTimeout(3000);
 if(parts.includes('ships')){
  // The systems of every ship layout the oracle dumped (PLAYER_, CREW_, ELITE_, VANILLA_SHIP_) at the start of a run (test-only mount /oracle-ships), written
  // to /out/ships.jsonl for .build/hssystems/compare-ships.py.
  const {readdir}=await import('node:fs/promises');
  const names=(await readdir('/oracle-ships')).filter(f=>/^[A-Z]+_SHIP_.*\.json$/.test(f)).map(f=>f.slice(0,-5)).sort();
  let lines='';const t=Date.now();
  for(let i=0;i<names.length;i+=20){
   lines+=await page.evaluate(p=>window.hsSystemsProbe(p),'ships:'+names.slice(i,i+20).join(','));
   await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');}
  await writeFile('/out/ships.jsonl',lines);
  console.log('PASS ships dumped '+names.length+' ('+(Date.now()-t)+' ms)');
 }
 for(const part of parts.filter(p=>p.startsWith('shot:'))){
  // Screenshots for the oracle session systems-render (systems bar of the same ship); written to /out/<name>.png.
  const probe=await page.evaluate(p=>window.hsSystemsProbe(p),part);console.log(probe);assert.ok(probe.startsWith('PASS'),probe);
  // Size the page so the game canvas is drawn at exactly 1280x720, then clip it.
  for(let i=0;i<3;i++){const b=await page.locator('#game').boundingBox(),v=page.viewportSize();
   if(Math.abs(b.width-1280)<1&&Math.abs(b.height-720)<1)break;
   await page.setViewportSize({width:Math.round(v.width+1280-b.width),height:Math.round(v.height+720-b.height)});await page.waitForTimeout(500);}
  const box=await page.locator('#game').boundingBox();
  await page.mouse.move(box.x+640,box.y+300);await page.waitForTimeout(2500);
  await page.screenshot({path:'/out/'+part.replace(/[^A-Za-z0-9_]+/g,'-')+'.png',clip:{x:box.x,y:box.y,width:1280,height:720}});
  await page.evaluate(()=>window.hsSystemsProbe('menu'));
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');}
 if(parts.includes('tai')){const r=await page.evaluate(()=>window.hsSystemsProbe('tai'));console.log(r);await writeFile('/out/tai.txt',r);
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');}
 for(const part of parts.filter(p=>p!=='ships'&&p!=='tai'&&!p.startsWith('shot:'))){const t=Date.now();
  const probe=await page.evaluate(p=>{try{return window.hsSystemsProbe(p);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000);}},part);
  console.log(probe+' ('+(Date.now()-t)+' ms)');assert.ok(probe.startsWith('PASS'),probe);
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');}
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS hs-systems '+session.hash.slice(0,12));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join('\n'));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
