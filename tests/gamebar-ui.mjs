import assert from 'node:assert/strict';

// The website's Back and Pause buttons are gone. Where a test used them it now sends the engine's semantic Back, the
// call the Escape key reaches (InGameState.cancelMenu or the menu state's Escape): it closes the open window or, in
// the flight with none open, opens the pause window. A browser keeps the physical Escape for leaving fullscreen, so
// the physical key is exercised windowed, in escapeExitUiChecks below.
// Two frames first: typed keys wait in the engine's input queue and the real Escape key would queue behind them.
export const back=page=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))
 ).then(()=>page.evaluate(()=>{window.ftlEngine.menuCancel();document.getElementById('game').focus();}));
// Pressing Fullscreen while fullscreen now leaves it, so tests only press it when they are not there yet.
export async function enterFullscreen(page){
 if(!await page.evaluate(()=>!!document.fullscreenElement))await page.locator('#fullscreen').click();
 await page.waitForFunction(()=>document.fullscreenElement?.id==='game-container');
}
export async function leaveFullscreen(page){
 if(await page.evaluate(()=>!!document.fullscreenElement))await page.evaluate(()=>document.exitFullscreen());
 await page.waitForFunction(()=>!document.fullscreenElement&&document.getElementById('fullscreen').textContent==='Fullscreen');
}
const frames=page=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
const geometry=page=>page.evaluate(()=>{
 const bar=document.querySelector('.gamebar').getBoundingClientRect(),canvas=document.getElementById('game').getBoundingClientRect();
 return {barTop:bar.top,barBottom:bar.bottom,canvasTop:canvas.top,canvasHeight:canvas.height,canvasWidth:canvas.width,width:innerWidth,height:innerHeight};
});

/** The game bar: removed buttons, the Fullscreen toggle and Hide header (menu state, no run needed). */
export async function gameBarUiChecks(page,{pass,name}){
 await leaveFullscreen(page);
 for(const id of ['menu-back','pause','export','debug-export'])assert.equal(await page.locator('#'+id).count(),0,'#'+id+' must be gone');
 for(const id of ['console','save','fullscreen','save-close','debug-mark'])assert.ok(await page.locator('#'+id).isVisible(),'#'+id+' missing from the bar');
 assert.equal(await page.locator('#save-close').textContent(),'Save and close');
 assert.ok(await page.locator('#hide-header').isHidden(),'Hide header is only for fullscreen');
 // Exporting a save moved out of the bar: the Saves panel keeps the cloud download and the import, and the pause panel the pending-progress download.
 assert.equal(await page.locator('#saves-panel #backup-download').count(),1);assert.equal(await page.locator('#saves-panel #backup-import').count(),1);assert.equal(await page.locator('#recovery-export').count(),1);
 assert.equal(await page.locator('#fullscreen').textContent(),'Fullscreen');
 pass('Game bar has no Back/Pause/Export, Save and close replaces Export and Hide header waits for fullscreen');

 // F1: the button toggles and its label follows the real fullscreen state, whoever changes it.
 await page.locator('#fullscreen').click();
 await page.waitForFunction(()=>document.fullscreenElement?.id==='game-container'&&document.getElementById('fullscreen').textContent==='Exit fullscreen');
 assert.ok(await page.locator('#hide-header').isVisible(),'Hide header must show in fullscreen');
 await page.locator('#fullscreen').click();
 await page.waitForFunction(()=>!document.fullscreenElement&&document.getElementById('fullscreen').textContent==='Fullscreen');
 assert.ok(await page.locator('#hide-header').isHidden());
 await page.locator('#fullscreen').click();await page.waitForFunction(()=>document.fullscreenElement?.id==='game-container');
 await page.evaluate(()=>document.exitFullscreen());
 await page.waitForFunction(()=>!document.fullscreenElement&&document.getElementById('fullscreen').textContent==='Fullscreen'&&document.getElementById('hide-header').hidden);
 assert.equal(await page.evaluate(()=>document.activeElement?.id),'game','Focus returns to the game after the toggle');
 pass('Fullscreen toggles on press and keeps its label in step when the browser leaves fullscreen');

 // T5: Hide header hides the bar so the canvas fills the screen; the top edge brings it back temporarily.
 await enterFullscreen(page);await frames(page);
 let g=await geometry(page);
 assert.ok(g.barBottom>0&&g.canvasTop>=g.barBottom-1&&g.canvasHeight<g.height,'The header takes room above the canvas in fullscreen');
 await page.locator('#hide-header').click();
 await page.waitForFunction(()=>document.querySelector('.gamebar').getBoundingClientRect().bottom<=0);await frames(page);
 g=await geometry(page);
 assert.equal(g.canvasTop,0);assert.equal(Math.round(g.canvasHeight),g.height,'The canvas fills the screen without the header');
 assert.ok(await page.locator('#hide-header').isHidden()||await page.locator('#show-header').count()===1);
 await page.mouse.move(g.width/2,g.height/2);await page.mouse.move(g.width/2,1);
 await page.waitForFunction(()=>document.querySelector('.gamebar').getBoundingClientRect().bottom>20);
 g=await geometry(page);assert.equal(g.canvasTop,0,'The revealed header floats over the canvas without resizing it');
 await page.mouse.move(g.width/2,g.height/2);
 await page.waitForFunction(()=>document.querySelector('.gamebar').getBoundingClientRect().bottom<=0);
 await page.evaluate(()=>document.exitFullscreen());
 await page.waitForFunction(()=>!document.fullscreenElement&&!document.getElementById('game-container').classList.contains('header-hidden'));
 g=await geometry(page);assert.ok(g.barBottom>0&&g.barTop>=0,'The header returns when fullscreen ends');
 await enterFullscreen(page);await frames(page);g=await geometry(page);
 assert.ok(g.barBottom>0,'A new fullscreen starts with the header shown');
 await page.locator('#hide-header').click();await page.waitForFunction(()=>document.querySelector('.gamebar').getBoundingClientRect().bottom<=0);
 await page.locator('#fullscreen').evaluate(button=>button.focus());
 await page.waitForFunction(()=>document.querySelector('.gamebar').getBoundingClientRect().bottom>0);
 await leaveFullscreen(page);
 pass('Hide header gives the canvas the whole screen, the top edge or keyboard focus reveals it and it returns with the window');
}

