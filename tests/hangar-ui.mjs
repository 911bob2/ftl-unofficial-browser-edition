import assert from 'node:assert/strict';
import {back,enterFullscreen} from './gamebar-ui.mjs';

export async function hangarUiChecks(page,{snapshot,state,click,pass,name}) {
 const flush=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 const clickInput=click;click=async(...args)=>{await clickInput(...args);await flush();};
 const press=async key=>{await page.locator('#game').press(key);await flush();};
 let profile=await page.evaluate(()=>window.ftlEngine.diagnosticProfile());
 const run=await page.evaluate(()=>window.ftlEngine.runSave());
 const seed=(await snapshot()).rng;
 const hover=async(x,y)=>{
  const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);
  await page.mouse.move(box.x+(box.width-1280*scale)/2+x*scale,box.y+(box.height-720*scale)/2+y*scale);
 };
 const region=async(x,y,width,height)=>{
  const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);
  return page.screenshot({clip:{x:box.x+(box.width-1280*scale)/2+x*scale,y:box.y+(box.height-720*scale)/2+y*scale,width:width*scale,height:height*scale}});
 };
 await click(160,158);assert.equal((await snapshot()).ship,'PLAYER_SHIP_HARD');
 await click(100,158);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).shipList);
 // ShipSelect's two-page tutorial opens over the list until the profile has seen it.
 for(let i=0;i<2&&(await snapshot()).shipListTutorial;i++)await press('1');
 assert.equal((await snapshot()).shipListTutorial,false,'Ship list tutorial did not close');
 // Native ScoreKeeper saves only that the list tutorial was seen.
 const seen=await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),withoutTutorial=xml=>xml.replace(/ listTutorial="\d"/,'');
 assert.equal(withoutTutorial(seen),withoutTutorial(profile),'Opening the ship list changed progress');profile=seen;
 await hover(1100,510);await flush();const arrow=await region(328,227,10,30),decorations=await region(141,285,175,27);
 await click(200,410);assert.equal((await snapshot()).shipList,true,'Locked ship closed the grid');
 assert.equal((await snapshot()).ship,'PLAYER_SHIP_HARD','Locked ship replaced the selection');
 await click(805,135);await click(200,220);assert.equal((await snapshot()).shipList,true,'Locked Type B accepted');
 await click(890,135);await click(1050,220);assert.equal((await snapshot()).shipList,true,'Absent Type C accepted');
 await hover(1100,510);await flush();assert.deepEqual(await region(328,227,10,30),arrow,'Type C changed family progression arrows');
 assert.deepEqual(await region(141,285,175,27),decorations,'Locked Type C hid family achievement/victory decorations');
 await page.locator('#game').screenshot({path:'.build/hangar-list-type-c-'+name.toLowerCase()+'.png'});
 await click(720,135);await click(200,220);await page.waitForFunction(()=>!JSON.parse(window.ftlEngine.debugSnapshot()).shipList);
 assert.equal((await snapshot()).ship,'PLAYER_SHIP_HARD');
 await click(94,265);assert.equal((await snapshot()).ship,'PLAYER_SHIP_HARD','Locked layout accepted input');
 await click(100,158);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).shipList);
 await click(200,410);await press('Escape');
 await click(100,193);assert.equal((await snapshot()).ship,'PLAYER_SHIP_HARD');
 await click(100,158);await press('Escape');
 assert.equal((await snapshot()).shipList,false);
 assert.equal((await snapshot()).rng,seed);
 assert.equal(await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),profile);
 assert.equal(await page.evaluate(()=>window.ftlEngine.runSave()),run);
 pass('Global Type tabs, locked/absent layout rejection and cosmetic preview preserve progress/RNG');

 await hover(700,475);await flush();
 await page.evaluate(()=>{
  const audio=window.ftlAudio;audio.hangarOriginalCall=audio.call;audio.hangarPlays=0;
  audio.call=function(op,...args){if(op==='play')audio.hangarPlays++;return audio.hangarOriginalCall.call(this,op,...args);};
 });
 try {
  // Native LIST (70,145) and rooms (20,301) buttons; the ship arrows are disabled here.
  await hover(90,158);await flush();const first=await page.evaluate(()=>window.ftlAudio.hangarPlays);
  assert.ok(first>=1,'Hover did not request the original audio sample');
  await hover(100,158);await flush();assert.equal(await page.evaluate(()=>window.ftlAudio.hangarPlays),first,'Hover sample repeated inside one button');
  await hover(90,312);await flush();assert.ok(await page.evaluate(()=>window.ftlAudio.hangarPlays)>first);
  const beforeStart=await page.evaluate(()=>window.ftlAudio.hangarPlays);
  await hover(1170,50);await flush();assert.ok(await page.evaluate(()=>window.ftlAudio.hangarPlays)>beforeStart,'Start hover did not request audio');
  const start=await page.evaluate(()=>window.ftlAudio.hangarPlays);
  await hover(1175,50);await flush();assert.equal(await page.evaluate(()=>window.ftlAudio.hangarPlays),start,'Start hover repeated inside one button');
  await hover(1040,28);await flush();assert.ok(await page.evaluate(()=>window.ftlAudio.hangarPlays)>start,'Difficulty hover did not request audio');
 } finally {await page.evaluate(()=>{const audio=window.ftlAudio;audio.call=audio.hangarOriginalCall;delete audio.hangarOriginalCall;delete audio.hangarPlays;});}
 pass('Hover plays the original audio sample once per button entry');

 const originalName=(await snapshot()).shipName;
 const clear=async()=>{await press('End');for(let i=0;i<16;i++)await press('Backspace');};
 await click(50,30);await page.locator('#game').pressSequentially('x');await press('Escape');
 assert.equal((await snapshot()).shipName,(originalName+'x').slice(0,16),'Escape must confirm appended input');
 await click(50,30);await clear();await press('Enter');
 assert.equal((await snapshot()).shipName,'The Nameless One','Blank-name fallback differs from native');
 await enterFullscreen(page);
 await flush();
 // Physical Escape is reserved for leaving fullscreen in windowed Firefox.
 // Visible Back delivers Escape semantics to the game without leaving fullscreen.
 await click(50,30);await clear();await page.locator('#game').pressSequentially('Confirmed');
 await back(page);await flush();await state('SelectShipState');
 assert.equal((await snapshot()).shipName,'Confirmed');
 assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
 await click(50,30);await clear();await page.locator('#game').pressSequentially('1234567890123456789');await press('Enter');
 assert.equal((await snapshot()).shipName,'1234567890123456');
 await click(50,30);await clear();await page.locator('#game').pressSequentially('abcde');
 await press('Control+a');assert.equal((await snapshot()).shipName,'abcde','Ctrl+A changed text or selected it');
 await press('Home');await press('ArrowRight');await press('Delete');await press('End');await press('Backspace');await press('Enter');
 assert.equal((await snapshot()).shipName,'acd','Cursor/navigation/delete semantics failed');
 await click(50,30);await clear();await page.locator('#game').pressSequentially('Parity ship');await press('Enter');
 await hover(116,546);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tooltip?.includes('Human'));
 await page.locator('#game').screenshot({path:'.build/hangar-crew-tooltip-'+name.toLowerCase()+'.png'});
 await click(100,590);assert.equal((await snapshot()).customizing,true);
 await click(1170,50);await state('SelectShipState');assert.equal((await snapshot()).customizing,true,'Start bypassed customization modal');
 await click(150,649);await clear();await page.locator('#game').pressSequentially('Parity crew');await back(page);await flush();
 assert.equal((await snapshot()).crew[0].name,'Parity crew');
 assert.equal((await snapshot()).customizing,true,'Escape during crew naming left Customize');
 const colour=(await snapshot()).crew[0].colour;await click(276,574);assert.notEqual((await snapshot()).crew[0].colour,colour);
 await page.locator('#game').screenshot({path:'.build/hangar-crew-'+name.toLowerCase()+'.png'});
 await click(253,649);assert.equal((await snapshot()).customizing,false);
 const choices=(await snapshot()).crew;
 await click(90,312);assert.equal((await snapshot()).roomsVisible,false);
 await hover(700,480);await flush();assert.equal((await snapshot()).tooltip,null,'Tooltip stayed visible after leaving its target');
 await hover(55,420);await flush();assert.match((await snapshot()).tooltip,/Federation/,'Achievement hover must show immediately');
 await hover(1040,28);await flush();assert.ok((await snapshot()).tooltip,'Difficulty hover has no information');
 await page.locator('#game').screenshot({path:'.build/hangar-difficulty-'+name.toLowerCase()+'.png'});
 await click(1024,26);assert.equal((await snapshot()).difficulty,'EASY','Easy difficulty selection failed');
 await click(1024,78);assert.equal((await snapshot()).difficulty,'HARD','Hard difficulty selection failed');
 await click(1024,52);assert.equal((await snapshot()).difficulty,'NORMAL','Normal difficulty selection failed');
 await click(965,26);assert.equal((await snapshot()).difficulty,'NORMAL','Click outside difficulty buttons changed state');
 await hover(480,550);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tooltip?.includes('Artemis'));
 await page.locator('#game').screenshot({path:'.build/hangar-equipment-'+name.toLowerCase()+'.png'});
 await hover(55,420);await page.waitForFunction(()=>!!JSON.parse(window.ftlEngine.debugSnapshot()).tooltip);
 assert.match((await snapshot()).tooltip,/Federation/);
 const mode=(await snapshot()).ae;
 await page.waitForFunction(()=>[...window.ftlAudio.sources.values()].some(s=>s.state===0x1012 && s.node?.buffer?.duration>30),{},{timeout:30000}).catch(async error=>{
  console.error('Music state',await page.evaluate(()=>({context:window.ftlAudio.context.state,sources:[...window.ftlAudio.sources].map(([id,s])=>({id,state:s.state,buffer:s.buffer,duration:s.node?.buffer?.duration})),buffers:[...window.ftlAudio.buffers].map(([id,b])=>({id,duration:b.decoded?.duration}))})));throw error;
 });
 await page.evaluate(()=>{const a=window.ftlAudio;a.hangarOriginalCall=a.call;a.hangarMusic=[...a.sources].find(([,s])=>s.state===0x1012 && s.node?.buffer?.duration>30)?.[0];if(!a.hangarMusic)throw Error('Title music is not playing');a.hangarAudioOps=[];a.call=function(op,id,...args){if(id===a.hangarMusic)a.hangarAudioOps.push(op);return a.hangarOriginalCall.call(this,op,id,...args);};});
 await click(mode?1040:1175,436);await page.waitForFunction(a=>JSON.parse(window.ftlEngine.debugSnapshot()).ae!==a,mode);
 assert.equal((await snapshot()).shipName,'Parity ship');assert.deepEqual((await snapshot()).crew,choices);
 await click(100,158);await hover(1050,220);await page.locator('#game').screenshot({path:'.build/hangar-list-vanilla-'+name.toLowerCase()+'.png'});await back(page);await flush();assert.equal((await snapshot()).shipList,false);
 await click(mode?1175:1040,436);await page.waitForFunction(a=>JSON.parse(window.ftlEngine.debugSnapshot()).ae===a,mode);
 const audioOps=await page.evaluate(()=>{const a=window.ftlAudio,ops=a.hangarAudioOps;a.call=a.hangarOriginalCall;delete a.hangarOriginalCall;delete a.hangarAudioOps;return ops;});
 assert.equal(audioOps.includes('deleteSource'),false,'AE toggle deleted playing title music');
 assert.equal(audioOps.includes('stop'),false,'AE toggle stopped playing title music');
 // The original hangar has no in-canvas Options; the website Back/Close stays the exit.
 assert.equal((await snapshot()).shipName,'Parity ship');assert.deepEqual((await snapshot()).crew,choices);
 assert.equal((await snapshot()).roomsVisible,false);
 assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
 await hover(700,480);await page.locator('#game').screenshot({path:'.build/hangar-'+name.toLowerCase()+'.png'});
 await click(100,158);await page.locator('#game').screenshot({path:'.build/hangar-list-'+name.toLowerCase()+'.png'});
 await back(page);await state('SelectShipState');
 assert.equal((await snapshot()).shipList,false);
 await click(100,590);assert.equal((await snapshot()).customizing,true);
 await back(page);await state('MainMenuState');
 assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
 await click(1180,353);await state('SelectShipState');
 await page.evaluate(()=>document.exitFullscreen());
 pass('Native append/confirm/blank/cursor input, crew modal, tooltips and uninterrupted AE music retain choices/fullscreen');
 const beforeProbe=await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),stored:window.ftlEngine.profileSave(),rng:JSON.parse(window.ftlEngine.debugSnapshot()).rng}));
 const report=await page.evaluate(()=>window.hangarProbe());assert.ok(report.startsWith('PASS'),report);
 await state('MainMenuState');
 assert.deepEqual(await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),stored:window.ftlEngine.profileSave(),rng:JSON.parse(window.ftlEngine.debugSnapshot()).rng})),beforeProbe);
 const promotionFrame=await region(25,610,80,68);await page.waitForTimeout(350);
 assert.notDeepEqual(await region(25,610,80,68),promotionFrame,'Original promotional mech is missing or static');
 await page.locator('#game').screenshot({path:'.build/main-menu-native-'+name.toLowerCase()+'.png'});
 await click(1180,353);await state('SelectShipState');
 pass('All-layout/name/save/legacy/restart fixture restores progress/RNG; original promotional mech animates');
 // Populated decorations are tested only in this disposable launcher fixture.
 const ordinary=await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),ae:JSON.parse(window.ftlEngine.debugSnapshot()).ae}));
 try {
  await page.evaluate(({profile,ae})=>{
   const doc=new DOMParser().parseFromString(profile,'application/xml');
   for(const [tag,attrs] of [['ach',{id:'ACH_UNITED_FEDERATION',diff:'NORMAL'}],['layoutVictory',{ship:'PLAYER_SHIP_HARD',diff:'NORMAL'}],['layout',{ship:'PLAYER_SHIP_HARD_2',diff:'NORMAL'}]]) {
    const elem=doc.createElement(tag);for(const [k,v] of Object.entries(attrs))elem.setAttribute(k,v);doc.documentElement.append(elem);
   }
   window.start(ae,null,new XMLSerializer().serializeToString(doc));
  },ordinary);
  await state('MainMenuState');await click(1180,353);await state('SelectShipState');
  await hover(55,420);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tooltip?.includes('Unlocked on NORMAL'));
  await page.locator('#game').screenshot({path:'.build/hangar-earned-'+name.toLowerCase()+'.png'});
  await hover(260,450);await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).tooltip?.includes('Achieved with Type A (Normal)'));
  await click(100,158);await hover(260,298);await page.locator('#game').screenshot({path:'.build/hangar-list-earned-'+name.toLowerCase()+'.png'});
  pass('Earned achievement/difficulty and layout-victory tooltip copy with populated grid decorations');
 } finally {
  await page.evaluate(({profile,run,ae})=>window.start(ae,run,profile),ordinary);
  await state('MainMenuState');await click(1180,353);await state('SelectShipState');
 }
 assert.equal(await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),ordinary.profile,'Decoration fixture changed ordinary progress');
}
