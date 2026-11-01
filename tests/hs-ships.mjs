// Hyperspace ships check (ships workstream): boot the real launch path to the main menu with a modded asset set, then
//  1. compare the engine's parsed ship definitions (hsShipsProbe('defs')) with this file's own reading of the merged XML
//     by Hyperspace 1.23.2's rules (CustomShipSelect.cpp ParseShipsNode/ParseCustomShipNode, ParseVanillaShipNode);
//  2. start every PLAYER_SHIP_* blueprint (hsShipsProbe('start <ship> 1')): no failure, a save/load round trip, and
//     the start matches the definition (hidden augments, HS crew list, crew limit, starting scrap/fuel, automation,
//     no drones without a drone system).
// Test-only. Usage inside the app image (merged files of the fixture in HS_TRUTH, e.g. ftlman's extracted ftl.dat):
//   FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload> HS_TRUTH=/truth HS_OUT=/out node /app/tests/hs-ships.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,readdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';
import {parseAll} from '../wwwroot/web/mods/xml.mjs';

const port=8084,origin='http://localhost:'+port,truth=process.env.HS_TRUTH,out=process.env.HS_OUT||'/tmp';
const only=process.env.HS_SHIPS?process.env.HS_SHIPS.split(','):null;

// ---- independent reading of the merged XML (HS rules) ----
const els=n=>n.children.filter(c=>c.name!==undefined);
const text=n=>n.children.filter(c=>c.text!==undefined||c.cdata!==undefined).map(c=>c.text??c.cdata).join('');
const bool=s=>s==='true'||s==='TRUE'||s==='True';
const lexInt=s=>{if(!/^[+-]?\d+$/.test(s))throw Error('bad lexical cast: '+s);return parseInt(s,10);};
const lexFloat=s=>{if(s.trim()!==s||s===''||isNaN(Number(s)))throw Error('bad lexical cast: '+s);return Math.fround(Number(s));};
function newDef(){return {name:'',hiddenAugs:{},crewList:[],noJump:false,noFuelStalemate:false,hpCap:20,startingFuel:-1,startingScrap:-1,augSlots:3,cargoSlots:4,
 forceAutomated:null,crewLimit:8,systemLimit:8,subsystemLimit:4,reactorPrices:[15,30,-1,-1,-1,-1],reactorPriceIncrement:5,maxReactorLevel:25,shipGenerator:'',shipIcons:[],rooms:{},artilleryRoomImages:0,exclusivity:0};}
