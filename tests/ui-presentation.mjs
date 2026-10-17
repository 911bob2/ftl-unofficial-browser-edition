import assert from 'node:assert/strict';

// Actual rendered pixels and pointer input, on the disposable Menus fixture.
// Expected colors/bounds come from desktop TextButton/WeaponControl references.
export async function presentationUiChecks(page,{snapshot,state,click,pass,name}) {
 const flush=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 const hover=async(x,y)=>{
  const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);
  await page.mouse.move(box.x+(box.width-1280*scale)/2+x*scale,box.y+(box.height-720*scale)/2+y*scale);await flush();
 };
 const pixel=async(x,y)=>{
  const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);
  const png=await page.screenshot({clip:{x:box.x+(box.width-1280*scale)/2+x*scale,y:box.y+(box.height-720*scale)/2+y*scale,width:2,height:2}});
  return page.evaluate(async bytes=>{
   const bitmap=await createImageBitmap(new Blob([Uint8Array.from(atob(bytes),c=>c.charCodeAt(0))],{type:'image/png'}));
   const canvas=new OffscreenCanvas(bitmap.width,bitmap.height),ctx=canvas.getContext('2d');ctx.drawImage(bitmap,0,0);bitmap.close();
   return [...ctx.getImageData(0,0,1,1).data].slice(0,3);
  },png.toString('base64'));
 };
 const color=async(x,y,expected,label)=>{
  const actual=await pixel(x,y);assert.ok(actual.every((c,i)=>Math.abs(c-expected[i])<=1),`${label}: ${actual} instead of ${expected}`);
 };
 const glyphRows=async(x,y,width,height,expected,label)=>{
  const box=await page.locator('#game').boundingBox(),scale=Math.min(box.width/1280,box.height/720);
  const png=await page.screenshot({clip:{x:box.x+(box.width-1280*scale)/2+x*scale,y:box.y+(box.height-720*scale)/2+y*scale,width:width*scale,height:height*scale}});
  const rows=await page.evaluate(async bytes=>{
   const bitmap=await createImageBitmap(new Blob([Uint8Array.from(atob(bytes),c=>c.charCodeAt(0))],{type:'image/png'}));
   const canvas=new OffscreenCanvas(bitmap.width,bitmap.height),ctx=canvas.getContext('2d');ctx.drawImage(bitmap,0,0);bitmap.close();
   const pixels=ctx.getImageData(0,0,canvas.width,canvas.height).data,ys=[];
   for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++){
    const i=(y*canvas.width+x)*4;if(pixels[i]<100&&pixels[i+1]<100&&pixels[i+2]<100){ys.push(y);break;}
   }
   return [Math.min(...ys),Math.max(...ys)];
  },png.toString('base64'));
  assert.ok(rows.every((v,i)=>Math.abs(v/scale-expected[i])<=1.2),`${label} glyph rows: ${rows.map(v=>v/scale)} instead of ${expected}`);
 };
 const capture=label=>page.locator('#game').screenshot({path:`.build/ui-${label}-${name.toLowerCase()}.png`});
 await hover(700,480);await color(1140,30,[235,245,229],'Start idle fill');
 await hover(1150,50);await color(1140,30,[255,230,94],'Start hover fill');
 await hover(700,480);await capture('hangar-fidelity');
 await glyphRows(19,117,85,20,[2,17],'Hangar SHIP native baseline');
 const crew=(await snapshot()).controls.find(c=>c.id==='crew0');
 assert.deepEqual({x:crew.x,y:crew.y,width:crew.width,height:crew.height},{x:70,y:585,width:100,height:20});
 await click(crew.x-1,crew.y+5);await flush();assert.equal((await snapshot()).customizing,false,'Crew art margin accepted a click');
 await click(crew.x+1,crew.y+5);await flush();assert.equal((await snapshot()).customizing,true,'Crew Customize inner button missed a click');
 await click(253,649);await flush();assert.equal((await snapshot()).customizing,false);
 await click(100,158);await hover(600,300);await flush();
 // ShipSelect: the type_button_frame strip sits at y 119 and its border rows at 120-121, above the 22px tabs at y 122.
 // Read the framebuffer itself: browser scaling of a page screenshot blends 2px rows.
 const border=await page.evaluate(({x,y})=>{window.renderFrame(0);const gl=window.ftlGL.gl,buf=new Uint8Array(4);gl.readPixels(x,720-y-1,1,1,gl.RGBA,gl.UNSIGNED_BYTE,buf);return [...buf].slice(0,3);},{x:720,y:120});
 assert.ok(border.every(c=>c<120),`Native Type tab frame is missing: ${border}`);
 await glyphRows(357,118,290,28,[3,26],'Grid title native baseline');
 await click(775,133);await flush();await capture('grid-fidelity');
 await click(1000,600);await flush();assert.equal((await snapshot()).shipList,false);
 pass('Native button pixels, heading/title glyph rows, crew Customize bounds and grid frame');

 const ordinary=await page.evaluate(()=>({profile:window.ftlEngine.diagnosticProfile(),run:window.ftlEngine.runSave(),ae:JSON.parse(window.ftlEngine.debugSnapshot()).ae,rng:JSON.parse(window.ftlEngine.debugSnapshot()).rng}));
 try {
  await page.evaluate(({profile,ae})=>{
   const doc=new DOMParser().parseFromString(profile,'application/xml'),layout=doc.createElement('layout');
   layout.setAttribute('ship','PLAYER_SHIP_STEALTH');layout.setAttribute('diff','NORMAL');doc.documentElement.append(layout);
   window.start(ae,null,new XMLSerializer().serializeToString(doc));
  },ordinary);await state('MainMenuState');
  // Four and three slots have an autofire control; the two-slot tutorial hull
  // is checked through the ordinary tutorial entry below.
  for(const [ship,count] of [['PLAYER_SHIP_HARD',4],['PLAYER_SHIP_STEALTH',3]]) {
   await page.evaluate(ship=>window.ftlEngine.newRun(ship,1),ship);await state('InGameState');
   // A new run's welcome dialog dims the HUD; dismiss its real Continue choice
   // before comparing native colors, then pause the disposable run.
   for(let i=0;i<5&&(await snapshot()).dialogue;i++) {
    await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).dialogue?.ready);
    const choice=(await snapshot()).dialogue.choices[0];await click(choice.x+8,choice.y+8);await flush();
   }
   assert.equal((await snapshot()).window,'game','Welcome dialog still covers the HUD');
   if(!(await snapshot()).playerPaused){await page.locator('#game').press('Space');await flush();}
   await page.waitForFunction(n=>JSON.parse(window.ftlEngine.debugSnapshot()).armaments.slots.length===n,count);
   let hud=await snapshot();assert.equal(hud.armaments.slots.length,count);
   for(const slot of hud.armaments.slots)assert.deepEqual({width:slot.width,height:slot.height},{width:87,height:39});
   for(let i=1;i<count;i++)assert.equal(hud.armaments.slots[i].x-hud.armaments.slots[i-1].x,97);
   const button=hud.armaments.autofire,first=hud.armaments.slots[0];
   assert.equal(button.height,24);assert.equal(button.x+button.width-first.x,count*97-4);assert.equal(button.y-first.y,47);
   if(hud.autofire){await click(button.x+button.width/2,button.y+12);await flush();}
   await hover(900,400);await color(button.x+button.width/2,button.y+2,[164,171,160],'Autofire off fill');await capture(`weapons-${count}-off`);
   await hover(button.x+button.width/2,button.y+12);await color(button.x+button.width/2,button.y+2,[209,213,208],'Autofire off hover');
   const tooltip=(await snapshot()).armaments.tooltip;
   assert.ok(tooltip&&!tooltip.includes('\\1')&&!tooltip.includes('\\2')&&!tooltip.includes('Tap twice'),'Desktop autofire tooltip bindings missing');
   await click(button.x-1,button.y+12);await flush();assert.equal((await snapshot()).autofire,false,'Autofire art margin accepted a click');
   await click(button.x+button.width/2,button.y+12);await flush();assert.equal((await snapshot()).autofire,true);
   await color(button.x+button.width/2,button.y+2,[255,243,175],'Autofire on hover');
   await hover(900,400);await color(button.x+button.width/2,button.y+2,[255,230,94],'Autofire on fill');await capture(`weapons-${count}-on`);
  }
  await page.evaluate(({profile,ae})=>window.start(ae,null,profile),ordinary);await state('MainMenuState');
  await click(1180,411);await state('InGameState');
  await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).armaments.slots.length===2);
  assert.equal((await snapshot()).armaments.autofire,null,'Two-slot tutorial shows an autofire button');
  await hover(900,400);await capture('weapons-2');
  pass('Native weapon slot dimensions/stride and autofire art, colors, hover, tooltip and pointer bounds across 2/3/4 slots');
 } catch(error) {
  await capture('presentation-failure');console.log(name+' presentation state:',await snapshot());throw error;
 } finally {
  await page.evaluate(({profile,run,ae,rng})=>{window.start(ae,run,profile);window.diagnosticSeed(rng);},ordinary);
  await state(ordinary.run?'InGameState':'MainMenuState');
  if(!ordinary.run){await click(1180,353);await state('SelectShipState');}
 }
 assert.equal(await page.evaluate(()=>window.ftlEngine.diagnosticProfile()),ordinary.profile,'Presentation fixture changed progression');
 assert.equal((await snapshot()).rng,ordinary.rng,'Presentation fixture changed gameplay RNG');
}
