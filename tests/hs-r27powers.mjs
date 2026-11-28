import {hangMs} from './lib/hang.mjs';
// r27-powers oracle twin (BrowserR27PowersDiagnostics), derived from hs-statcrew.mjs. Hyperspace stat/crew check (statcrew workstream): boot the real launch path (Launch FTL -> main menu), then start a
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
const data=join(process.cwd(),'.build/fixture-hs-r27powers');await mkdir(data,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+hangMs(180000);
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
 // r27-powers driver (tools/hs-oracle/tracer/data/hs_oracle/r27-powers.lua, the oracle's) with its item lists (R27_LISTS,
 // generated outside Git) and the tracer's authored fight (R27_EVENTS, R27_BLUEPRINTS: events/blueprints .xml.append).
 const rd=f=>f?readFile(f,'utf8'):'';
 const lua=await rd(process.env.R27_LUA),lists=await rd(process.env.R27_LISTS),evx=await rd(process.env.R27_EVENTS),bpx=await rd(process.env.R27_BLUEPRINTS);
 const append='<probe><blueprints>'+bpx+'</blueprints><events>'+evx+'</events></probe>';
 const res=await page.evaluate(([a,l,c,k,p,parts,f,r])=>{try{return window.r27PowersLua(a,l,c,k,p,parts,f,r);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000);}},[append,lists,lua,process.env.R27_KEYS||'1,1,1,1,1,1,1,2',process.env.R27_PRE||'',process.env.R27_PARTS||'all',Number(process.env.R27_FRAMES||400000),process.env.R27_RELOAD||'']);
 assert.ok(!res.startsWith('FAILED'),res);
 const j=JSON.parse(res);
 if(process.env.R27_OUT)await writeFile(process.env.R27_OUT,JSON.stringify(j));
 console.log('r27-powers lua: picked='+JSON.stringify(j.picked)+' lines='+j.lines.length+' frames='+j.frames+' errors='+j.errors.length+' done='+j.done);
 for(const e of j.errors.slice(0,40))console.log('LUA ERROR '+e);
 const csp=errors.filter(e=>e.includes('Content Security Policy'));
 assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
 assert.ok(j.done,'r27_done');
 console.log('PASS hs-r27powers');
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join(' | '));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
