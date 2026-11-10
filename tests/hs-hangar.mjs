// WP2 hangar check (Hyperspace): boot the real launch path, open the hangar from the main menu and capture the
// frames of the custom ship select UI (achievement strip, victory-type strip, reactor box and counts, augment pager,
// tooltips) into $HSH_OUT (default .build/hs-hangar). Asserts the snapshot only; frames are compared with the oracle by eye.
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] node /app/tests/hs-hangar.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8085,origin='http://localhost:'+port;
const data=join(process.cwd(),'.build/fixture-hs-hangar');await mkdir(data,{recursive:true});
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
const out=process.env.HSH_OUT||join(process.cwd(),'.build/hs-hangar');await mkdir(out,{recursive:true});
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
 const hover=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();await page.waitForTimeout(150);};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await flush();await page.waitForTimeout(150);};
 const shot=async name=>{await page.locator('#game').screenshot({path:join(out,name+'.png')});};
 await page.waitForTimeout(2000);
 if((await snapshot()).window==='WelcomeChoiceBox'){await page.locator('#game').press('1');await flush();}
 await click(1180,353);
 for(let i=0;i<20&&(await snapshot()).state!=='SelectShipState';i++){
  const s=await snapshot();if(s.window==='ConfirmationWindow'){const y=s.confirmation.yes;await click(y.x+y.width/2,y.y+12);}
  await page.waitForTimeout(250);}
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='SelectShipState');
 await page.waitForTimeout(1500);
 console.log('PASS hangar open');
 await shot('hangar-1');
 // The seed box (HS Seeds.cpp), the oracle session ps-s1-seed's steps: hover, active, typed, Backspace, done (UNLOCKS
 // DISABLED), Start hovered, cleared, left empty.
 if(session){
  // The canvas itself at 1280x720, as tools/ui-capture.mjs reads it, for frames.py against the oracle's shots.
  // CSSOM writes, not a style tag: the page's CSP has no 'unsafe-inline'.
  const fitStyle={position:'fixed',left:'0',top:'0',width:'1280px',height:'720px','z-index':'2147483647','aspect-ratio':'auto'};
  const before=await page.evaluate(fit=>{const s=document.getElementById('game').style,old=s.cssText;for(const [k,v] of Object.entries(fit))s.setProperty(k,v,'important');return old;},fitStyle);
  await page.waitForTimeout(500);
  const shot=async name=>{await flush();const data=await page.evaluate(()=>document.getElementById('game').toDataURL('image/png'));
   await writeFile(join(out,name+'.png'),Buffer.from(data.split(',')[1],'base64'));};
  const keys=async s=>{for(const ch of s)await page.locator('#game').press(ch);await flush();};
  await hover(640,700);await shot('seed-0-empty');
  await hover(1150,81);await shot('seed-1-hover');
  await click(1150,81);await shot('seed-2-active');
  await keys('123');await shot('seed-3-typed');
  await keys(['Backspace']);await shot('seed-4-back');
  await keys('45');await click(640,450);await page.waitForTimeout(300);await shot('seed-5-done');
  await hover(1150,40);await shot('seed-6-start-hover');
  await click(1150,81);await keys(Array(6).fill('Backspace'));await shot('seed-7-cleared');
  await click(640,450);await hover(640,700);await page.waitForTimeout(300);await shot('seed-8-empty-again');
  await page.evaluate(old=>{document.getElementById('game').style.cssText=old;},before);await page.waitForTimeout(500);
 }
 // Hover: the achievement strip, the reactor box, the victory-type strip, the trophy toggle.
 const spots=JSON.parse(process.env.HSH_SPOTS||'[[130,404,"ach0"],[200,404,"ach1"],[345,388,"reactor"],[345,415,"missiles"],[124,440,"vict0"],[260,454,"trophy"],[660,520,"aug0"]]');
 for(const [x,y,n] of spots){await hover(x,y);await shot('hover-'+n);}
 await hover(5,5);
 // Cycle through the first ships and capture each.
 const names=[];
 for(let i=0;i<(+process.env.HSH_SHIPS||4);i++){names.push((await snapshot()).ship);await shot('ship-'+i);await click(1112,158);await page.waitForTimeout(400);}
 console.log('PASS ships '+names.join(','));
 // The ship list with its victory filter box.
 await click(100,158);await page.waitForTimeout(500);
 for(let i=0;i<2&&(await snapshot()).shipListTutorial;i++){await page.locator('#game').press('1');await flush();}
 await shot('list');
 for(const [x,y,n] of JSON.parse(process.env.HSH_LIST_SPOTS||'[]')){await hover(x,y);await shot('list-'+n);}
 const csp=errors.filter(e=>e.includes('Content Security Policy'));
 assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
 console.log('PASS hs-hangar '+(session?session.hash.slice(0,12):'vanilla'));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join(' | '));
 await page.screenshot({path:join(out,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
