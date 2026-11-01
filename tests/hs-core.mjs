// Hyperspace core check (hscore workstream): boot the real launch path (Launch FTL -> main menu), then check the HS
// gate, the capability report and modded run/profile save round trips (hsCoreProbe). Test-only. Modded runs get
// window.ftlModSession from the loader's preload (.build/hs/tools/mod-session-preload.mjs, FTL_HS_MOD_SESSION) and
// the modded assets as /assets; without them the same checks run in vanilla. Usage inside the app image:
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] HS_REPORT=/out/x.json node /app/tests/hs-core.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,ship=process.env.HS_SHIP==='-'?'':(process.env.HS_SHIP||'PLAYER_SHIP_HARD');
const data=join(process.cwd(),'.build/fixture-hs-core');await mkdir(data,{recursive:true});
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
 const report=JSON.parse(await page.evaluate(()=>window.hsCapabilityReport()));
 if(process.env.HS_REPORT)await writeFile(process.env.HS_REPORT,JSON.stringify(report,null,1));
 console.log('capability: modded='+report.modded+' hyperspace='+report.hyperspace+' unsupported keys='+report.unsupported.length+' names='+report.byName.length+' ignored='+report.ignored.length+' config='+JSON.stringify(report.config));
 assert.equal(report.modded,!!session);assert.equal(report.hyperspace,!!session&&session.hyperspace);
 // HS_EXPECT=mv: the typed values of Multiverse 5.5.1's data/hyperspace.xml (read by hand from that file).
 if(process.env.HS_EXPECT==='mv'){const v=report.config.values;
  assert.deepEqual({forceDlc:v.forceDlc,seedsEnabled:v.seedsEnabled,defaultCheckCargo:v.defaultCheckCargo,ion:[v.controllableIonDroneDelay,v.controllableIonDroneDelayInitial],
   dummy:v.dummyEquipmentSlotsOpacity,invertCaps:v.consoleInvertCaps,rounding:v.advancedCrewTooltipRounding,purchaseLimit:v.purchaseLimit,
   threshold:v.systemNoPurchaseThreshold,save:[v.savePrefix],stores:v.nodes.store,versionOk:report.config.versionOk},
   {forceDlc:true,seedsEnabled:true,defaultCheckCargo:true,ion:[10,6],dummy:0.3,invertCaps:false,rounding:2,purchaseLimit:[0,-16,106],
   threshold:[true,999,'--'],save:['hs_mv'],stores:2,versionOk:true});
  console.log('PASS MV hyperspace.xml values '+JSON.stringify(v));}
 // Let the menu run a few seconds of frames (rendering the HS main menu with mod data).
 await page.waitForTimeout(3000);
 const probe=await page.evaluate(s=>{try{return window.hsCoreProbe(s);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000);}},ship);
 console.log(probe);assert.ok(probe.startsWith('PASS'),probe);
 // The report again after the probe's run start (generation records), for the capability JSON.
 if(process.env.HS_REPORT)await writeFile(process.env.HS_REPORT,await page.evaluate(()=>JSON.stringify(JSON.parse(window.hsCapabilityReport()),null,1)));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS hs-core '+(session?'modded '+session.hash.slice(0,12):'vanilla'));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join('\n'));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
