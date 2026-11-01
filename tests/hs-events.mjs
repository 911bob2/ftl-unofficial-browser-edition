// Hyperspace events check (events workstream): boot the real launch path (Launch FTL -> main menu) with a modded
// session, then play a seeded run the way an oracle session does (hsEventsProbe: picks by event name, jumps by
// beacon) and write one JSON line per choice box to HS_EVENTS_OUT for tools/hs-events/compare-oracle.py. Test-only;
// the session comes from the loader's preload (FTL_HS_MOD_SESSION) and the modded assets are /assets. Usage:
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> HS_EVENTS_SCRIPT=<file>
//   HS_EVENTS_OUT=/out/x.jsonl node /app/tests/hs-events.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port;
const data=join(process.cwd(),'.build/fixture-hs-events');await mkdir(data,{recursive:true});
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
const all=[];page.on('console',e=>{const t=e.text();all.push(t);if(e.type()==='error'){if(!expectedConsoleError(e))errors.push('CONSOLE '+t+' @'+e.location().url);}else if(/Hyperspace|unsupported/i.test(t))logs.push(t);});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-events-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 const t0=Date.now();
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu after '+(Date.now()-t0)+' ms');
 const script=await readFile(process.env.HS_EVENTS_SCRIPT,'utf8');
 page.setDefaultTimeout(900000);
 const t1=Date.now();
 const out=await page.evaluate(s=>{try{return window.hsEventsProbe(s);}catch(e){return JSON.stringify({k:'result',result:'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000)})+'\n';}},script);
 await writeFile(process.env.HS_EVENTS_OUT,out);
 const result=JSON.parse(out.trim().split('\n').pop());
 console.log(result.result+' boxes='+result.boxes+' in '+(Date.now()-t1)+' ms');
 assert.ok(result.result.startsWith('PASS'),result.result);
 assert.deepEqual(errors,[],'browser errors');
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join('\n'));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{if(process.env.HS_EVENTS_OUT)await writeFile(process.env.HS_EVENTS_OUT+'.console.txt',all.join('\n')).catch(()=>{});await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
