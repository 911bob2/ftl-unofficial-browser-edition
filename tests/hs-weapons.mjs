// Hyperspace weapons check (weapons workstream): boot the real launch path with the Multiverse session, then run
// hsWeaponsProbe's parts: units (tag data, pinpoint beam damage, shot limit, free missiles, fireTime, pre-ignited
// chargers, icon replacements, Lua weapon events), weapons (every weapon blueprint fires) and drones (every drone
// blueprint deploys). Test-only; needs window.ftlModSession from the loader's preload and the modded /assets.
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> [HSW_PARTS=units,weapons,drones] node /app/tests/hs-weapons.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,parts=(process.env.HSW_PARTS||'units,weapons,drones').split(',');
const data=join(process.cwd(),'.build/fixture-hs-weapons');await mkdir(data,{recursive:true});
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
page.on('console',e=>{const t=e.text();if(e.type()==='error'){if(!expectedConsoleError(e))errors.push('CONSOLE '+t+' @'+JSON.stringify(e.location()));}else if(/Hyperspace|unsupported/i.test(t))logs.push(t);});
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
 for(const part of parts){const t=Date.now();
  const probe=await page.evaluate(p=>{try{return window.hsWeaponsProbe(p);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,3000);}},part);
  console.log(probe+' ('+(Date.now()-t)+' ms)'+(errors.length?' [browser errors so far: '+errors.length+']':''));assert.ok(probe.startsWith('PASS'),probe);
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState');}
 if(parts.includes('units')){
  // Cooldown numbers against the oracle frame (mv-seed-12345 10-fight.png): the (255,120,120) text of the two
  // targeted boxes spans x 281-319 and 476-508, y 607-612 in game pixels.
  const r=await page.evaluate(()=>window.hsWeaponsProbe('scene'));console.log(r);assert.ok(r.startsWith('PASS'),r);
  await page.evaluate(()=>new Promise(res=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(res)))));
  const scan=await page.evaluate(()=>{const c=[...document.querySelectorAll('canvas')].sort((a,b)=>b.width*b.height-a.width*a.height)[0];
   const t=document.createElement('canvas');t.width=c.width;t.height=c.height;const x=t.getContext('2d');x.drawImage(c,0,0);
   const sx=c.width/1280,sy=c.height/720,d=x.getImageData(0,0,c.width,c.height).data,pts=[];
   for(let y=Math.floor(590*sy);y<625*sy;y++)for(let X=Math.floor(240*sx);X<560*sx;X++){const i=(y*c.width+X)*4;if(d[i]===255&&d[i+1]===120&&d[i+2]===120)pts.push([Math.floor(X/sx),Math.floor(y/sy)]);}
   const xs=[...new Set(pts.map(p=>p[0]))].sort((a,b)=>a-b),ys=pts.map(p=>p[1]),groups=[];
   for(const v of xs){const g=groups[groups.length-1];if(g&&v-g[1]<=8)g[1]=v;else groups.push([v,v]);}
   return {size:[c.width,c.height],groups,y:ys.length?[Math.min(...ys),Math.max(...ys)]:null};});
  console.log('cooldown numbers '+JSON.stringify(scan));
  await page.evaluate(()=>window.hsWeaponsProbe('menu'));
  assert.deepEqual({groups:scan.groups,y:scan.y},{groups:[[281,319],[476,508]],y:[607,612]},'cooldown numbers placement');
  console.log('PASS cooldown numbers match the oracle frame');}
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS hs-weapons '+session.hash.slice(0,12));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join('\n'));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
