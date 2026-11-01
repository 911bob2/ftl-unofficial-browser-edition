// WP6 slice A (events) check (Hyperspace): the oracle session tools/hs-oracle/sessions/wp6-events.json (id wp6-events-<part>) replayed
// through the real UI (new game on the Kestrel start, the session steps, the picks and keys) with the oracle's own Lua driver
// (tools/hs-oracle/tracer/data/hs_oracle/wp6-events.lua, mounted at /app/tests/wp6-events.lua) read through hsWp6EventsProbe.
// HS6E_PART=r (Lua members and hooks), m (multiShipFix), n (nebula / DYNASTY), f (customFleet), t (triggered events), as the
// session ids say. Output: /out/<part>.rows (the driver's rows) and /out/<part>-*.png. Test-only.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8087,origin='http://localhost:'+port,part=process.env.HS6E_PART||'main',out=process.env.HS6E_OUT||'/out';
const data=join(process.cwd(),'.build/fixture-hs-wp6events');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const driver=await readFile('/app/tests/wp6-events.lua','utf8');
const sessions=JSON.parse(await readFile('/app/tests/wp6-events.json','utf8'));
const evx=await readFile('/app/tests/events.xml.append','utf8'),bpx=await readFile('/app/tests/blueprints.xml.append','utf8');
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1280,height:720}}),page=await context.newPage();
const errors=[],logs=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{const t=e.text();if(e.type()==='error'){if(!expectedConsoleError(e))errors.push('CONSOLE '+t+' @'+JSON.stringify(e.location()));}else if(/Hyperspace|unsupported/i.test(t))logs.push(t);});
let failed=false;const rows=[];
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
 const move=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await flush();await page.waitForTimeout(150);};
 const key=async k=>{await page.locator('#game').press(k);await flush();};
 const wait=ms=>page.waitForTimeout(ms);
 const shot=async name=>{await page.locator('#game').screenshot({path:join(out,part+'-'+name+'.png')});};
 const probe=c=>page.evaluate(c=>{try{return window.hsWp6EventsProbe(c);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,2000);}},c);
 const ok=async c=>{const r=await probe(c);assert.ok(!r.startsWith('FAILED'),c.slice(0,80)+': '+r);return r;};
 const state=async()=>JSON.parse(await ok('state'));
 const lua=async c=>{const r=await ok('lua:'+c);const [head,...lines]=r.split('\n');assert.equal(head,'PASS',c+': '+head);rows.push(...lines);return lines;};
 // oracle ["pick", EVENT, key]: wait until EVENT's box is the open one, then press the key.
 const pick=async(ev,k,s=30)=>{const end=Date.now()+s*1000;let b;while((b=await probe('box'))!==ev){if(Date.now()>end)throw Error('pick '+ev+': open box '+b);await wait(200);}
  await wait(400);await key(String(k));await wait(300);};
 // oracle ["answer", 3]: the autoplay policy until no box is open for 3 s.
 const openMap=async()=>{await click(572,48);await wait(1500);await move(640,700);await wait(700);};
 const openMap0=openMap;
 const chooseAll=async()=>{let quiet=Date.now();for(let i=0;i<60;i++){await wait(400);const r=await ok('answer');if(r){console.log('answer '+r);quiet=Date.now();await wait(800);}else if(Date.now()-quiet>3000)break;}};

 const newGame=async()=>{
 if((await snapshot()).window==='WelcomeChoiceBox'){await key('1');}
 await click(1180,353);
 for(let i=0;i<20&&(await snapshot()).state!=='SelectShipState';i++){
  const s=await snapshot();if(s.window==='ConfirmationWindow'){const y=s.confirmation.yes;await click(y.x+y.width/2,y.y+12);}
  await wait(250);}
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='SelectShipState');
 await wait(1500);await move(640,700);await wait(500);
 await click(1160,40);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:60000});
 };
 await wait(2000);
 const session=sessions.find(s=>s.id==='wp6-events-'+part)||{steps:[]};assert.ok(session.steps.length||part==='v','no session '+part);
 await newGame();
 console.log(await ok('driver:'+driver));
 console.log(await ok('shim'));
 console.log(await ok('append:<probe><blueprints>'+bpx+'</blueprints><events>'+evx+'</events></probe>'));
 if(session.hs_options){console.log(await ok('options:'+Object.keys(session.hs_options).join(',')));}
 if(part==='v'){
  // port-only: a hazard stacked on another one (an ASB then asteroids) survives Save+Quit and Continue
  await lua("WP6A.load('WP6M_PDS_ALL')");await pick('WP6M_PDS_ALL',1);await wait(500);
  await lua("WP6A.load('WP6M_ASTEROIDS')");await pick('WP6M_ASTEROIDS',1);await wait(500);
  await lua("WP6A.haz('before')");await shot('stack');
  await ok('savereload');await wait(1500);
  await lua("WP6A.haz('after')");await shot('stack-reload');
  const [x,y]=rows.slice(-2).map(r=>r.split('|').slice(2).join('|'));
  console.log('before '+x+' after '+y);assert.equal(y,x,'stacked hazards after reload');assert.ok(x.startsWith('true|true'),'both hazards present: '+x);
  console.log('PASS stacked hazards survive a save');
 }
 const steps=session.steps.slice(8);
 for(const st of steps){
  const [op,a,b,c]=st;
  if(op==='lua')await lua(a);
  else if(op==='pick')await pick(a,b,c||30);
  else if(op==='wait')await wait(a*1000);
  else if(op==='shot')await shot(a);
  else if(op==='key')await key(String(a));
  else if(op==='move')await move(a,b);
  else if(op==='click')await click(a,b);
  else if(op==='trace'||op==='quit')break;
  else throw Error('step '+op);
 }
 await writeFile(join(out,part+'.rows'),rows.join('\n')+'\n');
 const csp=errors.filter(e=>e.includes('Content Security Policy'));
 assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
 console.log('PASS hs-wp6events '+part+' rows='+rows.length);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join(' | '));
 await writeFile(join(out,part+'.rows'),rows.join('\n')+'\n').catch(()=>{});
 await page.screenshot({path:join(out,part+'-failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
