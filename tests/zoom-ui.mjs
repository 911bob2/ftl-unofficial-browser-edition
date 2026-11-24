import assert from 'node:assert/strict';

// Pinch to zoom on the website (web/zoom.mjs, the game bar's setting; docs/WEBSITE.md#pinch-to-zoom), from the main
// menu, where a press on Options (1180, 527) opens it at once: off by default and then nothing on the canvas changes;
// zoomed, a click and a tap reach the game point under them and no longer the 1x one; a pinch's fingers and the
// minimap's never reach the game; the minimap's 1x, its map and its bar; a held finger reaches the game after the hold;
// the setting survives a reload. Touch is real touch input in Chromium (DevTools touch events); Firefox checks the
// mouse and Ctrl+wheel side. It ends on the main menu with the setting off again.
const OPTIONS=[1180,527];
const frames=page=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
const zoomState=page=>page.evaluate(()=>window.ftlZoom?.state()??null);
/** The page point of game point (x, y): the canvas's box is the zoomed one, as game.mjs maps pointers. */
const pageAt=(page,[x,y])=>page.evaluate(([x,y])=>{const r=document.getElementById('game').getBoundingClientRect(),s=Math.min(r.width/1280,r.height/720);return [r.left+(r.width-1280*s)/2+x*s,r.top+(r.height-720*s)/2+y*s];},[x,y]);
/** The game point under page point (x, y). */
const gameAt=(page,[x,y])=>page.evaluate(([x,y])=>{const r=document.getElementById('game').getBoundingClientRect(),s=Math.min(r.width/1280,r.height/720);return [(x-r.left-(r.width-1280*s)/2)/s,(y-r.top-(r.height-720*s)/2)/s];},[x,y]);
// The canvas's own style (the game sets its cursor there too, so only what the zoom would set) and the container's.
/** Ctrl and the wheel at page point (x, y), as a mouse wheel's notches or a trackpad's pinch send it. */
const ctrlWheel=(page,[x,y],deltaY)=>page.evaluate(([x,y,deltaY])=>document.getElementById('game').dispatchEvent(new WheelEvent('wheel',{clientX:x,clientY:y,deltaY,deltaMode:0,ctrlKey:true,bubbles:true,cancelable:true})),[x,y,deltaY]);
const canvasStyle=page=>page.evaluate(()=>({style:['transform','transformOrigin','touchAction'].map(k=>document.getElementById('game').style[k]).join('|'),zoomed:document.getElementById('game-container').classList.contains('zoomed'),minimap:document.querySelectorAll('.zoom-minimap').length}));

