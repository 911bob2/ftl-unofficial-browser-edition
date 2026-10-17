import assert from 'node:assert/strict';
import {back,enterFullscreen} from './gamebar-ui.mjs';

// Informational modal is reachable through the same settings in every context.
export async function colorblindNoticeUiCheck(page,{snapshot,windowIs,click,pass,name,context}) {
 const before=await snapshot(),profile=await page.evaluate(()=>window.ftlEngine.diagnosticProfile());
 const full=await page.evaluate(()=>document.fullscreenElement?.id??null),r=before.windowRect;
 await click(r.x+80,r.y+230);await windowIs('NoticeWindow');
 assert.equal((await snapshot()).notice.message,'Work in progress.');
 if(context==='run')await page.screenshot({path:'.build/colorblind-wip-'+name.toLowerCase()+'.png'});
 for(const key of ['1','f','Tab'])await page.locator('#game').press(key);
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 assert.equal((await snapshot()).window,'NoticeWindow','Modal consumed settings/gameplay shortcuts');
 const popup=(await snapshot()).windowRect;
 await click(popup.x+25,popup.y+25);assert.equal((await snapshot()).window,'NoticeWindow','Outside-button click dismissed notice');
 await click(popup.x+popup.width-60,popup.y+popup.height-18);await windowIs('OptionsWindow');
 assert.equal(await page.evaluate(()=>document.fullscreenElement?.id??null),full);
 await click(r.x+80,r.y+230);await windowIs('NoticeWindow');
 await back(page);await windowIs('OptionsWindow');
 assert.equal(await page.evaluate(()=>document.fullscreenElement?.id??null),full);
 await click(r.x+80,r.y+230);await windowIs('NoticeWindow');
 await page.locator('#game').press('Enter');await windowIs('OptionsWindow');
 assert.equal(await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),profile,'WIP popup changed saved settings/progress');
 const after=await snapshot();
 for(const k of ['autoPause','beaconPaths','dialogKeys','autofire','playerPaused','rng'])if(k in before)assert.equal(after[k],before[k],k+' changed through notice');
 pass('Colorblind WIP notice in '+context+' consumes shortcuts, closes to Options and preserves settings/fullscreen');
}

