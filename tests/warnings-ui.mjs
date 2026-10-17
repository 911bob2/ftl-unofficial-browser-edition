import assert from 'node:assert/strict';
import {back} from './gamebar-ui.mjs';

// Controlled boarding setup; subsequent actions use browser input and the
// normal frame clock. This is interface coverage, not a played campaign.
export async function warningUiChecks(page,{snapshot,state,windowIs,click,pass,name}) {
 const sameFullscreen=async()=>assert.equal(await page.evaluate(()=>document.fullscreenElement?.id),'game-container');
 const progress=()=>page.evaluate(()=>({run:window.ftlEngine.diagnosticRun(),profile:window.ftlEngine.diagnosticProfile(),stored:window.ftlEngine.runSave()}));
 // StarMap::Open (0x5ea3a0) draws two angles from the generation stream every time the map opens, so the
 // saved native stream state moves on by exactly two LCG steps; everything else must stay identical.
 const afterMapOpen=p=>{const step=s=>{let v=BigInt.asUintN(64,BigInt(s));for(let i=0;i<2;i++)v=BigInt.asUintN(64,v*0x5851F42D4C957F2Dn+1n);return BigInt.asIntN(64,v).toString();};
  const fix=x=>x?.replace(/browserNativeRngState="(-?\d+)"/,(_,s)=>`browserNativeRngState="${step(s)}"`);return {...p,run:fix(p.run),stored:fix(p.stored)};};
 const answer=async yes=>{const rect=(await snapshot()).confirmation[yes?'yes':'no'];await click(rect.x+rect.width/2,rect.y+12);};
 const setup=async()=>{
  await page.evaluate(()=>window.warningFixture());await state('InGameState');await windowIs('game');
  if(!(await snapshot()).playerPaused){await page.locator('#game').press('Space');await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).playerPaused);}
  await page.evaluate(()=>window.ftlEngine.save());
 };
 await setup();const original=await progress();const originalState=await snapshot();
 for(const [label,dy] of [['Restart',146],['Hangar',109]]){
  await back(page);await windowIs('PauseWindow');let rect=(await snapshot()).windowRect;
  await click(rect.x+50,rect.y+dy);await windowIs('ConfirmationWindow');
  assert.equal((await snapshot()).confirmation.message,'confirm_end_game');
  await page.locator('#game').press('1');assert.equal((await snapshot()).window,'ConfirmationWindow');
  await page.screenshot({path:'.build/warning-'+label.toLowerCase()+'-'+name.toLowerCase()+'.png'});
  await answer(false);await windowIs('PauseWindow');assert.deepEqual(await progress(),original,label+' No mutated progress');await sameFullscreen();
  rect=(await snapshot()).windowRect;await click(rect.x+50,rect.y+dy);await windowIs('ConfirmationWindow');
  await back(page);await windowIs('PauseWindow');assert.deepEqual(await progress(),original,label+' Back mutated progress');await sameFullscreen();
  await back(page);await windowIs('game');
 }
 pass('Restart/Hangar No and visible Back retain exact run/profile/checkpoint, consume hotkeys and keep fullscreen');
 // Both the original jump hotkey and top-bar pointer action share the warning.
 for(const input of ['pointer','hotkey']){
  if(input==='pointer')await click(560,44);else await page.locator('#game').press('j');
  await windowIs('ConfirmationWindow');assert.equal((await snapshot()).confirmation.message,'confirm_leave_crew');
  if(input==='pointer')await page.screenshot({path:'.build/warning-boarders-'+name.toLowerCase()+'.png'});
  await answer(false);await windowIs('game');assert.deepEqual(await progress(),original);await sameFullscreen();
 }
 await click(560,44);await windowIs('ConfirmationWindow');await answer(true);await windowIs('JumpWindow');
 assert.equal((await snapshot()).fuel,originalState.fuel);await sameFullscreen();
 // The website's Back button used to blur the canvas, which saved the run while the map was open; save it here the
 // same way so the stored checkpoint is deterministic (the 30 s autosave would otherwise decide it).
 await page.evaluate(()=>window.ftlEngine.save());
 await back(page);await windowIs('game');assert.deepEqual(await progress(),afterMapOpen(original));
 pass('Jump pointer/hotkey warnings preserve crew, fuel and saved state; Yes opens map and Back keeps fullscreen');

 await back(page);await windowIs('PauseWindow');let rect=(await snapshot()).windowRect;
 await click(rect.x+50,rect.y+146);await windowIs('ConfirmationWindow');await answer(true);await windowIs('DialogueWindow');
 const restarted=await snapshot();assert.equal(restarted.ship,originalState.ship);assert.equal(restarted.ae,originalState.ae);assert.equal(restarted.difficulty,originalState.difficulty);
 let stats=await page.evaluate(()=>new DOMParser().parseFromString(window.ftlEngine.diagnosticProfile(),'application/xml').querySelector('browserStats').outerHTML);
 assert.match(stats,/<score\b/);await sameFullscreen();
 await setup();await back(page);await windowIs('PauseWindow');rect=(await snapshot()).windowRect;
 await click(rect.x+50,rect.y+109);await windowIs('ConfirmationWindow');await answer(true);await state('SelectShipState');
 assert.equal(await page.evaluate(()=>window.ftlEngine.runSave()),null);await sameFullscreen();
 pass('Confirmed Restart preserves ship/mode/difficulty and records score; confirmed Hangar removes Continue checkpoint');

 await setup();await back(page);await windowIs('PauseWindow');rect=(await snapshot()).windowRect;
 await click(rect.x+50,rect.y+72);await state('MainMenuState');
 const saved=await page.evaluate(()=>({run:window.ftlEngine.runSave(),profile:window.ftlEngine.diagnosticProfile()}));assert.ok(saved.run);
 await click(1180,353);await windowIs('ConfirmationWindow');assert.equal((await snapshot()).confirmation.message,'confirm_new_game');
 await answer(false);await windowIs('main');assert.deepEqual(await page.evaluate(()=>({run:window.ftlEngine.runSave(),profile:window.ftlEngine.diagnosticProfile()})),saved);await sameFullscreen();
 await click(1180,353);await windowIs('ConfirmationWindow');await back(page);await windowIs('main');
 assert.equal(await page.evaluate(()=>window.ftlEngine.runSave()),saved.run);await sameFullscreen();
 await click(1180,353);await windowIs('ConfirmationWindow');await page.screenshot({path:'.build/warning-new-game-'+name.toLowerCase()+'.png'});
 await answer(true);await state('SelectShipState');assert.equal(await page.evaluate(()=>window.ftlEngine.runSave()),null);await sameFullscreen();
 assert.equal(await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),saved.profile);
 pass('Main Menu saves current run; New Game Cancel/Back preserve Continue and Confirm clears it without changing profile');
 await setup();
}
