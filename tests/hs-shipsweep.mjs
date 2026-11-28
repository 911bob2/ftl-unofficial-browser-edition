import {hangMs} from './lib/hang.mjs';
// Ship sweep, the engine's side of the oracle comparison (r27-ships): boot the real launch path to the main menu with a
// modded asset set, then write one row per ship blueprint as BrowserShipSweepDiagnostics builds it
// (shipSweepDump('dump <sector> <seed0> <from> <count>'): ShipGenerator.buildShip with the oracle probe's seed) and one
// start per hangar layout (shipSweepDump('start <ship>')). The rows go to $SWEEP_OUT/<name>.jsonl; the comparison
// with tools/hs-oracle's r27s sessions is .build/hs/r27-sweep/r27-ships/tools/compare.py (outside Git).
// Test-only. Usage inside the app image:
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> SWEEP_OUT=/out SWEEP_PASSES='-1:27000,4:47000' node /app/tests/hs-shipsweep.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,appendFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,out=process.env.SWEEP_OUT||'/tmp';
const passes=(process.env.SWEEP_PASSES??'-1:27000').split(',').filter(Boolean).map(p=>p.split(':').map(Number));
const chunk=+(process.env.SWEEP_CHUNK||40);
const NL=String.fromCharCode(10);

const data=join(process.cwd(),'.build/fixture-hs-shipsweep');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+hangMs(180000);
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(900000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>errors.push('PAGE CRASHED'));
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e)&&!/style-src/.test(e.text()))errors.push('CONSOLE '+e.text());});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-shipsweep-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 // a fresh engine: the hangar starts are new runs one after another, and a few hundred of them fill the heap
 const boot=async()=>{await page.goto(origin);await launchGame(page);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});};
 await boot();
 console.log('PASS main menu');
 const probe=w=>page.evaluate(w=>{try{return window.shipSweepDump(w);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,1500);}},w);
 const names=(await probe('names')).split(NL);
 await writeFile(join(out,'names.txt'),names.join(NL)+NL);
 for(const [sector,seed0] of passes){
  const file=join(out,'dump-'+sector+'.jsonl');await writeFile(file,'');let n=0,bad=0;
  // SWEEP_ORDER: the oracle probe's name list (names.json "ships", 1-based index j, seed seed0 + j); the engine's own list
  // has more names (blueprints it makes itself), so each name is built at the oracle's index: 'dump' takes seed0' + i + 1.
  if(process.env.SWEEP_ORDER){
   const order=JSON.parse(await readFile(process.env.SWEEP_ORDER,'utf8')).ships,at=new Map(names.map((x,i)=>[x,i]));
   for(let j=1;j<=order.length;j++){
    const i=at.get(order[j-1]);
    const r=i===undefined?JSON.stringify({name:order[j-1],seed:seed0+j,sector,error:'not an engine blueprint'}):await probe('dump '+sector+' '+(seed0+j-i-1)+' '+i+' 1');
    if(r.startsWith('FAILED')){console.log('FAILED '+order[j-1]+': '+r.slice(0,600));failed=true;continue;}
    await appendFile(file,r+NL);n++;if(r.includes('"error"'))bad++;
   }
   console.log((bad?'NOTE':'PASS')+' dump sector '+sector+' in the oracle order: '+n+' rows, '+bad+' with errors');
   continue;
  }
  for(let from=+(process.env.SWEEP_FROM||0);from<Math.min(names.length,+(process.env.SWEEP_TO||1e9));from+=chunk){
   const r=await probe('dump '+sector+' '+seed0+' '+from+' '+chunk);
   if(r.startsWith('FAILED')){console.log('FAILED chunk '+from+': '+r.slice(0,600));failed=true;continue;}
   await appendFile(file,r+NL);for(const l of r.split(NL)){n++;if(l.includes('"error"'))bad++;}
  }
  console.log((bad?'NOTE':'PASS')+' dump sector '+sector+': '+n+' rows, '+bad+' with errors');
 }
 // SWEEP_RNG='<sector>:<seed>:<name>,...': each build with every draw of the stream and its JS stack (rng-<name>.json), for
 // the oracle's random32 call trace (session r27s-rng)
 for(const spec of (process.env.SWEEP_RNG??'').split(',').filter(Boolean)){
  const [sector,seed,name]=spec.split(':');const r=await probe('rngtrace '+sector+' '+seed+' '+name);
  if(r.startsWith('FAILED')){console.log('FAILED rngtrace '+name+': '+r.slice(0,600));failed=true;continue;}
  await writeFile(join(out,'rng-'+name+'.json'),r);console.log('PASS rngtrace '+name+': '+(r.match(/"v":/g)||[]).length+' draws');
 }
 if(process.env.SWEEP_STARTS!=='0'){
  // SWEEP_START_FROM/SWEEP_START_TO: a slice of the hangar list (starts-<from>.jsonl); a new engine every SWEEP_RELOAD starts
  const all=(await probe('hangar')).split(NL).filter(Boolean),a=+(process.env.SWEEP_START_FROM||0),hangar=all.slice(a,+(process.env.SWEEP_START_TO||1e9));
  const file=join(out,'starts-'+a+'.jsonl'),every=+(process.env.SWEEP_RELOAD||40);await writeFile(file,'');let bad=0,k=0;
  for(const ship of hangar){if(k&&k%every===0)await boot();k++;const r=await probe('start '+ship+(process.env.SWEEP_START_FRAMES?' '+process.env.SWEEP_START_FRAMES:''));if(r.startsWith('FAILED')||r.includes('"error"'))bad++;await appendFile(file,r.replace(/\n/g,' ')+NL);}
  console.log((bad?'NOTE':'PASS')+' starts '+a+'..'+(a+hangar.length)+' of '+all.length+': '+hangar.length+' layouts, '+bad+' with errors');
 }
 if(errors.length){console.log('browser errors '+JSON.stringify(errors.slice(0,20)));failed=true;}
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));}
finally{await browser.close();server.kill('SIGTERM');}
console.log(failed?'FAILED hs-shipsweep':'PASS hs-shipsweep');
process.exitCode=failed?1:0;
