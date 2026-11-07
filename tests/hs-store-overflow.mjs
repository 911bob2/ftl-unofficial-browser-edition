// Store with an over-capacity augment (Multiverse, reported on 2.0.1): three augments fill the slots, an event gives a
// fourth (Sylvan's SYLVAN_DESTRUCTION_2_SHIELD gives SYLVAN_SHIELD_REWARD), then the store opens and shows its sell tab.
// The original keeps the fourth in the over-capacity augment box, on the equipment screen and on the store's sell tab
// (oracle session bt-store-over in tools/hs-oracle/sessions/bugs-tests.json). Runs the real Play launch and the HS console;
// vanilla's and the touch layout's over-capacity store are checked by the gameplay-parity mode (GameplayParityStoresChecks).
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> [STORE_OUT=/out] node /app/tests/hs-store-overflow.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8084,origin='http://localhost:'+port,out=process.env.STORE_OUT||join(process.cwd(),'.build/hs-store-overflow');
const data=join(process.cwd(),'.build/fixture-hs-store');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8'));
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1280,height:720}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>errors.push('PAGE CRASHED'));
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e))errors.push('CONSOLE '+e.text());});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-store-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu');
 const snap=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const key=async(k)=>{await page.locator('#game').press(k);await page.waitForTimeout(250);};
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await page.waitForTimeout(400);};
 const shot=async name=>page.locator('#game').screenshot({path:join(out,name+'.png')});
 // Answer every open choice box with the given keys (then '1'), waiting until the box takes keys.
 const answer=async(keys=[],limit=40)=>{for(let i=0;i<limit;i++){const s=await snap();if(!s.dialogue){await page.waitForTimeout(500);if(!(await snap()).dialogue)return i;continue;}
  if(!s.dialogue.ready){await page.waitForTimeout(150);continue;}await key(keys.shift()??'1');await page.waitForTimeout(300);}throw Error('choice boxes did not end');};
 await page.evaluate(()=>window.newRun('PLAYER_SHIP_MVKESTREL',1));await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState');
 // Multiverse's start beacon: the oracle's picks (1 x7, then 2 at START_BEACON_EXPLAIN).
 console.log('start boxes answered: '+await answer(['1','1','1','1','1','1','1','2']));
 const run=()=>page.evaluate(()=>window.diagnosticRun());
 // The HS console the way the oracle's driver types it: backslash opens the box, Return runs the command. HS's box closes
 // on Return (InputBox, TEXT_CONFIRM); the port's console stays open to show its output (docs/CONSOLE.md), so it is closed
 // here before the next key goes to the game, as in the oracle session.
 const consoleOpen=()=>page.evaluate(()=>/^open=true/.test(window.hsWp7UiProbe('console')));
 const command=async c=>{for(let i=0;!await consoleOpen();i++){assert.ok(i<40,'the console opens');await key('Backslash');await page.waitForTimeout(250);}
  await page.keyboard.type(c,{delay:20});await key('Enter');await page.waitForTimeout(500);
  if(await consoleOpen()){await key('Escape');await page.waitForTimeout(250);}};
 for(const a of ['SCRAP_COLLECTOR','AUTO_COOLDOWN','SHIELD_RECHARGE'])await command('AUG '+a);
 await command('SCRAP 500');
 const augs=[...(await run()).matchAll(/<augment[^>]*>/g)].length;console.log('augment elements before the event: '+augs);
 await key('i');await page.waitForTimeout(800);await shot('01-equipment-full');await key('Escape');
 await command('EVENT SYLVAN_DESTRUCTION_2_SHIELD');await page.waitForTimeout(1000);console.log('event boxes answered: '+await answer());
 assert.ok((await run()).includes('SYLVAN_SHIELD_REWARD'),'the fourth augment is kept (over capacity)');
 await key('i');await page.waitForTimeout(800);await shot('02-equipment-over');await key('Escape');await page.waitForTimeout(300);
 await command('STORE');await page.waitForTimeout(1200);
 assert.match((await snap()).window??'',/Store/,'the store opened');await shot('03-store-buy');
 await click(588,105);await page.waitForTimeout(1200);await shot('04-store-sell');
 await page.waitForTimeout(1000);
 const s=await snap();console.log('after the sell tab: state='+s.state+' window='+s.window);
 assert.equal(s.state,'InGameState');assert.match(s.window??'',/Store/,'the store is still open on its sell tab');
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS hs-store-overflow '+session.hash.slice(0,12));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,20),null,1));await page.screenshot({path:join(out,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
