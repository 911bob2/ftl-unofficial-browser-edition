// The Tests page's "All tests" as a player runs it: a normal account on a disposable server, a Chromium without
// navigator.webdriver, and (DIAG_MOD_FILES) Hyperspace and Multiverse added to the browser's mod library through the Mods
// panel, so their suites run on the library's files. Not part of a suite.
//   [DIAG_MOD_FILES=/mods/Hyperspace.ftl,/mv/<assets>.zip,/mv/<data>.zip] [DIAG_OUT=/out] [DIAG_ONLY=id,id] node /app/tests/diagnostics-full.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,out=process.env.DIAG_OUT||join(process.cwd(),'.build/diagnostics-full');
const data=join(process.cwd(),'.build/fixture-diagnostics-full');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const modFiles=(process.env.DIAG_MOD_FILES||'').split(',').filter(Boolean);
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
// A player's browser: no navigator.webdriver.
await context.addInitScript(()=>Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false,configurable:true}));
// A probe runs in one go on the page's thread (Multiverse core starts and saves a run on the mod's data), so the page can stay
// busy for minutes between two status reads.
const page=await context.newPage(),errors=[];page.setDefaultTimeout(1200000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>errors.push('PAGE CRASHED'));
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e))errors.push('CONSOLE '+e.text().slice(0,400));});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='diag-full-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 if(modFiles.length){
  await page.locator('#mods-panel > summary').click();
  await page.locator('#mods-root .file-drop input[type=file]').setInputFiles(modFiles);
  await page.waitForFunction(n=>document.querySelectorAll('#mods-root [data-badge]').length===n,modFiles.length,{timeout:900000});
  console.log('PASS '+modFiles.length+' mod files in the library');
 }
 await page.goto(origin+'/Diagnostics');await page.waitForFunction(()=>!document.getElementById('run-tests').disabled);
 assert.equal(await page.evaluate(()=>navigator.webdriver),false);
 // The mod suites settle once the library was read.
 await page.waitForFunction(()=>!/Looking for the mod files/.test(document.getElementById('suite-options').textContent));
 await page.locator('.preset[data-preset="all"]').click();
 if(process.env.DIAG_ONLY){const only=process.env.DIAG_ONLY.split(',');await page.locator('#custom-selection').evaluate(e=>e.open=true);for(const box of await page.locator('#suite-options input').all()){const id=(await box.getAttribute('value'));if(await box.isEnabled())await box.setChecked(only.includes(id));}}
 const chosen=await page.evaluate(()=>[...document.querySelectorAll('#suite-options input:checked')].map(i=>i.value));
 console.log('selected '+chosen.join(','));
 if(modFiles.length)for(const id of ['hs-core','mv-core'])if(!process.env.DIAG_ONLY||process.env.DIAG_ONLY.includes(id))assert.ok(chosen.includes(id),id+' is not available');
 const t0=Date.now();await page.locator('#run-tests').click();
 let last='';
 while(!/^Completed|^Stopped/.test(await page.locator('#test-status').textContent())){
  const now=await page.locator('#test-summary').textContent();if(now!==last){console.log(Math.round((Date.now()-t0)/1000)+' s: '+now+' · '+(await page.locator('#test-status').textContent()).slice(0,120));last=now;}
  if(Date.now()-t0>4*3600*1000)throw Error('All tests did not finish in 4 hours');
  await page.waitForTimeout(5000);
 }
 const pending=page.waitForEvent('download');await page.locator('#export-results').click();
 const report=JSON.parse(await readFile(await (await pending).path(),'utf8'));
 await writeFile(join(out,'report.json'),JSON.stringify(report,null,1));
 const bad=report.results.filter(r=>r.status!=='passed');
 console.log(`${report.results.length} cases, ${bad.length} not passed, ${Math.round((Date.now()-t0)/60000)} min`);
 for(const r of bad)console.log('NOT PASSED '+r.name+': '+String(r.error).slice(0,600));
 assert.equal(report.status,'passed','All tests: '+report.status+(report.error?' ('+report.error+')':''));
 // Console errors are listed (a probe's own engine messages land there too); a page error or crash fails the run.
 console.log('console errors '+JSON.stringify(errors.filter(e=>e.startsWith('CONSOLE')).slice(0,20),null,1));
 assert.deepEqual(errors.filter(e=>!e.startsWith('CONSOLE')),[],'page errors');
 console.log('PASS diagnostics-full '+report.results.length+' cases');
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,20),null,1));await page.screenshot({path:join(out,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
