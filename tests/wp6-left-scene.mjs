// WP6 leftovers, vanilla: the backdrop, planet and background fleet the port picks for a seed, read from the debug
// snapshot's "scene" next to a screenshot, to set against the native 1.6.14 captures (.build/wp6-left/native).
// env REPLAYS="csv;csv" (a native start beacon's draws from the capture ring: backdrop pick, planet pick, x, y, turn) replays them instead (window.sceneReplay) and writes /out/replay-<n>.json/.png.
// env SEEDS=<native layout seeds, from the capture ring> CLICKS="x,y;x,y" (map clicks of each seed's jumps, after the first open of the map).
// Output: /out/<seed>-<n>.json (snapshot) and /out/<seed>-<n>.png.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {launchGame} from './lib/launch.mjs';

const port=8086,origin='http://localhost:'+port,out=process.env.SC_OUT||'/out';
const seeds=(process.env.SEEDS||'').split(',').filter(Boolean),clicks=(process.env.CLICKS||'').split(';').filter(Boolean).map(c=>c.split(',').map(Number));
const data=join(process.cwd(),'.build/fixture-wp6-left-scene');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1280,height:720}}),page=await context.newPage();
page.setDefaultTimeout(120000);
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-core-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const move=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await flush();await page.waitForTimeout(150);};
 const key=async k=>{await page.locator('#game').press(k);await flush();};
 const wait=ms=>page.waitForTimeout(ms);
 const record=async(seed,n)=>{const s=await snapshot();if(n===0)await writeFile(join(out,`${seed}-trace.txt`),await page.evaluate(()=>window.nativeTrace()));await writeFile(join(out,`${seed}-${n}.json`),JSON.stringify({at:s.at,scene:s.scene,rng:s.rng,jumping:s.jumping??null,sector:s.sector})+'\n');
  await move(1270,5);await wait(400);await page.locator('#game').screenshot({path:join(out,`${seed}-${n}.png`)});console.log('record',seed,n,s.at,JSON.stringify(s.scene));};
 const replays=(process.env.REPLAYS||'').split(';').filter(Boolean);
 for(let r=0;r<replays.length;r++){
  if((await snapshot()).state!=='MainMenuState'){await page.goto(origin);await launchGame(page);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});}
  await page.evaluate(()=>window.newRunLayout('PLAYER_SHIP_HARD',1,12345));
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:60000});
  await wait(6000);
  const res=await page.evaluate(c=>window.sceneReplay(c),replays[r]);await wait(1500);
  await move(1270,5);await wait(400);await writeFile(join(out,`replay-${r+1}.json`),res+'\n');await page.locator('#game').screenshot({path:join(out,`replay-${r+1}.png`)});console.log('replay',r+1,res);
 }
 for(const seed of seeds){
  if((await snapshot()).state!=='MainMenuState'){await page.goto(origin);await launchGame(page);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});}
  await page.evaluate(s=>window.newRunLayout('PLAYER_SHIP_HARD',1,s),Number(seed));
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:60000});
  await wait(6000);
  await record(seed,0);
  await key('1');await wait(2000);await key('1');await wait(2000);await key('1');await wait(2000);
  let n=0;
  for(const [x,y] of clicks){
   n++;const before=(await snapshot()).at;
   await click(572,49);await wait(1500);await click(x,y);
   const end=Date.now()+60000;let s;
   while(true){s=await snapshot();if(!s.jumping&&s.at!==before)break;if(Date.now()>end)throw Error('no arrival '+JSON.stringify(s.at));await wait(300);}
   await wait(3000);await record(seed,n);
   await key('1');await wait(1500);await key('1');await wait(1500);
  }
 }
 console.log('PASS wp6-left-scene');
}catch(e){failed=true;console.log('FAILED',e.stack||e);await page.screenshot({path:join(out,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
