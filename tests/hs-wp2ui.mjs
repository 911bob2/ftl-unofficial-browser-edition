// WP2 in-run UI check (Hyperspace): a run, then the ship window's upgrades and crew manifest through real clicks, with frames in $WP2_OUT
// (paging, rename, crew limit boxes, drag swap). Test-only.   WP2_SHIP=PLAYER_SHIP_MVKESTREL  [FTL_HS_MOD_SESSION=... NODE_OPTIONS=--import=...] node /app/tests/hs-wp2ui.mjs
//
//
//   [FTL_HS_MOD_SESSION=/assets/mod-session.json NODE_OPTIONS=--import=<preload>] [HSC_PARTS=move,tooltip] node /app/tests/hs-crewui.mjs
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError,launchGame} from './lib/launch.mjs';
const BP_PROBE=String.raw`
-- OUT(name, value) is defined by the harness.
function V(v) local ok, n = pcall(function() return v:size() end); if not ok then return "userdata" end; local p = {}; for i = 0, math.min(n, 6) - 1 do p[#p + 1] = tostring(v[i]) end; return n .. "[" .. table.concat(p, ",") .. "]" end
function F(b, k) local ok, v = pcall(function() return b[k] end); if not ok then return "ERR" end; if type(v) == "userdata" then return V(v) end; return tostring(v) end
K1 = {"weaponCount", "originalWeaponCount", "droneCount", "originalDroneCount", "drone_count", "missiles", "health", "maxPower"}
K2 = {"originalCrewCount", "maxCrew", "bp_count", "boardingAI", "minSector", "maxSector", "weaponSlots", "droneSlots"}
K3 = {"loadWeapons", "loadDrones", "defaultCrew", "customCrew", "weapons", "drones", "augments", "systems"}
K4 = {"blueprintName", "layoutFile", "imgFile", "cloakFile", "shieldFile", "floorFile"}
function D(n) local b = Hyperspace.Blueprints:GetShipBlueprint(n, -1); local r = {}; for _, t in ipairs({K1, K2, K3, K4}) do for _, k in ipairs(t) do r[#r + 1] = k .. "=" .. F(b, k) end end; OUT(n, table.concat(r, " ")) end
D("PLAYER_SHIP_MVKESTREL")
D("PLAYER_SHIP_HARD")
D("PLAYER_SHIP_HARD_2")
D("PLAYER_SHIP_MANTIS_2")
D("BOSS_3")
D("DEFAULT")
D("PLAYER_SHIP_CIRCLE")
`;
const EQ_PROBE=String.raw`
-- OUT(name, value) is defined by the harness.
function H() local eq = Hyperspace.App.gui.equipScreen; local h = eq:GetCargoHold(); local p = {}; for i = 0, h:size() - 1 do p[#p + 1] = tostring(h[i]) end; return table.concat(p, ",") end
function G(f) local ok, v = pcall(f); if ok then return tostring(v) end; return "ERR" end
function S(tag) local sh = Hyperspace.ships.player; OUT(tag, "scrap=" .. G(function() return sh.currentScrap end) .. " hold=" .. H() .. " weapons=" .. G(function() return sh.weaponSystem.weapons:size() end) .. " augs=" .. G(function() return sh:HasAugmentation("SHIELD_RECHARGE") .. "/" .. sh:HasAugmentation("CREW_STIMS") end)) end
eq = Hyperspace.App.gui.equipScreen
B = Hyperspace.Blueprints
S("start")
eq:AddWeapon(B:GetWeaponBlueprint("LASER_BURST_1"), false, false) S("w_nofree")
eq:AddWeapon(B:GetWeaponBlueprint("LASER_BURST_1"), true, false) S("w_free")
eq:AddWeapon(B:GetWeaponBlueprint("LASER_BURST_2"), true, true) S("w_force")
eq:AddAugment(B:GetAugmentBlueprint("SHIELD_RECHARGE"), false, false) S("a_nofree")
eq:AddAugment(B:GetAugmentBlueprint("CREW_STIMS"), true, true) S("a_force")
eq:AddDrone(B:GetDroneBlueprint("DEFENSE_1"), true, false) S("d_free")
eq:AddDrone(B:GetDroneBlueprint("COMBAT_1"), false, false) S("d_nofree")
eq:AddToCargo("ION_1") S("cargo")
`;
const LUA_PROBE=String.raw`
local out = {}
local function t(n, f) local ok, v = pcall(f); out[#out+1] = n .. "=" .. tostring(ok and v or ("ERR " .. tostring(v))) end
local css = Hyperspace.CustomShipSelect.GetInstance()
t("orderN", function() return css.customShipOrder:size() end)
t("order0", function() return css.customShipOrder[0] end)
t("bpId100", function() return css:GetShipBlueprint(100) end)
t("bpId0", function() return css:GetShipBlueprint(0) end)
t("idFromName", function() return css:GetShipButtonIdFromName("PLAYER_SHIP_MVKESTREL") end)
t("idVariant", function() local p = css:GetShipIdAndVariantFromName("PLAYER_SHIP_MVKESTREL_2"); return tostring(p.first) .. "/" .. tostring(p.second) end)
t("countUnlocked", function() return css:CountUnlockedShips(0) end)
t("shipCount", function() return css:ShipCount() .. "," .. css:ShipCount(1) end)
t("maxPages", function() return css:GetMaxPages() end)
t("page", function() return css:GetCurrentPage() end)
t("isOpen", function() return tostring(css:IsOpen()) end)
t("sel", function() return css:GetSelection() .. "," .. css:GetSelectedId() .. "," .. css:GetLastSelected() end)
t("defName", function() return css:GetShipButtonDefinition(0).name end)
t("ordDef", function() return css:GetOrderedShipButtonDefinition(0).name end)
t("orderIdx", function() return css:GetShipButtonOrderIndex(0) end)
t("rnd", function() return css:GetRandomShipIndex() end)
t("lists", function() return css:GetShipButtonLists():size() end)
t("list0", function() local l = css:GetShipButtonListFromID(100); return l:GetId() .. "," .. l:GetIndex() .. "," .. l:GetPage() end)
t("shipSelect", function() return css.shipSelect.selectedShip .. "," .. css.shipSelect.currentType end)
local def = css:GetDefinition("PLAYER_SHIP_MVKESTREL")
t("def.name", function() return def.name end)
t("def.icons", function() return def.shipIcons:size() end)
t("def.reactor", function() return def.reactorPrices[0] .. "," .. def.reactorPrices:size() end)
t("def.crewList", function() local c = def.crewList; return c:size() .. ":" .. (c:size() > 0 and (c[0].species .. "/" .. c[0].roomId .. "/" .. c[0].name .. "/" .. tostring(c[0].isList)) or "") end)
t("def.hidden", function() return tostring(def.hiddenAugs:has_key("X")) .. "," .. def.hiddenAugs:size() end)
t("def.forceAuto", function() return tostring(def.forceAutomated.enabled) .. "," .. tostring(def.forceAutomated.value) end)
local un = Hyperspace.CustomShipUnlocks.instance
t("unlocked", function() return tostring(un:GetCustomShipUnlocked("PLAYER_SHIP_MVKESTREL")) .. "," .. tostring(un:GetCustomShipUnlocked("PLAYER_SHIP_MVKESTREL", 1)) end)
local ach = Hyperspace.CustomAchievementTracker.instance
t("achStatus", function() return ach:GetAchievementStatus("NOPE") end)
local bp = Hyperspace.Blueprints:GetShipBlueprint("PLAYER_SHIP_MVKESTREL", -1)
t("bp.name", function() return bp.name:GetText() end)
t("bp.class", function() return bp.shipClass:GetText() end)
t("bp.desc", function() return bp.desc.title:GetText() .. "|" .. bp.desc.description:GetText() end)
t("bp.unlock", function() return bp.unlock:GetText() end)
t("bp.nums", function() return bp.health .. "," .. bp.maxPower .. "," .. bp.missiles .. "," .. bp.drone_count .. "," .. bp.weaponSlots .. "," .. bp.droneSlots end)
t("bp.lists", function() return bp.weapons:size() .. "," .. bp.drones:size() .. "," .. bp.augments:size() .. "," .. bp.systems:size() .. ":" .. bp.weapons[0] .. "," .. bp.systems[0] end)
t("bp.files", function() return bp.cloakFile .. "|" .. bp.shieldFile .. "|" .. bp.floorFile end)
local set = Hyperspace.Settings
t("settings", function() local v = Hyperspace.Settings; return v.sound .. "," .. v.music .. "," .. v.difficulty .. "," .. tostring(v.achPopups) .. "," .. tostring(v.showPaths) .. "," .. v.dialogKeys end)
local eq = Hyperspace.App.gui.equipScreen
t("equip", function() return tostring(eq) ~= nil end)
t("addToCargo", function() eq:AddToCargo("LASER_BURST_2"); eq:AddToCargo("LASER_BURST_3"); return eq:GetCargoHold():size() end)
t("addAug", function() eq:AddAugment(Hyperspace.Blueprints:GetAugmentBlueprint("SHIELD_RECHARGE"), true, false); return eq:GetCargoHold():size() end)
error("R|" .. table.concat(out, "\n"))
`;