// Deliberate disposable run/dialogue/map setup, followed by real browser input.
export async function optionsUiChecks(page,{snapshot,state,windowIs,click,pass,name}) {
 const canvas=page.locator('#game');
 const original=await snapshot();
 const fields=['autoPause','beaconPaths','dialogKeys'];
 const originalSettings=Object.fromEntries(fields.map(k=>[k,original[k]]));
 const press=async key=>{await canvas.press(key);await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));};
 async function setup(kind='run') {
  const points=await page.evaluate(kind=>JSON.parse(window.optionsFixture(kind)),kind);
  await state('InGameState');await canvas.focus();
  return points;
 }
 async function configure(values,leaveOpen=false) {
  await back(page);await windowIs('PauseWindow');let r=(await snapshot()).windowRect;
  await click(r.x+55,r.y+185);await windowIs('OptionsWindow');r=(await snapshot()).windowRect;
  for(const [key,y] of [['autoPause',196],['beaconPaths',128]])if(key in values&&(await snapshot())[key]!==values[key]){
   await click(r.x+500,r.y+y);await page.waitForFunction(({key,value})=>JSON.parse(window.ftlEngine.debugSnapshot())[key]===value,{key,value:values[key]});
  }
  if('dialogKeys' in values)for(let i=0;i<3&&(await snapshot()).dialogKeys!==values.dialogKeys;i++){
   const before=(await snapshot()).dialogKeys;await click(r.x+500,r.y+94);await page.waitForFunction(before=>JSON.parse(window.ftlEngine.debugSnapshot()).dialogKeys!==before,before);
  }
  for(const [key,value] of Object.entries(values))assert.equal((await snapshot())[key],value,key);
  if(!leaveOpen){await back(page);await windowIs('game');}
 }
 await setup();
 if(!await page.evaluate(()=>!!document.fullscreenElement)){await enterFullscreen(page);}
 await configure({autoPause:true,beaconPaths:true,dialogKeys:1},true);
 await page.screenshot({path:'.build/gameplay-options-'+name.toLowerCase()+'.png'});
 await colorblindNoticeUiCheck(page,{snapshot,windowIs,click,pass,name,context:'run'});
 // Reopen with no row selected; configure may have focused a changed row.
 await back(page);await windowIs('game');await configure({},true);
 // Shift+Tab from no selection reaches the last rows: hotkeys, then Window Focus.
 await press('Shift+Tab');await press('Shift+Tab');await press('Space');
 assert.equal((await snapshot()).autoPause,false,'Keyboard toggles focus option without pausing gameplay');
 await press('Space');assert.equal((await snapshot()).autoPause,true);
 await back(page);await windowIs('game');
 assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
 pass('Gameplay options use pointer/keyboard controls and nested Back preserves fullscreen');

 let s=await snapshot();assert.equal(s.playerPaused,false);
 await page.locator('#save').focus();await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).focusPaused);
 s=await snapshot();assert.equal(s.playerPaused,false);assert.equal(s.window,'game');
 await canvas.focus();await page.waitForFunction(()=>!JSON.parse(window.ftlEngine.debugSnapshot()).focusPaused);
 assert.equal((await snapshot()).paused,false);
 await press('Space');assert.equal((await snapshot()).playerPaused,true);
 await page.locator('#save').focus();await canvas.focus();s=await snapshot();assert.equal(s.playerPaused,true);assert.equal(s.paused,true);
 await press('Space');
 await configure({autoPause:false});await page.locator('#save').focus();s=await snapshot();assert.equal(s.focusPaused,false);assert.equal(s.paused,false);await canvas.focus();
 pass('Actual blur/focus restores an active or explicitly paused run; disabled focus pause leaves it active');

 await configure({dialogKeys:0});await setup('dialog');
 await press('1');assert.equal((await snapshot()).window,'DialogueWindow');
 let box=(await snapshot()).dialogue.choices[0];await click(box.x+box.width/2,box.y+box.height/2);await windowIs('game');
 await configure({dialogKeys:2});
 // Create the controlled dialogue when physical input arrives. IPC, layout and
 // screenshots can consume the entire 0.3s delay before an otherwise "early"
 // click. This keeps real browser input while fixing the fixture's time origin.
 const freshDialogueOnInput=event=>page.evaluate(event=>document.getElementById('game').addEventListener(event,()=>window.optionsFixture('dialog'),{once:true,capture:true}),event);
 await setup('dialog');await freshDialogueOnInput('keydown');await press('1');s=await snapshot();assert.equal(s.window,'DialogueWindow','Brief delay rejects early key');
 box=s.dialogue.choices[0];await freshDialogueOnInput('pointerdown');await click(box.x+box.width/2,box.y+box.height/2);assert.equal((await snapshot()).window,'DialogueWindow','Brief delay rejects early mouse');
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).dialogue?.ready);
 await canvas.press('1');await windowIs('game');
 await configure({dialogKeys:1});await setup('dialog');await canvas.press('1');await windowIs('game');
 pass('Disabled/no-delay/brief-delay event modes gate real number keys and mouse clicks');

 await configure({beaconPaths:true});const points=await setup('map');
 async function hoverAndPixels() {
  const b=await canvas.boundingBox(),scale=Math.min(b.width/1280,b.height/720);
  await page.mouse.move(b.x+(b.width-1280*scale)/2+points.hover.x*scale,b.y+(b.height-720*scale)/2+points.hover.y*scale);
  await page.waitForTimeout(100);
  return page.evaluate(({x,y})=>{
   window.renderFrame(0);const gl=window.ftlGL.gl,buf=new Uint8Array(24*24*4);
   gl.readPixels(Math.round(x)-12,720-Math.round(y)-12,24,24,gl.RGBA,gl.UNSIGNED_BYTE,buf);
   let yellow=0;for(let i=0;i<buf.length;i+=4)if(buf[i]>200&&buf[i+1]>200&&buf[i+2]<90)yellow++;
   return yellow;
  },points.connection);
 }
 const enabled=await hoverAndPixels();
 await back(page);await windowIs('game');await configure({beaconPaths:false});await canvas.press('j');await windowIs('JumpWindow');
 const disabled=await hoverAndPixels();
 assert.ok(enabled>disabled+4,`Hovered connection is rendered only when enabled (${enabled}/${disabled} yellow pixels)`);
 await back(page);await windowIs('game');
 pass('Hover-path option changes actual rendered beacon connections without changing the map');

 await configure({autoPause:false,beaconPaths:false,dialogKeys:2});
 const persisted={autoPause:false,beaconPaths:false,dialogKeys:2};
 const sync=page.waitForResponse(r=>r.url().endsWith('/api/saves/current')&&r.request().method()==='PUT');await page.locator('#save').click();assert.ok((await sync).ok());
 await page.reload();await page.locator('#launch').click();await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden,{},{timeout:90000});
 if(!await page.evaluate(()=>!!window.ftlEngine))await page.locator('#takeover').click();await page.waitForFunction(()=>!!window.ftlEngine,{},{timeout:90000});
 for(const [key,value] of Object.entries(persisted))assert.equal((await snapshot())[key],value,key+' after cloud/reload');
 await setup();await configure(originalSettings);
 pass('All gameplay options survive cloud save/reload and fixture settings are restored');
}