/** F2: the Console button closes an open console, and the game keeps the keyboard. Needs a run. */
export async function consoleToggleUiCheck(page,{pass,name}){
 const open=()=>page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()).console===true);
 const wait=value=>page.waitForFunction(v=>(JSON.parse(window.ftlEngine.debugSnapshot()).console===true)===v,value);
 await leaveFullscreen(page);
 if(await open()){await page.locator('#console').click();await wait(false);}
 await page.locator('#console').click();await wait(true);
 assert.equal(await page.evaluate(()=>document.activeElement?.id),'game');
 await page.locator('#console').click();await wait(false);
 assert.equal(await page.evaluate(()=>document.activeElement?.id),'game','Focus returns to the game when the console closes');
 await page.locator('#game').press('Backquote');await wait(true);
 await page.locator('#console').click();await wait(false);
 await page.locator('#console').click();await wait(true);
 await page.locator('#game').press('Backquote');await wait(false);
 assert.equal(await open(),false);
 pass('Console button opens and closes the console, also against the keyboard shortcut');
}

/**
 * Every screen can be left with the physical Escape key (or its own control), so removing the website Back button
 * strands nobody. Run windowed: a browser keeps Escape for leaving fullscreen first.
 */
export async function escapeExitUiChecks(page,{snapshot,state,windowIs,click,pass,name}){
 await leaveFullscreen(page);
 const escape=()=>page.locator('#game').press('Escape');
 await state('MainMenuState');
 await click(1180,527);await state('SettingsMenuState');await windowIs('OptionsWindow');
 await escape();await state('MainMenuState');
 await click(1180,469);await state('BrowserStatisticsState');
 await escape();await state('MainMenuState');
 await click(1180,585);await state('CreditsState');
 await escape();await state('MainMenuState');
 if(await page.evaluate(()=>!!window.ftlEngine.runSave())){
  // The New Game warning is left with Escape too, and that keeps the saved run.
  await click(1180,353);await windowIs('ConfirmationWindow');await escape();await windowIs('main');await state('MainMenuState');
  assert.ok(await page.evaluate(()=>!!window.ftlEngine.runSave()),'Cancelling New Game keeps the run');
 }
 pass('Options, Statistics, Credits and the New Game warning leave with the physical Escape key');
}

/** The hangar (SelectShipState) leaves with Escape and can be entered again. Needs no saved run to confirm over. */
export async function hangarEscapeCheck(page,{click,state,pass}){
 await leaveFullscreen(page);
 await state('SelectShipState');
 await page.locator('#game').press('Escape');await state('MainMenuState');
 await click(1180,353);await state('SelectShipState');
 pass('The hangar leaves with the physical Escape key and opens again');
}

/**
 * Save and close, cloud path: a save that cannot be written keeps the game running and says why; once it can be
 * written the whole run reaches the cloud before the game stops, the play lease is handed back so the account can
 * launch again at once (no takeover prompt), and the next launch continues exactly that run.
 */
export async function saveCloseUiChecks(page,{snapshot,state,click,pass}){
 await leaveFullscreen(page);
 await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));await state('InGameState');
 await page.evaluate(()=>window.ftlEngine.consoleExecute('rich'));
 await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).scrap===5000);
 await page.route('**/api/saves/current',route=>route.request().method()==='PUT'?route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({error:'Storage is full'})}):route.continue());
 await page.locator('#save-close').click();
 await page.waitForFunction(()=>!document.getElementById('connection-panel').hidden&&/^Not saved/.test(document.getElementById('save-status').textContent));
 assert.ok(await page.evaluate(()=>!!window.ftlEngine),'A failed save must not stop the game');
 assert.equal((await snapshot()).state,'InGameState');
 assert.equal((await snapshot()).scrap,5000,'A failed save loses nothing');
 await page.waitForFunction(()=>!document.getElementById('save-close').disabled&&document.getElementById('save-close').textContent==='Save and close');
 await page.unroute('**/api/saves/current');
 // The failure paused the flight for reconnection (existing machinery); reconnect, then close for real.
 await page.locator('#reconnect').click();
 await page.waitForFunction(()=>document.getElementById('connection-panel').hidden);
 const reloaded=page.waitForEvent('framenavigated',{timeout:60000});
 await page.locator('#save-close').click();await reloaded;
 await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
 const cloud=await page.evaluate(async()=>(await fetch('/api/saves/current')).json());
 assert.ok(cloud.run&&cloud.run.length>1000,'The run is in the cloud save');
 await page.locator('#launch').click();
 await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden,{},{timeout:90000});
 assert.ok(await page.evaluate(()=>!!window.ftlEngine),'Launching right after Save and close must not ask to take over the lease');
 await state('MainMenuState');
 assert.ok(await page.evaluate(()=>!!window.ftlEngine.runSave()),'The launch loads the cloud run');
 await click(1180,295);await state('InGameState');
 assert.equal((await snapshot()).scrap,5000,'Progress made just before closing is in the continued run');
 pass('Save and close keeps the game on a failed save, then writes the run, hands the lease back and the next launch continues it');
}
