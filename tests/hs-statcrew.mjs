// Hyperspace stat/crew check (statcrew workstream): boot the real launch path (Launch FTL -> main menu), then start a
// run on each ship of HS_SHIPS and dump every starting crew member's HS stats (hsStatCrewProbe) to HS_OUT, in the
// shape of the oracle's per-ship start dumps. Test-only; modded sessions come from the loader's preload as in
// hs-core.mjs. Usage inside the app image:
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> HS_SHIPS=A,B HS_OUT=/out/x.json node /app/tests/hs-statcrew.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,ships=process.env.HS_SHIPS||'PLAYER_SHIP_HARD';
const data=join(process.cwd(),'.build/fixture-hs-statcrew');await mkdir(data,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=process.env.FTL_HS_MOD_SESSION?JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8')):null;
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[],logs=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{const t=e.text();if(e.type()==='error'){if(!expectedConsoleError(e))errors.push('CONSOLE '+t+' @'+e.location().url);}else if(/Hyperspace|unsupported/i.test(t))logs.push(t);});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-core-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 const t0=Date.now();
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu after '+(Date.now()-t0)+' ms');
 await page.waitForTimeout(2000);
 if(process.env.HS_LUA_FILE){
  // Power-effect scenario (tools/hs-oracle/tracer/data/hs_oracle/statcrew.lua, the oracle's driver) on HS_SHIPS' first ship.
  const lua=await readFile(process.env.HS_LUA_FILE,'utf8');
  const res=await page.evaluate(([c,k,f,r])=>{try{return window.hsStatCrewLua(c,k,f,r);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000);}},[lua,process.env.HS_KEYS||'',Number(process.env.HS_FRAMES||12000),process.env.HS_RELOAD||'']);
  assert.ok(!res.startsWith('FAILED'),res);
  const j=JSON.parse(res);
  if(process.env.HS_OUT)await writeFile(process.env.HS_OUT,JSON.stringify(j,null,1));
  console.log('statcrew lua: picked='+JSON.stringify(j.picked)+' lines='+j.lines.length+' errors='+j.errors.length+' done='+j.done);
  for(const e of j.errors.slice(0,20))console.log('LUA ERROR '+e);
  const csp=errors.filter(e=>e.includes('Content Security Policy'));
  assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
  console.log('PASS hs-statcrew lua');
 }else{
 const out=await page.evaluate(s=>{try{return window.hsStatCrewProbe(s);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000);}},ships);
 assert.ok(!out.startsWith('FAILED'),out);
 const json=JSON.parse(out);
 if(process.env.HS_OUT)await writeFile(process.env.HS_OUT,JSON.stringify(json,null,1));
 const bad=json.ships.filter(s=>s.error);
 console.log('statcrew: races='+json.races+' powerDefs='+json.powerDefs+' statBoostDefs='+json.statBoostDefs+' ships='+json.ships.length+' errors='+bad.length);
 for(const s of bad)console.log('SHIP ERROR '+s.bp+' '+s.error);
 // parity sweep C6 regression checks (BrowserPsC6Checks), on every Hyperspace ship
 const c6=json.ships.flatMap(s=>(s.c6||[]).map(c=>({...c,bp:s.bp}))).concat((json.c6win||[]).map(c=>({...c,bp:'win run'})));
 for(const c of c6)console.log((c.ok?'PASS':'FAIL')+' c6 '+c.bp+' '+c.name+(c.detail?' '+c.detail:''));
 assert.ok(c6.every(c=>c.ok),'ps-c6 checks');
 if(process.env.HS_REPORT)await writeFile(process.env.HS_REPORT,await page.evaluate(()=>JSON.stringify(JSON.parse(window.hsCapabilityReport()),null,1)));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');
 // MV ship starts log CSP inline-style errors (open item in reports/hscore.md, not crew): counted, not failed.
 const csp=errors.filter(e=>e.includes('Content Security Policy'));
 if(csp.length)console.log('CSP console errors (known, hscore open item): '+csp.length);
 assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
 console.log('PASS hs-statcrew '+(session?'modded '+session.hash.slice(0,12):'vanilla'));
 }
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join(' | '));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