const copy=d=>JSON.parse(JSON.stringify(d));
function parseCustom(node,def){
 let custom=false;
 for(const c of els(node)){const v=text(c);
  switch(c.name){
   case 'hiddenAug':custom=true;def.hiddenAugs[v]=(def.hiddenAugs[v]||0)+1;break;
   case 'crewLimit':custom=true;def.crewLimit=lexInt(v);break;
   case 'shipIcons':custom=true;for(const i of els(c))if(i.name==='shipIcon')def.shipIcons.push(text(i));break;
   case 'rooms':custom=true;for(const r of els(c)){
     if(r.name==='room'&&r.attrs.has('id')){const id=lexInt(r.attrs.get('id'));const rd=[false,0,0,0,0];
      for(const x of els(r)){if(x.name==='roomAnim')rd[4]++;if(x.name==='sensorBlind')rd[0]=bool(text(x));if(x.name==='hullDamageResistChance')rd[1]=lexFloat(text(x));
       if(x.name==='sysDamageResistChance')rd[2]=lexFloat(text(x));if(x.name==='ionDamageResistChance')rd[3]=lexFloat(text(x));}
      def.rooms[id]=rd;}}break;
   case 'crew':custom=true;for(const x of els(c))def.crewList.push([x.name,x.attrs.has('room')?lexInt(x.attrs.get('room')):0,x.attrs.get('name')??'',x.attrs.has('list')?bool(x.attrs.get('list')):false]);break;
   case 'noJump':custom=true;def.noJump=true;break;
   case 'noFuelStalemate':custom=true;def.noFuelStalemate=true;break;
   case 'hpCap':custom=true;def.hpCap=lexInt(v);break;
   case 'startingFuel':custom=true;def.startingFuel=lexInt(v);break;
   case 'startingScrap':custom=true;def.startingScrap=lexInt(v);break;
   case 'autoShipForce':custom=true;def.forceAutomated=bool(v);break;
   case 'systemLimit':custom=true;def.systemLimit=lexInt(v);break;
   case 'subsystemLimit':custom=true;def.subsystemLimit=lexInt(v);break;
   case 'customReactor':custom=true;if(c.attrs.has('maxLevel'))def.maxReactorLevel=lexInt(c.attrs.get('maxLevel'));if(def.maxReactorLevel<0)def.maxReactorLevel=0;
    if(def.maxReactorLevel>25){const n=Math.floor(def.maxReactorLevel/5)+1;while(def.reactorPrices.length<n)def.reactorPrices.push(-1);def.reactorPrices.length=n;}
    for(const x of els(c)){if(x.name==='baseCost')def.reactorPrices[0]=lexInt(text(x));if(x.name==='increment')def.reactorPriceIncrement=lexInt(text(x));
     if(x.name==='overrideCost'){let col=0;if(x.attrs.has('coloumn'))col=lexInt(x.attrs.get('coloumn'));if(x.attrs.has('column'))col=lexInt(x.attrs.get('column'));def.reactorPrices[col]=lexInt(text(x));}}break;
   case 'shipGenerator':custom=true;def.shipGenerator=v;break;
   case 'augSlots':custom=true;def.augSlots=lexInt(v);break;
   case 'cargoSlots':custom=true;def.cargoSlots=lexInt(v);break;
   case 'systemList':{const a=els(c).filter(x=>x.name==='artillery').length;def.artilleryRoomImages+=a;if(def.artilleryRoomImages>1)custom=true;break;}
   case 'exclusivityOverride':custom=true;def.exclusivity+=els(c).filter(x=>x.name==='exclusivityGroup').length;break;
  }}
 return custom;
}
async function expectedDefs(){
 const root=parseAll(await readFile(join(truth,'data/hyperspace.xml'),'utf8')).find(n=>n.name==='FTL');
 const ships=els(root).filter(n=>n.name==='ships');
 let dflt=newDef();
 for(const s of ships)for(const c of els(s))if(c.name==='customShip'&&!c.attrs.has('name'))parseCustom(c,dflt);
 const defs={},blueprints={};
 for(const f of ['blueprints.xml','autoBlueprints.xml','dlcPirateBlueprints.xml','dlcBlueprints.xml','dlcBlueprintsOverwrite.xml','bosses.xml']){
  let t;try{t=await readFile(join(truth,'data',f),'utf8');}catch{continue;}
  const top=parseAll(t);const nodes=[];for(const n of top){if(n.name==='FTL')nodes.push(...els(n));else if(n.name!==undefined)nodes.push(n);}
  for(const bp of nodes)if(bp.name==='shipBlueprint'&&bp.attrs.has('name')){const name=bp.attrs.get('name');blueprints[name]=bp;const d=copy(dflt);defs[name]=d;if(!parseCustom(bp,d))delete defs[name];}
 }
 const buttons=[],order=[],unlocks={};let hideFirstPage=false;
 for(const s of ships){if(s.attrs.has('hideFirstShipPage'))hideFirstPage=bool(s.attrs.get('hideFirstShipPage'));
  for(const c of els(s)){
   if(c.name==='ship'&&c.attrs.has('name')){const v=(a,d)=>!c.attrs.has(a)?d:c.attrs.get(a)==='dlc'?2:bool(c.attrs.get(a))?1:0;
    const name=c.attrs.get('name');
    for(const u of els(c).filter(x=>x.name==='unlock')){const vr=u.attrs.get('variant');const n=name+(vr==='b'?'_2':vr==='c'?'_3':'');
     const ty=els(u).find(x=>x.name==='type');const others=(els(u).find(x=>x.name==='otherUnlocks')?.children??[]).filter(x=>x.name==='ship').map(text);
     (unlocks[n]??=[]).push({type:ty?parseInt(text(ty),10):null,others});}
    if(!VANILLA.includes(name))buttons.push({name,t:[v('a',1),v('b',0),v('c',0)],arrows:els(c).filter(x=>x.name==='arrow').length,unlocks:els(c).filter(x=>x.name==='unlock').length,
     noAppend:c.attrs.has('noAppend')&&bool(c.attrs.get('noAppend'))});}
   if(c.name==='shipOrder')for(const x of els(c)){if(x.name==='ship')order.push(text(x));if(x.name==='empty')order.push('empty');}
   if(c.name==='customShip'&&c.attrs.has('name')){const name=c.attrs.get('name');const exists=name in defs;if(!exists)defs[name]=newDef();
    const load=c.attrs.get('load');if(load!==undefined){if(load in defs)defs[name]=copy(defs[load]);}else if(!exists)defs[name]=copy(dflt);
    defs[name].name=name;parseCustom(c,defs[name]);}
  }}
 return {defs,dflt,buttons,order,hideFirstPage,blueprints,unlocks};
}
// Custom stores (CustomStore.cpp ParseStoreNode) and what the checks need of the blueprints: costs, types, lists.
async function expectedStores(){
 const root=parseAll(await readFile(join(truth,'data/hyperspace.xml'),'utf8')).find(n=>n.name==='FTL');
 const price=n=>{const d={price:-1,useMinMax:false,sectorScaled:false,min:0,max:0,hasModifier:false,flat:false,mmin:0,mmax:0};
  for(const c of els(n)){const v=text(c);
   if(c.name==='price'){if(c.attrs.has('min')&&c.attrs.has('max')){d.min=lexFloat(c.attrs.get('min'));d.max=lexFloat(c.attrs.get('max'));d.useMinMax=true;if(d.min>d.max)d.max=d.min;}if(v!=='')d.price=lexInt(v);}
   if(c.name==='sectorScaled')d.sectorScaled=true;
   if(c.name==='modifier'){if(c.attrs.has('min')&&c.attrs.has('max')){d.mmin=lexFloat(c.attrs.get('min'));d.mmax=lexFloat(c.attrs.get('max'));if(d.mmin>d.mmax)d.mmax=d.mmin;}
    if(c.attrs.has('flat'))d.flat=bool(c.attrs.get('flat'));if(v!==''){d.mmin=d.mmax=lexFloat(v);}d.hasModifier=true;}}
  return d;};
 const stores={};
 for(const st of els(root).filter(n=>n.name==='store'))for(const cs of els(st))if(cs.name==='customStore'){
  const d={id:cs.attrs.get('id'),resources:[],repair:null,categories:[],limit:-1};
  for(const c of els(cs)){
   if(c.name==='hullRepair')d.repair=price(c);
   if(c.name==='resource'){let type='',min=0,max=0;for(const r of els(c)){if(r.name==='type')type=text(r);if(r.name==='count'){if(r.attrs.has('min')&&r.attrs.has('max')){min=lexInt(r.attrs.get('min'));max=lexInt(r.attrs.get('max'));}if(text(r)!==''){min=max=lexInt(text(r));}}}
    d.resources.push({type,min,max,price:price(c)});}
   if(c.name==='category'){const cat={type:Math.max(0,['WEAPONS','DRONES','AUGMENTS','CREW','SYSTEMS'].indexOf(c.attrs.get('type'))),group:c.attrs.has('group')?lexInt(c.attrs.get('group')):-1,title:'',items:[]};
    for(const x of els(c)){if(x.name==='customTitle')cat.title=text(x);if(x.name==='item'){let bp='',mystery=false;for(const i of els(x)){if(i.name==='blueprint')bp=text(i);if(i.name==='mysteryItem')mystery=bool(text(i));}cat.items.push({bp,mystery,price:price(x)});}}
    d.categories.push(cat);}
   if(c.name==='itemPurchaseLimit')d.limit=lexInt(text(c));
  }
  stores[d.id]=d;}
 const bps={},lists={};const kinds={weaponBlueprint:0,droneBlueprint:1,augBlueprint:2,crewBlueprint:3,systemBlueprint:4,itemBlueprint:5};
 for(const f of ['blueprints.xml','dlcBlueprints.xml','dlcBlueprintsOverwrite.xml','dlcPirateBlueprints.xml','autoBlueprints.xml']){
  let t;try{t=await readFile(join(truth,'data',f),'utf8');}catch{continue;}
  const top=parseAll(t);const nodes=[];for(const n of top){if(n.name==='FTL')nodes.push(...els(n));else if(n.name!==undefined)nodes.push(n);}
  for(const n of nodes){const name=n.attrs?.get('name');if(!name)continue;
   if(n.name in kinds){const c=els(n).find(x=>x.name==='cost');(bps[name]??={})[kinds[n.name]]={kind:kinds[n.name],cost:c?parseInt(text(c).trim(),10):0};}
   if(n.name==='blueprintList')lists[name]=els(n).filter(x=>x.name==='name').map(x=>text(x).trim());}
 }
 return {stores,bps,lists};
}
// GetItemPricing with float32 arithmetic; null when it draws from the stream.
function storePrice(p,dflt,level,free){
 const f=Math.fround;let v=p.price===-1?dflt:p.price;
 if(p.useMinMax){if(!p.sectorScaled)return null;v=Math.floor(f(f(f(p.max-p.min)*f(f(level)/7))+p.min));}
 v+=Math.trunc(free/2);
 if(p.hasModifier){if(p.mmin!==p.mmax)return null;v=Math.trunc(p.flat?f(v+p.mmin):f(f(v)*p.mmin));}
 return v;
}
function checkStores(r,S){
 const bad=[];const items={fuel:'fuel',missiles:'missiles',drones:'drones'};
 for(const s of r.stores){const d=S.stores[s.id],pr=[];if(!d){bad.push(s.id+': unknown');continue;}
  const res=d.resources.filter(x=>x.type in items);
  if(s.resources.length!==res.length)pr.push('resources '+s.resources.length+' != '+res.length);
  res.forEach((x,i)=>{const g=s.resources[i];if(!g)return;if(g[0].toLowerCase()!==x.type)pr.push('resource '+i+' '+g[0]);
   if(g[1]<x.min||g[1]>x.max)pr.push('resource '+x.type+' count '+g[1]+' not in '+x.min+'..'+x.max);
   const e=storePrice(x.price,S.bps[x.type]?.[5]?.cost??0,r.level,0);if(e!==null&&e!==g[2])pr.push('resource '+x.type+' cost '+g[2]+' != '+e);});
  if((s.repair===null)!==(d.repair===null))pr.push('repair '+s.repair);
  else if(d.repair){const e=storePrice(d.repair,r.level>5?4:r.level>2?3:2,r.level,0);if(e!==null&&e!==s.repair)pr.push('repair '+s.repair+' != '+e);
   if(e===null&&d.repair.useMinMax&&(s.repair<d.repair.min||s.repair>d.repair.max))pr.push('repair '+s.repair+' out of range');}
  if(s.limit!==d.limit)pr.push('limit '+s.limit+' != '+d.limit);
  const groups={};
  s.pages.forEach((page,pi)=>{if(page.length<1||page.length>2||(page.length===1&&pi!==s.pages.length-1))pr.push('page '+pi+' has '+page.length+' sections');
   for(const sec of page){const cands=d.categories.filter(c=>c.type===sec.category&&c.title===sec.title);
    if(!cands.length){pr.push('section '+sec.category+' '+JSON.stringify(sec.title)+' not defined');continue;}
    if(cands.length===1&&cands[0].group!==-1){const g=cands[0].group;groups[g]=(groups[g]||0)+1;if(groups[g]>1)pr.push('group '+g+' chosen twice');}
    const boxes=sec.boxes.flat();
    if(sec.boxes.some((l,i)=>l.length>3||(l.length<3&&i!==sec.boxes.length-1)))pr.push('box rows '+JSON.stringify(sec.boxes.map(l=>l.length)));
    if(cands.length!==1)continue;const cat=cands[0];
    if(boxes.length>cat.items.length||!boxes.length)pr.push('section '+sec.title+' boxes '+boxes.length+' of '+cat.items.length);
    if(boxes.length!==cat.items.length)continue;
    boxes.forEach((b,i)=>{const it=cat.items[i],bp=S.bps[b[0]]?.[sec.category];
     if(!bp||bp.kind!==sec.category){pr.push('box '+b[0]+' is not a '+sec.category);return;}
     if(it.bp!==''){const l=S.lists[it.bp];if(l?!l.includes(b[0]):b[0]!==it.bp)pr.push('box '+b[0]+' not from '+it.bp);}
     if(b[2]!==it.mystery)pr.push('box '+b[0]+' mystery '+b[2]);
     const free=b[3]?(S.bps[b[3]]?.[1]?.cost??0):0;const e=storePrice(it.price,bp.cost,r.level,free);
     if(e!==null&&e!==b[1])pr.push('box '+b[0]+' cost '+b[1]+' != '+e+(free?' (free drone '+b[3]+')':''));
     if(b[5]!==bp.cost)pr.push('box '+b[0]+' original price '+b[5]+' != '+bp.cost);});
   }});
  if(pr.length)bad.push(s.id+': '+pr.join('; '));}
 if(r.ui){const u=r.ui;if(u.disabled||!u.bought||u.paid!==u.cost||u.purchased!==1||u.page!==1)bad.push('buy page '+JSON.stringify(u));}else bad.push('buy page: no store with two pages');
 return bad;
}
// Custom achievements (CustomAchievements.cpp ParseAchievements): names defined or referenced, and the stats pages
// (a section starts a new page when the page would pass 500: 90 + 70 per row of seven, 160 when empty).
async function expectedAchievements(){
 const root=parseAll(await readFile(join(truth,'data/hyperspace.xml'),'utf8')).find(n=>n.name==='FTL');
 const names=new Set(),pages=[];let height=9999;
 const def=n=>{const nm=n.attrs.get('name')??n.attrs.get('load')??'';names.add(nm);return nm;};
 for(const a of els(root).filter(n=>n.name==='achievements'))for(const n of els(a)){
  if(n.name==='achievement')def(n);
  if(n.name==='page')for(const sec of els(n).filter(x=>x.name==='section')){let count=0;
   for(const c of els(sec)){const nm=c.name==='achievement'&&c.attrs.has('name')?def(c):c.name;if(names.has(nm))count++;}
   const h=count<1?160:90+70*Math.floor((count+6)/7);if(height+h>500){height=0;pages.push([]);}height+=h;pages[pages.length-1].push(count);}
 }
 for(const s of els(root).filter(n=>n.name==='ships'))for(const sh of els(s))if(sh.name==='ship')for(const c of els(sh))if(c.name==='achievement')def(c);
 return {names:[...names].sort(),pages};
}
const VANILLA=['PLAYER_SHIP_HARD','PLAYER_SHIP_STEALTH','PLAYER_SHIP_MANTIS','PLAYER_SHIP_CIRCLE','PLAYER_SHIP_FED','PLAYER_SHIP_JELLY','PLAYER_SHIP_ROCK','PLAYER_SHIP_ENERGY','PLAYER_SHIP_CRYSTAL','PLAYER_SHIP_ANAEROBIC'];