const port=8084,origin='http://localhost:'+port,parts=(process.env.HSC_PARTS||'move,tooltip').split(',');
const data=join(process.cwd(),'.build/fixture-hs-crewui');await mkdir(data,{recursive:true});
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

 const out=process.env.WP2_OUT||'/out';await mkdir(out,{recursive:true});
 const snapshot=async()=>JSON.parse(await page.evaluate(()=>window.ftlEngine.debugSnapshot()));
 const flush=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const at=async(x,y)=>{const box=await page.locator('#game').boundingBox(),s=Math.min(box.width/1280,box.height/720);return [box.x+(box.width-1280*s)/2+x*s,box.y+(box.height-720*s)/2+y*s];};
 const hover=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await flush();await page.waitForTimeout(150);};
 const click=async(x,y)=>{const [px,py]=await at(x,y);await page.mouse.move(px,py);await page.mouse.down();await page.mouse.up();await flush();await page.waitForTimeout(150);};
 const shot=async name=>{await hover(5,5);await page.locator('#game').screenshot({path:join(out,name+'.png')});};
 const probe=c=>page.evaluate(c=>{try{return window.hsWp2Probe(c);}catch(e){return 'FAILED '+(e&&e.message||e)+' '+String(e&&e.stack||'').slice(0,2000);}},c);
 const st=async()=>JSON.parse(await probe('state'));
 const ok=async c=>{const r=await probe(c);assert.ok(!r.startsWith('FAILED'),c+': '+r);return r;};
 await page.waitForTimeout(3000);
 console.log(await ok('start:'+(process.env.WP2_SHIP||'PLAYER_SHIP_MVKESTREL')));
 const hs=!!session;
 if(hs){await ok('crew:4');await ok('limit:12');}
 if(process.env.WP2_PART==='lua'){
  const r3=await ok('lua:local out={}; function OUT(n,v) out[#out+1]=n.."="..v end '+EQ_PROBE+' error("R|"..table.concat(out,"\\n"))');console.log('LUAEQ '+r3);
  const r=await ok('lua:'+LUA_PROBE);console.log('LUA '+r);
  const r2=await ok('lua:local out={}; function OUT(n,v) out[#out+1]=n.."="..v end '+BP_PROBE+' error("R|"..table.concat(out,"\\n"))');console.log('LUABP '+r2);
  await writeFile(join(out,'caps.txt'),await ok('caps'));
 } else if(process.env.WP2_PART==='panel'){
  // CustomCommandGui crew list paging: 11 crew = 8 + 3 (oracle session wp2-panel).
  await ok('crew:4');await page.waitForTimeout(1500);
  await shot('p0');console.log('state0 '+JSON.stringify(await st()));
  await hover(42,143);await page.locator('#game').screenshot({path:join(out,'p0hover.png')});
  await click(42,143);await page.waitForTimeout(300);await shot('p1');
  await hover(58,143);await page.locator('#game').screenshot({path:join(out,'p1hover.png')});
  await click(58,143);await page.waitForTimeout(300);await shot('p0again');
  const [wx,wy]=await at(300,300);await page.mouse.move(wx,wy);await page.mouse.wheel(0,100);await page.waitForTimeout(400);await shot('p1wheel');
  await page.mouse.wheel(0,100);await page.waitForTimeout(400);await shot('p1wheel2');
  await page.mouse.wheel(0,-100);await page.waitForTimeout(400);await shot('p0wheel');
  console.log('PASS panel');
 } else if(process.env.WP2_PART==='equip'){
  const cg=async()=>JSON.parse(await probe('cargo'));
  await click(660,48);await page.waitForTimeout(500);
  await click(560,103);await page.waitForTimeout(500);
  let s=await st();assert.equal(s.tab,'EQUIPMENT');await shot('e0');console.log('PASS equip open '+JSON.stringify(s.equip)+' '+JSON.stringify(await cg()));
  // Fill the weapon slots and cargo, then overflow: weapons first, then augments.
  for(const n of (process.env.WP2_GIVE||'LASER_BURST_2,LASER_BURST_3,ION_1,BEAM_1,MISSILES_2,LASER_BURST_1,ION_2,BOMB_1,SHIELD_RECHARGE,CREW_STIMS,SCRAP_RECOVERY_ARM,TELEPORT_HEAL,DRONE_RECOVERY').split(',')){console.log('give '+n+' '+await probe('give:'+n));}
  await page.waitForTimeout(500);
  let c=await cg();console.log('cargo '+JSON.stringify(c));await shot('e1');
  s=await st();console.log('equip '+JSON.stringify(s.equip));
  if(hs&&s.equip.over>1){const [a1,b1]=[[470,505],[505,505]];
   await click(1,1);}
  await ok('slots:aug=7,cargo=9');await page.waitForTimeout(500);
  s=await st();console.log('slots '+JSON.stringify(s.equip));await shot('e2');
  console.log('cargo '+JSON.stringify(await cg()));
  await ok('slots:aug=2,cargo=2');await page.waitForTimeout(500);
  s=await st();console.log('slots small '+JSON.stringify(s.equip));await shot('e3');
  console.log('cargo '+JSON.stringify(await cg()));
 } else {
 // Upgrades tab.
 await click(660,48);await page.waitForTimeout(500);
 let s=await st();assert.equal(s.tab,'UPGRADES','upgrades open');
 await shot('up0');console.log('PASS upgrades '+JSON.stringify(s));
 // Crew tab.
 await click(470,103);await page.waitForTimeout(500);
 s=await st();assert.equal(s.tab,'CREW');await shot('c0');console.log('PASS crew '+JSON.stringify(s));
 if(hs){
  assert.ok(s.boxes.length>0,'crew boxes');
  await hover(880,150);await shot('c0hover');
  await click(862,148);s=await st();console.log('after arrow '+JSON.stringify(s));await shot('c1');
  await hover(420,350);await shot('c1hover');
  await click(862,148);s=await st();console.log('after arrow 2 '+JSON.stringify(s));
  await ok('limit:8');await page.waitForTimeout(800);s=await st();console.log('limit 8 '+JSON.stringify(s));await shot('c2');
 }

 }
 const csp=errors.filter(e=>e.includes('Content Security Policy'));
 assert.deepEqual(errors.filter(e=>!csp.includes(e)),[],'browser errors');
 console.log('PASS hs-wp2ui '+(session?session.hash.slice(0,12):'vanilla'));
}catch(e){failed=true;console.log('FAILED',e.stack||e);console.log('errors',JSON.stringify(errors.slice(0,40),null,1));console.log('logs',logs.slice(0,40).join(' | '));
 await page.screenshot({path:join(data,'failure.png')}).catch(()=>{});}
finally{await browser.close();server.kill('SIGTERM');}
process.exitCode=failed?1:0;