export async function zoomUiChecks(page,{snapshot,state,windowIs,pass,name}){
 const engineState=async()=>{const s=await snapshot();return s.state+'/'+s.window;};
 const settle=async(ms=250)=>{await page.waitForTimeout(ms);await frames(page);};
 const optionsOpen=async()=>{await state('SettingsMenuState');await windowIs('OptionsWindow');await page.locator('#game').press('Escape');await state('MainMenuState');await windowIs('main');};
 await state('MainMenuState');await windowIs('main');

 // Off by default: no zoom object, the canvas as before, Ctrl+wheel is the game's.
 assert.equal(await page.locator('#zoom-enabled').isChecked(),false,'Pinch to zoom starts off');
 assert.ok(await page.locator('#zoom-options').isHidden(),'The Minimap options wait for the setting');
 assert.equal(await page.evaluate(()=>localStorage.getItem('ftl-zoom')),null);
 assert.equal(await page.evaluate(()=>window.ftlZoom??null),null,'No zoom while it is off');
 const off=await canvasStyle(page);
 assert.deepEqual(off,{style:'||',zoomed:false,minimap:0},'Off: nothing on the canvas changes');
 const [ox,oy]=await pageAt(page,OPTIONS);
 await ctrlWheel(page,[ox-200,oy],-400);await settle();
 assert.deepEqual(await canvasStyle(page),off,'Off: Ctrl+wheel does not zoom');
 pass('Pinch to zoom is off by default and then the canvas and its input are as before');

 // On: the browser's own touch gestures leave the canvas; nothing is zoomed yet.
 await page.locator('#zoom-enabled').check();
 assert.ok(await page.locator('#zoom-options').isVisible());
 let z=await zoomState(page);
 assert.ok(z&&z.scale===1&&z.minimap===null,'On, at 1x, no minimap: '+JSON.stringify(z));
 assert.equal(await page.evaluate(()=>document.getElementById('game').style.touchAction),'none');
 assert.deepEqual(JSON.parse(await page.evaluate(()=>localStorage.getItem('ftl-zoom'))),{on:true,minimap:true,size:'medium',x:1,y:0});

 // Ctrl+wheel zooms on the pointer: the game point under it stays.
 const before=await gameAt(page,[ox-200,oy]);
 await ctrlWheel(page,[ox-200,oy],-400);await settle();
 z=await zoomState(page);
 assert.ok(z.scale>1.5&&z.scale<=4,'Ctrl+wheel zoomed: '+z.scale);
 const after=await gameAt(page,[ox-200,oy]);
 assert.ok(Math.abs(after[0]-before[0])<1.5&&Math.abs(after[1]-before[1])<1.5,'The point under the pointer stays: '+JSON.stringify([before,after]));
 assert.ok(z.minimap&&z.minimap.box.w>0,'The minimap shows while zoomed');
 await page.locator('.zoom-reset').click();await settle();
 assert.equal((await zoomState(page)).scale,1,'Its 1x button goes back to 1x');
 assert.equal((await zoomState(page)).minimap,null);
 pass('Ctrl+wheel zooms on the pointer and the minimap\'s 1x goes back');

 // Zoomed on (1100, 500) at 2.5x: Options moved on the page; a click on its new place opens it, its 1x place does not.
 await page.evaluate(()=>window.ftlZoom.zoomOn(2.5,1100,500));await settle();
 const moved=await pageAt(page,OPTIONS);
 assert.ok(Math.hypot(moved[0]-ox,moved[1]-oy)>100,'Options moved on the page: '+JSON.stringify([[ox,oy],moved]));
 await page.mouse.click(ox,oy);await settle(400);
 assert.equal(await engineState(),'MainMenuState/main','A click on Options\' 1x place while zoomed reached it');
 await page.mouse.click(...moved);await optionsOpen();
 pass('Zoomed, a click reaches the game point under it');

 // The minimap: a click on its map's top left corner shows the picture's top left; its bar moves it, and the place is kept.
 z=await zoomState(page);
 await page.mouse.click(z.minimap.map.x+2,z.minimap.map.y+2);await settle();
 let s=await zoomState(page);
 assert.ok(s.panX===0&&s.panY===0,'The map\'s corner shows the picture\'s corner: '+JSON.stringify([s.panX,s.panY]));
 assert.equal(await engineState(),'MainMenuState/main','A click on the minimap reached the game');
 const grip=[s.minimap.bar.x+s.minimap.bar.w*0.3,s.minimap.bar.y+s.minimap.bar.h/2];
 await page.mouse.move(...grip);await page.mouse.down();
 for(let i=1;i<=8;i++)await page.mouse.move(grip[0]-i*40,grip[1]+i*25);
 await page.mouse.up();await settle();
 const dragged=await zoomState(page);
 assert.ok(Math.abs(dragged.minimap.box.x-(s.minimap.box.x-320))<2&&Math.abs(dragged.minimap.box.y-(s.minimap.box.y+200))<2,'Dragged by its bar: '+JSON.stringify([s.minimap.box,dragged.minimap.box]));
 const kept=JSON.parse(await page.evaluate(()=>localStorage.getItem('ftl-zoom')));
 assert.ok(kept.x<1&&kept.y>0,'The place is kept: '+JSON.stringify(kept));
 assert.equal(await engineState(),'MainMenuState/main');
 // Size and the minimap switch from the bar's options.
 await page.locator('#zoom-options > summary').click();
 await page.locator('#zoom-size').selectOption('large');await settle();
 assert.ok((await zoomState(page)).minimap.map.h>dragged.minimap.map.h*1.2,'Large is larger');
 await page.locator('#zoom-minimap').uncheck();await settle();
 assert.equal((await zoomState(page)).minimap,null,'Switched off, no minimap while zoomed');
 await page.locator('#zoom-minimap').check();await page.locator('#zoom-place').click();await settle();
 s=await zoomState(page);
 assert.deepEqual(s.place,[1,0],'Put back top right');
 await page.locator('#zoom-size').selectOption('medium');
 await page.locator('#zoom-options > summary').click();
 await page.locator('.zoom-reset').click();await settle();
 pass('The minimap: its map moves the view, its bar moves it (kept), its size and switch, and its fingers stay off the game');

 if(name==='Chromium'){
  // Real touch input (DevTools): what reaches the game, finger by finger.
  const cdp=await page.context().newCDPSession(page);
  await cdp.send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
  const touch=(type,points)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:points.map(([x,y],i)=>({x:Math.round(x),y:Math.round(y),id:i+1}))});
  try{
   // A pinch with its first finger on Options: neither finger reaches the game, and it zooms.
   const [ax,ay]=await pageAt(page,OPTIONS);
   await touch('touchStart',[[ax,ay]]);await page.waitForTimeout(30);
   await touch('touchStart',[[ax,ay],[ax,ay+100]]);
   for(let i=1;i<=10;i++){await touch('touchMove',[[ax,ay-i*10],[ax,ay+100+i*10]]);await page.waitForTimeout(16);}
   await touch('touchEnd',[]);await settle();
   z=await zoomState(page);
   assert.ok(z.scale>2.6&&z.scale<=3.01,'The pinch zoomed about 3x: '+z.scale);
   assert.equal(z.mode,'idle');
   assert.equal(await engineState(),'MainMenuState/main','A pinch begun on Options pressed it');
   // A zoomed tap on Options' new place opens it.
   const tapAt=await pageAt(page,OPTIONS);
   await touch('touchStart',[tapAt]);await page.waitForTimeout(50);await touch('touchEnd',[]);
   await optionsOpen();
   // Two fingers tapped together: back to 1x; again: 2x there.
   await touch('touchStart',[[400,500]]);await page.waitForTimeout(20);await touch('touchStart',[[400,500],[480,500]]);await page.waitForTimeout(60);await touch('touchEnd',[]);await settle();
   assert.equal((await zoomState(page)).scale,1,'A two-finger tap goes back to 1x');
   await touch('touchStart',[[400,500]]);await page.waitForTimeout(20);await touch('touchStart',[[400,500],[480,500]]);await page.waitForTimeout(60);await touch('touchEnd',[]);await settle();
   assert.equal((await zoomState(page)).scale,2,'At 1x a two-finger tap zooms 2x');
   assert.equal(await engineState(),'MainMenuState/main');
   await page.locator('.zoom-reset').click();await settle();
   // A held finger reaches the game after the hold, before it lifts: the press opens Options.
   await touch('touchStart',[await pageAt(page,OPTIONS)]);
   await state('SettingsMenuState');
   await touch('touchEnd',[]);
   await windowIs('OptionsWindow');await page.locator('#game').press('Escape');await state('MainMenuState');await windowIs('main');
   pass('Touch: a pinch\'s fingers never reach the game, a zoomed tap hits the control under it, two-finger taps and a held finger');
  }finally{await cdp.send('Emulation.setTouchEmulationEnabled',{enabled:false});await cdp.detach();}
 }

 // The setting is kept: the next launch has it on, and off removes every trace from the canvas.
 await page.reload();await page.locator('#launch').click();
 await page.waitForFunction(()=>!!window.ftlEngine,{},{timeout:90000});
 await state('MainMenuState');
 if((await snapshot()).window==='WelcomeChoiceBox'){await page.locator('#game').press('1');await windowIs('main');}
 assert.equal(await page.locator('#zoom-enabled').isChecked(),true,'Kept on through a reload');
 assert.ok(await page.evaluate(()=>!!window.ftlZoom&&document.getElementById('game').style.touchAction==='none'),'The next game starts with it');
 await page.evaluate(()=>window.ftlZoom.zoomOn(2,640,360));await settle();
 await page.locator('#zoom-enabled').uncheck();await settle();
 assert.equal(await page.evaluate(()=>window.ftlZoom??null),null);
 assert.deepEqual(await canvasStyle(page),off,'Off again: the canvas is as before');
 assert.equal(JSON.parse(await page.evaluate(()=>localStorage.getItem('ftl-zoom'))).on,false);
 pass('Pinch to zoom is kept through a reload, and switching it off restores the canvas');
}