// ---- browser ----
const data=join(process.cwd(),'.build/fixture-hs-ships');await mkdir(data,{recursive:true});
const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:origin,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
const code=await new Promise(resolve=>{const p=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts','user01:user'],{cwd:'/app',stdio:'ignore',env});p.on('error',()=>resolve(1));p.on('exit',resolve);});
assert.equal(code,0,'fixture accounts');
const server=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
const deadline=Date.now()+180000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){if(server.exitCode!==null||Date.now()>deadline)throw Error('fixture server did not start');await new Promise(r=>setTimeout(r,250));}
const initial=new Map((await readFile(data+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const browser=withOnboarding(await chromium.launch({headless:true,args:['--disable-dev-shm-usage']}),origin),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const errors=[];page.setDefaultTimeout(600000);
page.on('pageerror',e=>errors.push('PAGE '+e.message));page.on('crash',()=>errors.push('PAGE CRASHED'));
page.on('console',e=>{if(e.type()==='error'&&!expectedConsoleError(e)&&!/style-src/.test(e.text()))errors.push('CONSOLE '+e.text());});
let failed=false;
try{
 const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me')).catch(e=>e);await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 await page.evaluate(async({username,initial})=>{let csrf=(await (await fetch('/api/auth/csrf')).json()).token;const post=(p,b)=>fetch('/api/auth/'+p,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(b)});const password='hs-ships-fixture-'+username;let r=await post('login',{username,password});if(r.status===401)r=await post('login',{username,password:initial});if(!r.ok)throw Error('login '+r.status);csrf=(await (await fetch('/api/auth/csrf')).json()).token;if((await (await fetch('/api/auth/me')).json()).mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('rotation');csrf=(await (await fetch('/api/auth/csrf')).json()).token;await post('login',{username,password});}},{username:'user01',initial:initial.get('user01')});
 await page.goto(origin);
 await launchGame(page);
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).state==='MainMenuState',{},{timeout:180000});
 console.log('PASS main menu');
 const probe=w=>page.evaluate(w=>{try{return window.hsShipsProbe(w);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,1500);}},w);

 // 1. definitions
 const got=JSON.parse(await probe('defs'));const exp=await expectedDefs();
 await writeFile(join(out,'hs-ships-defs.json'),JSON.stringify(got,null,1));
 const diffs=[];
 const cmp=(what,a,b)=>{const ja=JSON.stringify(a),jb=JSON.stringify(b);if(ja!==jb)diffs.push(what+': engine '+ja.slice(0,300)+' expected '+jb.slice(0,300));};
 cmp('hideFirstPage',got.hideFirstPage,exp.hideFirstPage);cmp('order',got.order,exp.order);
 cmp('buttons',got.buttons.map(b=>({name:b.name,t:b.t,arrows:b.arrows,unlocks:b.unlocks,noAppend:b.noAppend})),exp.buttons);
 cmp('def names',Object.keys(got.defs).sort(),Object.keys(exp.defs).sort());
 const norm=d=>{const r={...d};r.hiddenAugs=Object.fromEntries(Object.entries(d.hiddenAugs).sort(([a],[b])=>a<b?-1:a>b?1:0));r.rooms=Object.fromEntries(Object.entries(d.rooms).map(([k,v])=>[k,v.map(x=>typeof x==='number'?Math.fround(x):x)]));return r;};
 for(const [name,d] of Object.entries(exp.defs)){if(!got.defs[name])continue;const e=norm(d),g=norm(got.defs[name]);
  for(const k of Object.keys(e))cmp(name+'.'+k,g[k],e[k]);}
 console.log((diffs.length?'FAILED':'PASS')+' definitions: '+Object.keys(got.defs).length+' defs, '+got.buttons.length+' hangar entries, order '+got.order.length+(diffs.length?'\n  '+diffs.slice(0,40).join('\n  '):''));
 if(diffs.length)failed=true;

 // 2. starts
 const ships=['hangar','stores','boss','enemies'].includes(process.env.HS_PART)?[]:only??(await probe('ships')).split('\n');const results=[],bad=[],offHangar=[];
 // Layouts the hangar offers (an entry in the HS order with its a/b/c flag); the other blueprints only start from tests.
 const hangarOrder=[...exp.order];for(const b of exp.buttons)if(!b.noAppend&&!hangarOrder.includes(b.name))hangarOrder.push(b.name);
 const reachable=new Set();for(const n of hangarOrder){const b=exp.buttons.find(x=>x.name===n);if(!b)continue;b.t.forEach((t,v)=>{if(t)reachable.add(v===0?n:n+'_'+(v+1));});}
 for(const ship of ships){
  const r=await probe('start '+ship+' 1');
  if(r.startsWith('FAILED')){(reachable.has(ship)||only?bad:offHangar).push(ship+': '+r.slice(0,400));results.push({ship,error:r});continue;}
  const s=JSON.parse(r);results.push(s);
  const d=exp.defs[ship]??exp.dflt,bp=exp.blueprints[ship],problems=[];
  const hidden=Object.fromEntries(Object.entries(d.hiddenAugs).sort(([a],[b])=>a<b?-1:a>b?1:0));
  if(JSON.stringify(s.hidden)!==JSON.stringify(hidden))problems.push('hidden '+JSON.stringify(s.hidden)+' != '+JSON.stringify(hidden));
  if(s.crewLimit!==d.crewLimit)problems.push('crewLimit '+s.crewLimit+' != '+d.crewLimit);
  if(d.startingScrap!==-1&&s.scrap!==d.startingScrap)problems.push('scrap '+s.scrap+' != '+d.startingScrap);
  if(d.startingFuel!==-1&&s.fuel!==d.startingFuel)problems.push('fuel '+s.fuel+' != '+d.startingFuel);
  if(d.forceAutomated!==null&&s.automated!==d.forceAutomated)problems.push('automated '+s.automated);
  if(bp){const sys=els(els(bp).find(n=>n.name==='systemList')??{children:[]});const starts=n=>sys.some(x=>x.name===n&&x.attrs.get('start')!=='false');
   const vanillaCrew=els(bp).filter(n=>n.name==='crewCount').reduce((a,n)=>a+lexInt(n.attrs.get('amount')),0);
   if(s.crew.length<vanillaCrew)problems.push('crew '+s.crew.length+' < blueprint '+vanillaCrew);
   if(s.crew.length>vanillaCrew+d.crewList.length)problems.push('crew '+s.crew.length+' > '+(vanillaCrew+d.crewList.length));
   if(s.crew.length!==vanillaCrew+d.crewList.length)problems.push('crew '+s.crew.length+' != '+vanillaCrew+'+'+d.crewList.length+' (HS crew dropped: unknown race?)');
   if(!starts('drones')&&s.drones.some(x=>x))problems.push('drones without a drone system');
   if(!starts('weapons')&&s.weapons.some(x=>x))problems.push('weapons without a weapon system');}
  if(problems.length)bad.push(ship+': '+problems.join('; '));
 }
 if(ships.length)await writeFile(join(out,'hs-ships-starts.json'),JSON.stringify(results,null,1));
 // 2b. the oracle: the original game with HS + MV started each hangar ship (ship_full at the run's init, tools/hs-oracle ships).
 // Compared: crew species and rooms (multisets), weapons, drones, augments (with hidden ones), systems and levels, reactor,
 // hull, missiles and drone parts. Fuel and scrap are not set yet at that point of the original's start.
 const oracleDir=process.env.HS_ORACLE;
 if(oracleDir&&ships.length){
  let files=[];try{files=(await readdir(oracleDir)).filter(f=>f.endsWith('.json')&&f!=='index.json');}catch(e){}
  const od=[],rnd=[],sysNote=[];let compared=0;
  const ms=a=>JSON.stringify([...a].map(x=>JSON.stringify(x)).sort());
  const L=(await expectedStores()).lists;
  // A blueprint list (nested lists resolved) is a random pick: the leaf names it can give.
  const leaves=(n,seen=new Set())=>{if(!L[n]||seen.has(n))return [n];seen.add(n);return L[n].flatMap(x=>leaves(x,seen));};
  // Match each member to a slot (a fixed name or the leaves of a list); true when every member fits one slot.
  const fits=(members,slots)=>{const used=slots.map(()=>false);const go=i=>{if(i===members.length)return true;
   for(let k=0;k<slots.length;k++)if(!used[k]&&slots[k].includes(members[i])){used[k]=true;if(go(i+1))return true;used[k]=false;}return false;};
   return members.length===slots.length&&go(0);};
  // 0x4ae2f0's player stations; members beyond the stations the ship has get a random room.
  const STATIONS=['pilot','engines','weapons','shields','oxygen','sensors','doors','cloaking','medbay','drones','hacking','teleporter','artillery'];
  const HS_SYSTEMS=new Set(['temporal']);
  for(const f of files){
   const o=JSON.parse(await readFile(join(oracleDir,f),'utf8')),os=o.ship,e=results.find(r=>r.ship===o.bp);
   if(!e||e.error)continue;compared++;const pr=[],rn=[];
   const c2=(w,a,b)=>{if(JSON.stringify(a)!==JSON.stringify(b))pr.push(w+' engine '+JSON.stringify(a).slice(0,200)+' original '+JSON.stringify(b).slice(0,200));};
   const ocrew=os.crew.filter(c=>c.crew&&!c.drone);
   const bp=exp.blueprints[o.bp],d=exp.defs[o.bp]??exp.dflt;
   const bpCrew=bp?els(bp).filter(n=>n.name==='crewCount').flatMap(n=>Array(lexInt(n.attrs.get('amount'))).fill(n.attrs.get('class')??'human')):[];
   const slots=[...bpCrew,...d.crewList.map(c=>c[0])].map(n=>leaves(n));
   const random=slots.some(x=>x.length>1||L[x[0]]);
   if(!random)c2('crew species',ms(e.crew.map(c=>c[0])),ms(ocrew.map(c=>c.species)));
   else{const ef=fits(e.crew.map(c=>c[0]),slots),of=fits(ocrew.map(c=>c.species),slots);
    if(!ef)pr.push('crew species engine '+JSON.stringify(e.crew.map(c=>c[0]))+' do not fit the slots');
    if(!of)pr.push('crew species original '+JSON.stringify(ocrew.map(c=>c.species))+' do not fit the slots (model)');
    if(ef&&of)rn.push('species from lists');}
   const present=STATIONS.filter(n=>os.systems.some(x=>x.name===n)).length,randomRooms=Math.max(0,bpCrew.length-present);
   const er=e.crew.map(c=>c[1]),orr=ocrew.map(c=>c.room);
   if(!random&&!randomRooms)c2('crew rooms',ms(e.crew),ms(ocrew.map(c=>[c.species,c.room])));
   else{const left=[...orr];let common=0;for(const r of er){const i=left.indexOf(r);if(i>=0){left.splice(i,1);common++;}}
    if(common<er.length-randomRooms||er.length!==orr.length)pr.push('crew rooms engine '+JSON.stringify(er.slice().sort((a,b)=>a-b))+' original '+JSON.stringify(orr.slice().sort((a,b)=>a-b))+' ('+randomRooms+' random)');
    else if(randomRooms)rn.push(randomRooms+' random rooms');}
   const listCheck=(w,eng,orig,entries)=>{const isRandom=entries.some(n=>L[n]);
    if(!isRandom){c2(w,eng,orig);return;}
    const ok=xs=>xs.length===entries.length&&xs.every((x,i)=>leaves(entries[i]).includes(x));
    if(!ok(eng))pr.push(w+' engine '+JSON.stringify(eng)+' not from '+JSON.stringify(entries));
    if(!ok(orig))pr.push(w+' original '+JSON.stringify(orig)+' not from '+JSON.stringify(entries)+' (model)');
    if(ok(eng)&&ok(orig))rn.push(w+' from lists');};
   const entries=k=>{const l=bp&&els(bp).find(n=>n.name===k+'List');return l?els(l).filter(n=>n.name===k).map(n=>n.attrs.get('name')):[];};
   listCheck('weapons',e.weapons.filter(x=>x),os.weapons.map(w=>w.bp),entries('weapon'));
   listCheck('drones',e.drones.filter(x=>x),os.droneList.map(x=>x.bp),entries('drone'));
   const aug={};for(const a of e.augments)aug[a]=(aug[a]||0)+1;for(const [k,v] of Object.entries(e.hidden))aug[k]=(aug[k]||0)+v;
   c2('augments',Object.fromEntries(Object.entries(aug).sort()),Object.fromEntries(Object.entries(os.augments).sort()));
   const osys=os.systems.map(x=>[x.name,x.level]),missing=osys.filter(x=>HS_SYSTEMS.has(x[0])&&!e.systems.some(y=>y[0]===x[0]));
   c2('systems',ms(e.systems),ms(osys.filter(x=>!missing.includes(x))));
   if(missing.length)sysNote.push(o.bp+': '+missing.map(x=>x[0]+' '+x[1]).join(', '));
   c2('reactor',e.reactor,os.reactor.max);c2('hull',e.hull,os.hull);c2('missiles',e.missiles,os.missiles);c2('drone parts',e.droneParts,os.drones);
   if(pr.length)od.push(o.bp+': '+pr.join('; '));
   if(rn.length)rnd.push(o.bp+': '+rn.join(', '));
  }
  console.log((od.length?'FAILED':'PASS')+' oracle starts: '+compared+' of '+files.length+' original starts compared, '+od.length+' differ'+(od.length?'\n  '+od.join('\n  '):''));
  console.log('NOTE oracle starts with random parts (checked against their lists, not equal draws): '+rnd.length+'\n  '+rnd.join('\n  '));
  if(sysNote.length)console.log('NOTE HS custom systems not in the engine yet (systems workstream): '+sysNote.length+'\n  '+sysNote.join('\n  '));
  if(od.length)failed=true;
 }
 console.log((bad.length?'FAILED':'PASS')+' starts: '+ships.length+' ships ('+ships.filter(x=>reachable.has(x)).length+' in the hangar), '+bad.length+' with problems'+(bad.length?'\n  '+bad.join('\n  '):''));
 if(offHangar.length)console.log('NOTE blueprints outside the HS hangar that fail to start: '+offHangar.length+'\n  '+offHangar.join('\n  '));
 if(bad.length)failed=true;
 // 2c. custom stores generated for a run of the MV Kestrel: definitions, prices, pages, save round trip, the buy page.
 if(!['hangar','boss','enemies'].includes(process.env.HS_PART)){
  const S=await expectedStores();
  const ids=Object.keys(S.stores);
  const r=await probe('store PLAYER_SHIP_MVKESTREL '+(process.env.HS_STORE_SEED||'1'));
  if(r.startsWith('FAILED')){console.log('FAILED stores: '+r.slice(0,1500));failed=true;}
  else{const sr=JSON.parse(r);await writeFile(join(out,'hs-ships-stores.json'),JSON.stringify(sr,null,1));const bad=checkStores(sr,S);
   if(sr.stores.length!==ids.length)bad.push('stores '+sr.stores.length+' != '+ids.length);
   const pages=sr.stores.reduce((a,s)=>a+s.pages.length,0),boxes=sr.stores.reduce((a,s)=>a+s.pages.flat().reduce((b,sec)=>b+sec.boxes.flat().length,0),0);
   console.log((bad.length?'FAILED':'PASS')+' stores: '+sr.stores.length+' custom stores, '+pages+' pages, '+boxes+' boxes'+(bad.length?'\n  '+bad.slice(0,40).join('\n  '):''));
   if(bad.length)failed=true;}
 }
 // 2d. custom achievements: definitions and pages, an unlock over a profile round trip, a variable one, the stats page.
 if(!['hangar','boss','enemies'].includes(process.env.HS_PART)){
  const E=await expectedAchievements();
  const r=await probe('ach ACH_ACC_DATABASE prof_deaths 25 ACH_ACC_25DEATHS');
  if(r.startsWith('FAILED')){console.log('FAILED achievements: '+r.slice(0,1500));failed=true;}
  else{const a=JSON.parse(r);await writeFile(join(out,'hs-ships-achievements.json'),JSON.stringify(a,null,1));const bad=[];
   const c3=(w,x,y)=>{if(JSON.stringify(x)!==JSON.stringify(y))bad.push(w+': engine '+JSON.stringify(x).slice(0,300)+' expected '+JSON.stringify(y).slice(0,300));};
   c3('names',a.names,[...E.names].sort((p,q)=>p<q?-1:p>q?1:0));c3('pages',a.pages,E.pages);
   c3('unlock',a.unlock,{before:-1,after:1,wiped:-1,reloaded:1,saved:true});c3('variable',a.variable,[-1,0]);c3('popup',[a.popups,a.popupShown],[1,true]);
   c3('stats page',[a.tab,a.page],['ACHIEVEMENTS',1]);
   console.log((bad.length?'FAILED':'PASS')+' achievements: '+a.count+' custom achievements, '+a.pages.length+' stats pages'+(bad.length?'\n  '+bad.join('\n  '):''));
   if(bad.length)failed=true;}
 }
 // 2e. <boss> (CustomBoss.cpp): stage 1 crew, the recorded list after a death, stage 2 from it, surge drone pools, barrages.
 if(!['hangar','enemies'].includes(process.env.HS_PART)){
  const root=parseAll(await readFile(join(truth,'data/hyperspace.xml'),'utf8')).find(n=>n.name==='FTL');
  const crew=[],unnamed={d:[[],[],[]],b:[[],[],[]]},named={};
  const counts=(n,into)=>{for(const c of els(n)){const e=[c.attrs.get('name'),lexInt(c.attrs.get('count'))];
   if(c.attrs.has('difficulty')){const d=lexInt(c.attrs.get('difficulty'));if(d<=2)into[d].push(e);}else for(let d=0;d<3;d++)into[d].push(e);}return into;};
  for(const b of els(root).filter(n=>n.name==='boss')){unnamed.d=[[],[],[]];
   for(const c of els(b)){if(c.name==='crew')for(const x of els(c))crew.push([x.name,x.attrs.has('room')?lexInt(x.attrs.get('room')):0]);
    if(c.name==='surgeDrones'||c.name==='surgeBarrage'){const k=c.name==='surgeDrones'?'d':'b';
     if(c.attrs.has('name'))named[c.attrs.get('name')]=counts(c,[[],[],[]]);else counts(c,unnamed[k]);}}}
  // Projectiles per barrage weapon: one for a laser or missile, every <projectile> count (fake ones too) for flak.
  const wtype={};for(const f of ['blueprints.xml','dlcBlueprints.xml','autoBlueprints.xml']){let t;try{t=await readFile(join(truth,'data',f),'utf8');}catch{continue;}
   const top=parseAll(t);const nodes=[];for(const n of top){if(n.name==='FTL')nodes.push(...els(n));else if(n.name!==undefined)nodes.push(n);}
   for(const n of nodes)if(n.name==='weaponBlueprint'&&n.attrs.has('name')){const ty=els(n).find(x=>x.name==='type');const pj=els(n).find(x=>x.name==='projectiles');
    wtype[n.attrs.get('name')]={type:ty?text(ty).trim():'',n:pj?els(pj).reduce((a,x)=>a+lexInt(x.attrs.get('count')),0):1};}}
  const shots=list=>list.reduce((a,[w,c])=>a+c*(wtype[w]?.type==='BURST'?wtype[w].n:['LASER','MISSILES','BEAM','BOMB'].includes(wtype[w]?.type)?1:0),0);
  const ms=a=>JSON.stringify([...a].map(x=>JSON.stringify(x)).sort());
  for(const diff of [0,1,2]){const r=await probe('boss '+diff);
   if(r.startsWith('FAILED')){console.log('FAILED boss '+diff+': '+r.slice(0,1500));failed=true;continue;}
   const b=JSON.parse(r),bad=[];const c4=(w,x,y)=>{if(JSON.stringify(x)!==JSON.stringify(y))bad.push(w+': engine '+JSON.stringify(x).slice(0,300)+' expected '+JSON.stringify(y).slice(0,300));};
   c4('stage 1 crew',ms(b.stage1),ms(crew.filter(c=>c[1]<b.rooms1)));
   const left=[...b.stage1.map(x=>JSON.stringify(x))];let sub=true;for(const x of b.recorded){const i=left.indexOf(JSON.stringify(x));if(i<0)sub=false;else left.splice(i,1);}
   if(!sub||b.recorded.length!==b.stage1.length-1)bad.push('recorded '+JSON.stringify(b.recorded));
   c4('stage 2 crew',ms(b.stage2),ms(b.recorded.filter(c=>c[1]<b.rooms2)));
   const pool=unnamed.d[diff].reduce((a,x)=>a+x[1],0),mfk=(named.MFK_DRONE_SURGE?.[diff]??[]).reduce((a,x)=>a+x[1],0);
   c4('drones',b.drones,{pool,flying:pool,reused:pool,named:mfk,cleared:0});
   c4('barrage',b.barrage,shots(unnamed.b[diff]));if(b.beamSurge)c4('beam surge '+b.beamSurge,b.beams,(named[b.beamSurge]?.[diff]??[]).reduce((a,[w,c])=>a+(wtype[w]?.type==='BEAM'?c:0),0));c4('named barrage',b.namedBarrage,shots(named.MFK_LASER_SURGE?.[diff]??[]));
   console.log((bad.length?'FAILED':'PASS')+' boss difficulty '+diff+': '+b.stage1.length+' crew, stage 2 '+b.stage2.length+', '+pool+' surge drones, '+b.barrage+' barrage shots, beam surge '+b.beamSurge+': '+b.beams+' beams'+(bad.length?'\n  '+bad.join('\n  '):''));
   if(bad.length)failed=true;}
 }
 // 3. hangar (blank profile): order, cycling, list pages, a sector unlock with a profile round trip, a run start
 if(!only&&!['stores','boss','enemies'].includes(process.env.HS_PART)){
  const order=[...exp.order];for(const b of exp.buttons)if(!b.noAppend&&!order.includes(b.name))order.push(b.name);
  const unlocked=new Set(),isUnlocked=n=>!(n in exp.unlocks)||unlocked.has(n);
  for(let changed=true;changed;){changed=false;for(const [n,list] of Object.entries(exp.unlocks))for(const u of list)if(u.type===3&&!unlocked.has(n)&&u.others.every(isUnlocked)){unlocked.add(n);changed=true;}}
  const entries=order.filter(n=>n!=='empty'&&exp.buttons.some(b=>b.name===n));
  const startable=entries.filter(n=>exp.buttons.find(b=>b.name===n).t[0]!==0&&exp.blueprints[n]&&isUnlocked(n));
  const i0=entries.indexOf(startable[0]),cycle=[];for(let k=1;k<=entries.length;k++){const n=entries[(i0+k)%entries.length];if(startable.includes(n)){cycle.push(n);if(n===startable[0])break;}}
  const pages=[];for(let p=0;p*10<order.length;p++)pages.push(order.slice(p*10,p*10+10).filter(n=>n!=='empty'&&exp.buttons.some(b=>b.name===n)));
  const r=await probe('hangar PLAYER_SHIP_MVFED PLAYER_SHIP_MVKESTREL 4');
  if(r.startsWith('FAILED')){console.log('FAILED hangar: '+r.slice(0,1500));failed=true;}
  else{const h=JSON.parse(r);await writeFile(join(out,'hs-ships-hangar.json'),JSON.stringify(h,null,1));const hd=[];
   const c2=(w,a,b)=>{if(JSON.stringify(a)!==JSON.stringify(b))hd.push(w+': engine '+JSON.stringify(a).slice(0,400)+' expected '+JSON.stringify(b).slice(0,400));};
   c2('first',h.first,entries[0]);c2('cycle',h.cycle,cycle);
   c2('pages',h.pages.map(p=>p.cards),Array.from({length:h.pages.length},(_,i)=>pages[(i+(h.pages[0].page-1))%pages.length]));
   c2('unlock',h.unlock,{before:false,after:true,wiped:false,reloaded:true,saved:true});c2('started',h.started,'PLAYER_SHIP_MVFED');
   console.log((hd.length?'FAILED':'PASS')+' hangar: '+h.cycle.length+' ships cycled, '+h.previews.length+' layouts previewed, '+h.pages.length+' list pages'+(hd.length?'\n  '+hd.join('\n  '):''));
   if(hd.length)failed=true;}
 }
 // 4. every enemy ship blueprint built as an event ship at world levels 0-7 on each difficulty (HS_PART=enemies or
 //    HS_ENEMIES=1): no failure, one build per blueprint as the enemy (a frame), some saved and loaded.
 if(process.env.HS_PART==='enemies'||process.env.HS_ENEMIES==='1'){
  const total=+(await probe('enemyCount'));const chunk=+(process.env.HS_ENEMY_CHUNK||50);
  for(const diff of (process.env.HS_ENEMY_DIFFS||'0,1,2').split(',').map(Number)){
   const fails=[];let builds=0,spawned=0,saves=0,maxHull=0,maxCrew=0,automated=0,defCrew=0,temporal=0,overMax=0,gen=null;
   for(let from=0;from<total;from+=chunk){const r=await probe('enemies '+diff+' '+from+' '+chunk);
    if(r.startsWith('FAILED')){fails.push('chunk '+from+': '+r.slice(0,800));continue;}
    const e=JSON.parse(r);if(process.env.HS_ENEMY_LOG)console.log('  chunk '+diff+'/'+from+': '+e.builds+' builds, '+e.failures.length+' failures');builds+=e.builds;spawned+=e.spawned;saves+=e.saves;maxHull=Math.max(maxHull,e.maxHull);maxCrew=Math.max(maxCrew,e.maxCrew);
    automated+=e.automated;defCrew+=e.defCrewBuilds;temporal+=e.temporal;overMax+=e.overMax;gen=e.hsGenerator;
    for(const f of e.failures)fails.push(f.bp+' level '+f.level+': '+f.error.slice(0,1600));}
   const NL=String.fromCharCode(10);
   await writeFile(join(out,'hs-ships-enemies-'+diff+'.txt'),fails.join(NL));
   if(overMax)fails.push(overMax+' systems above their AI maximum');
   console.log((fails.length?'FAILED':'PASS')+' enemies difficulty '+diff+': '+total+' blueprints, '+builds+' builds, '+spawned+' spawned, '+saves+' saved/loaded, HS generator '+gen+
    ', max hull '+maxHull+', max crew '+maxCrew+', automated '+automated+', with <crew> lists '+defCrew+', temporal '+temporal+(fails.length?NL+'  '+fails.slice(0,40).join(NL+'  ')+(fails.length>40?NL+'  ... '+fails.length+' in all':''):''));
   if(fails.length)failed=true;}
 }
 await probe('menu');
 const report=JSON.parse(await page.evaluate(()=>window.hsCapabilityReport()));await writeFile(join(out,'hs-ships-capability.json'),JSON.stringify(report,null,1));
 if(errors.length){console.log('browser errors '+JSON.stringify(errors.slice(0,20)));failed=true;}
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));await page.screenshot({path:join(out,'hs-ships-failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
console.log(failed?'FAILED hs-ships':'PASS hs-ships');
process.exitCode=failed?1:0;
