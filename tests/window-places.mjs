// Where the mouse layout's windows sit (docs/TESTS.md, round 23), in vanilla or in a Hyperspace session (the loader's
// preload sets window.ftlModSession from FTL_HS_MOD_SESSION): the ship window (upgrades, crew and equipment tabs), the
// store, the star map and the sector map, the pause menu and the options, an event and the game over, each opened in
// a new run on the Kestrel and read from the engine's window rectangle (debugSnapshot.windowRect). The places are the
// original's, as the native oracle shows them (.build/hs/reports/hs-offset.md): CommandGui::OnLanguageChange puts the
// ship and store windows at x 682 less the widest system levels box (at most 340), which Multiverse's level texts
// make 357 wide (x 325), vanilla's and plain Hyperspace's 347 (x 335); every other window keeps its place. The equipment
// tab's item box sits 25 above the upgrades tab's levels box and is 323 wide.
// Test-only. Output: /out/winpos-<fixture>-*.png, WINPOS_OUT.
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] node /app/tests/window-places.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';

const port=8093,origin='http://localhost:'+port,out=process.env.WINPOS_OUT||'/out';
const data=join(process.cwd(),'.build/fixture-winpos');await mkdir(data,{recursive:true});await mkdir(out,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const session=process.env.FTL_HS_MOD_SESSION?JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION,'utf8')):null;
const hs=!!session?.hyperspace;
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(120000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>{errors.push('PAGE CRASHED');console.log('PAGE CRASHED');});
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e)&&!/Content Security Policy directive 'style-src/.test(e.text()))errors.push('CONSOLE '+e.text());});
let failed=false,mode=hs?'hs':'vanilla';
const pass=t=>console.log('PASS '+t);
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='winpos-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const probe=async()=>JSON.parse(await page.evaluate(()=>window.consoleProbe()));
 const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const key=async k=>{await page.locator('#game').press(k);await flush();await flush();};
 const run=async c=>{await page.evaluate(c=>window.ftlEngine.consoleExecute(c),c);await flush();await flush();};
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();await page.mouse.down();await flush();await page.mouse.up();await flush();await page.waitForTimeout(300);};
 const closeWindows=async()=>{for(let i=0;i<40;i++){const s=await snapshot(),w=s.window;if(w==='game')return;
   if(w!=='DialogueWindow')await key('Escape');else if(s.dialogue?.ready){const p=await probe(),c=p.choices.findIndex(t=>/proceed|enough|continue|leave|ignore/i.test(t));await key(String(c>=0?c+1:1+i%Math.max(1,p.choices.length)));}
   await page.waitForTimeout(400);}throw Error('a window stays open: '+(await snapshot()).window);};
 const waitWindow=async(name,ms=8000)=>{const end=Date.now()+ms;for(;;){const s=await snapshot();if(s.window===name&&s.windowRect)return s.windowRect;if(Date.now()>end)throw Error(name+' does not open: '+s.window);await page.waitForTimeout(200);}};

 await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='InGameState',{},{timeout:120000});
 await page.waitForTimeout(1500);await closeWindows();await page.locator('#game').focus();await page.mouse.move(...await at(1270,710));
 const mv=(await probe()).multipleOver;const fixture=mv?'F3':hs?'F2':'vanilla';mode=fixture;
 const screens=(await probe()).screens;
 console.log(fixture+': system levels boxes '+JSON.stringify(screens));
 // CommandGui::OnLanguageChange: x = 682 - the widest box, at most 340 (ship: at least 150, store: at least 283)
 assert.equal(screens.box,Math.max(...screens.boxes),'the widest box');
 assert.equal(screens.ship,Math.max(150,Math.min(340,682-screens.box)),'shipScreens x from the widest box');
 assert.equal(screens.store,Math.max(283,Math.min(340,682-screens.box)),'storeScreens x from the widest box');
 // the oracle (.build/hs/hs-offset/oracle; the window art's edge is 7 right of x): shipScreens and storeScreens at 335 in
 // vanilla and plain Hyperspace, 325 under Multiverse
 const nativeX=fixture==='F3'?325:335;

 const places={};
 const record=async(name,window,open,ms)=>{await open();const r=await waitWindow(window,ms);await page.mouse.move(...await at(1270,710));await page.waitForTimeout(400);
  await page.locator('#game').screenshot({path:join(out,'winpos-'+fixture+'-'+name+'.png')});places[name]=[r.x,r.y,r.width,r.height];console.log(name+' '+window+' '+JSON.stringify(r));return r;};
 const up=await record('upgrades','ShipWindow',()=>key('u'));
 // the shields' levels box: the upgrades window's InfoBox as wide as the widest system box (333 for 347; oracle wp-hs2 /
 // wp-mv2 13-sensors-info: 10 wider under Multiverse)
 await page.mouse.move(...await at(up.x+65,275));await page.waitForTimeout(400);
 await page.locator('#game').screenshot({path:join(out,'winpos-'+fixture+'-upgrades-info.png')});
 const sysInfo=(await probe()).screens.systemInfo;console.log('system info box '+sysInfo);
 await closeWindows();
 const eq=await record('equipment','ShipWindow',()=>key('i'));
 // the first weapon's item box: Equipment's InfoBox at the window's x + 600, y - 25, 323 wide (oracle oc-hs 04, ib-mv-eq)
 await page.mouse.move(...await at(eq.x+116,190));await page.waitForTimeout(400);
 await page.locator('#game').screenshot({path:join(out,'winpos-'+fixture+'-equipment-info.png')});
 const info=(await probe()).screens.equipmentInfo;console.log('equipment info box '+info);
 await closeWindows();
 await record('store','StoreWindow',()=>run('STORE'));await closeWindows();
 await record('map','JumpWindow',()=>key('j'));await closeWindows();
 await record('pause','PauseWindow',()=>key('Escape'));
 await record('options','OptionsWindow',()=>click(495,405));await closeWindows();
 await record('event','DialogueWindow',()=>run('EVENT ASTEROID_EXPLORE'),20000);await closeWindows();
 await record('sector-map','SectorMapWindow',()=>run('EXIT'));await closeWindows();
 // the whole crew dead (the console has no hull damage)
 await record('game-over','GameOverWindow',async()=>{for(const c of (await probe()).crew)await run('KILL '+c.split(':')[1]);},30000);
 console.log('places '+fixture+' '+JSON.stringify(places));
 assert.equal(screens.ship,nativeX,fixture+': shipScreens at '+nativeX+' as the original');
 assert.equal(screens.store,nativeX,fixture+': storeScreens at '+nativeX+' as the original');
 // the ship window's origin is 7 right of shipScreens (its art at x), the store at storeScreens (ShipWindow.kt,
 // StoreWindow.kt)
 for(const n of ['upgrades','equipment'])assert.deepEqual(places[n].slice(0,2),[nativeX+7,85],n+': the ship window 7 right of shipScreens');
 assert.deepEqual(places.store.slice(0,2),[nativeX,85],'the store at storeScreens');
 assert.equal(info,(places.equipment[0]+600)+',134,323','the item box 25 above the levels box, 323 wide');
 assert.equal(sysInfo,(places.upgrades[0]+600)+',159,'+(333+screens.box-347),'the levels box as wide as the widest system box');
 pass(fixture+': the ship and store windows and the equipment item box at the original\'s place');
 // the rest do not move: their frames' edges equal the oracle's in all three (measured on these screenshots, round 23)
 const fixed={map:[340,83],pause:[414,213],options:[227,140],event:[334,147],'sector-map':[365,218],'game-over':[400,199]};
 for(const [n,p] of Object.entries(fixed))assert.deepEqual(places[n].slice(0,2),p,n+' at the original\'s place');
 pass(fixture+': the star map, sector map, pause menu, options, event and game over at the original\'s place');
 assert.deepEqual(errors,[],'browser errors');
 console.log('PASS window places '+fixture);
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,30),null,1));try{console.log('snapshot',JSON.stringify(await page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()))).slice(0,600));console.log('output',(await page.evaluate(()=>window.ftlEngine.consoleOutput())).split('\n').slice(-12).join('\n'));await page.locator('#game').screenshot({path:join(out,'winpos-'+mode+'-failure.png')});}catch{}}
await browser.close();server.kill('SIGTERM');process.exit(failed?1:0);
