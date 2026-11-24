// Pinch to zoom (off by default, a setting of this browser, like the debug console's): two fingers pinch the game's
// picture larger, up to MAX times, and move it; a minimap shows the whole picture with the part in view framed. The
// Android app's twin is android/.../game/Zoom.kt (same rules and numbers; docs/ANDROID.md#pinch-to-zoom).
//
// It changes no rule of the game. The canvas is scaled with a CSS transform inside the game container, so the
// canvas's bounding box, which game.mjs maps every pointer through, is the zoomed one: a pointer reaches the game at
// the point under it. Touch pointers: the first is held back until it moves SLOP, is held HOLD_MS or lifts, then the
// game gets it with all it did, in order; every later pointer goes to the game as before, until all are up. A second
// finger while the first is held back makes a pinch, and the game gets neither. Two fingers tapped together go back
// to 1x (at 1x: zoom TAP_ZOOM there). A pinch ending below SNAP is 1x. A desktop browser zooms with Ctrl and the wheel,
// which is also what a trackpad's pinch sends. With the setting off there is no zoom object at all (game.mjs).
const KEY='ftl-zoom';
export const MAX=4,TAP_ZOOM=2,SNAP=1.1,HOLD_MS=150,TAP_MS=300,SLOP=2.5,BAR=7,MARGIN=2,RESET_W=1.4;
export const SIZES={small:['Small',22],medium:['Medium',30],large:['Large',38]};
export const DEFAULTS={on:false,minimap:true,size:'medium',x:1,y:0};
const clamp=(v,lo,hi)=>Math.min(hi,Math.max(lo,v));

export function readZoomSettings(){
  try{const s=JSON.parse(localStorage.getItem(KEY)??'null');if(s&&typeof s==='object')return {on:s.on===true,minimap:s.minimap!==false,size:SIZES[s.size]?s.size:'medium',x:Number.isFinite(s.x)?clamp(s.x,0,1):1,y:Number.isFinite(s.y)?clamp(s.y,0,1):0};}catch{}
  return {...DEFAULTS};
}
export function writeZoomSettings(s){try{localStorage.setItem(KEY,JSON.stringify(s));}catch{/* this visit only */}}

/**
 * The zoom of a running game. canvas: the game's; container: what clips it; toGame(kind,e,[x,y]): pushes a held-back
 * pointer event to the game as game.mjs's own listener would ('down', 'move', 'up'); position(e): game.mjs's mapping.
 */
export function createZoom({canvas,container,position,toGame,settings}){
  let scale=1,panX=0,panY=0,mode='idle',first=null,held=[],pinch=null,mapGrab=null;
  const down=new Map();
  const box=()=>({w:canvas.clientWidth||1,h:canvas.clientHeight||1});
  // The canvas's untransformed corner in the page.
  const origin=()=>{const r=canvas.getBoundingClientRect();return {x:r.left-panX,y:r.top-panY};};
  const local=e=>{const o=origin();return [e.clientX-o.x,e.clientY-o.y];};
  const unit=()=>box().h/100;
  const unzoom=(x,y)=>[(x-panX)/scale,(y-panY)/scale];
  function apply(){
    const on=scale>1;
    canvas.style.transformOrigin=on?'0 0':'';
    canvas.style.transform=on?`translate(${panX}px,${panY}px) scale(${scale})`:'';
    container.classList.toggle('zoomed',on);
    layoutMinimap();
  }
  function set(to,ux,uy,vx,vy){
    const {w,h}=box();scale=clamp(to,1,MAX);
    panX=clamp(vx-ux*scale,w-scale*w,0);panY=clamp(vy-uy*scale,h-scale*h,0);
    if(scale===1)panX=panY=0;apply();
  }
  function reset(){scale=1;panX=panY=0;apply();}
  const lookAt=(ux,uy)=>{const {w,h}=box();set(scale,ux,uy,w/2,h/2);};

  // ---- the minimap: a bar to drag it by with a 1x button, the whole picture, the part in view framed ----
  const map=document.createElement('div');map.className='zoom-minimap';map.hidden=true;
  map.innerHTML='<div class="zoom-bar"><span class="zoom-grip" aria-hidden="true"></span><button type="button" class="zoom-reset" aria-label="Back to 1x">1×</button></div><canvas class="zoom-map" aria-hidden="true"></canvas><span class="zoom-seen" aria-hidden="true"></span>';
  container.append(map);
  const bar=map.querySelector('.zoom-bar'),reset1x=map.querySelector('.zoom-reset'),mapCanvas=map.querySelector('.zoom-map'),seen=map.querySelector('.zoom-seen'),ctx=mapCanvas.getContext('2d');
  // The bar is on the side away from the nearer edge (below the map in the top half), so a drag of it never starts at
  // the screen's edge, where a phone's system takes a swipe; it changes side when a drag ends.
  let placeX=settings.x,placeY=settings.y,barBelow=placeY<0.5;
  // Where the game's 1280x720 picture lies in the canvas box (object-fit: contain), unzoomed.
  const picture=()=>{const {w,h}=box(),s=Math.min(w/1280,h/720);return {s,left:(w-1280*s)/2,top:(h-720*s)/2};};
  function minimapRect(){
    const {w,h}=box(),u=h/100,mh=u*SIZES[settings.size][1],mw=mh*16/9,bh=u*BAR,m=u*MARGIN;
    const roomX=Math.max(0,w-2*m-mw),roomY=Math.max(0,h-2*m-mh-bh);
    return {left:m+placeX*roomX,top:m+placeY*roomY,w:mw,mh,bh,m,roomX,roomY};
  }
  function layoutMinimap(){
    const show=scale>1&&settings.minimap;map.hidden=!show;if(!show)return;
    // In the container: the canvas's untransformed place in it plus the box's own place.
    const c=container.getBoundingClientRect(),o=origin(),r=minimapRect();
    map.classList.toggle('bar-below',barBelow);
    Object.assign(map.style,{left:(o.x-c.left+r.left)+'px',top:(o.y-c.top+r.top)+'px',width:r.w+'px'});
    bar.style.height=r.bh+'px';reset1x.style.width=(r.bh*RESET_W)+'px';mapCanvas.style.height=r.mh+'px';
    const ratio=devicePixelRatio||1,cw=Math.round(r.w*ratio),ch=Math.round(r.mh*ratio);
    if(mapCanvas.width!==cw||mapCanvas.height!==ch){mapCanvas.width=cw;mapCanvas.height=ch;}
    // The part in view, in game units, then in the map.
    const p=picture(),{w,h}=box(),[x0,y0]=unzoom(0,0),[x1,y1]=unzoom(w,h);
    const gx0=clamp((x0-p.left)/p.s,0,1280),gx1=clamp((x1-p.left)/p.s,0,1280),gy0=clamp((y0-p.top)/p.s,0,720),gy1=clamp((y1-p.top)/p.s,0,720);
    Object.assign(seen.style,{left:(gx0/1280*r.w)+'px',top:((barBelow?0:r.bh)+gy0/720*r.mh)+'px',width:((gx1-gx0)/1280*r.w)+'px',height:((gy1-gy0)/720*r.mh)+'px'});
  }
  // The view's centre on the map's point.
  function mapLook(e){const rr=mapCanvas.getBoundingClientRect(),p=picture();lookAt(clamp((e.clientX-rr.left)/rr.width,0,1)*1280*p.s+p.left,clamp((e.clientY-rr.top)/rr.height,0,1)*720*p.s+p.top);}
  const stop=e=>{e.preventDefault();e.stopPropagation();};
  map.addEventListener('pointerdown',e=>{
    stop(e);map.setPointerCapture?.(e.pointerId);
    const part=e.target===reset1x?'reset':bar.contains(e.target)?'bar':'map';
    const r=minimapRect();mapGrab={id:e.pointerId,part,x:e.clientX,y:e.clientY,left:r.left,top:r.top};
    if(part==='map')mapLook(e);
  });
  map.addEventListener('pointermove',e=>{
    if(!mapGrab||e.pointerId!==mapGrab.id)return;stop(e);
    if(mapGrab.part==='map')return mapLook(e);
    if(mapGrab.part==='reset'&&Math.hypot(e.clientX-mapGrab.x,e.clientY-mapGrab.y)<=SLOP*unit())return;
    mapGrab.part='bar';const r=minimapRect();
    placeX=r.roomX>0?clamp((mapGrab.left+e.clientX-mapGrab.x-r.m)/r.roomX,0,1):0;
    placeY=r.roomY>0?clamp((mapGrab.top+e.clientY-mapGrab.y-r.m)/r.roomY,0,1):0;layoutMinimap();
  });
  const mapEnd=e=>{
    if(!mapGrab||e.pointerId!==mapGrab.id)return;stop(e);
    if(mapGrab.part==='reset'&&e.type==='pointerup')reset();
    if(mapGrab.part==='bar'){barBelow=placeY<0.5;layoutMinimap();settings.x=placeX;settings.y=placeY;writeZoomSettings({...readZoomSettings(),x:placeX,y:placeY});}
    mapGrab=null;canvas.focus({preventScroll:true});
  };
  map.addEventListener('pointerup',mapEnd);map.addEventListener('pointercancel',mapEnd);
  for(const name of ['click','wheel','contextmenu'])map.addEventListener(name,stop,{passive:false});

  // ---- touch pointers on the canvas ----
  const pass=()=>{mode='pass';for(const [kind,e,at] of held)toGame(kind,e,at);held=[];if(!down.size)mode='idle';};
  function startPinch(e){
    held=[];mode='pinch';const a=down.get(first.id),b=down.get(e.pointerId),d=Math.hypot(b[0]-a[0],b[1]-a[1])||1,[ux,uy]=unzoom((a[0]+b[0])/2,(a[1]+b[1])/2);
    pinch={a:first.id,b:e.pointerId,d,scale,ux,uy,tap:true,at:Math.min(first.t,e.timeStamp),sa:[...a],sb:[...b]};
  }
  function movePinch(){
    const a=down.get(pinch.a),b=down.get(pinch.b),slop=SLOP*unit();if(!a||!b)return;
    if(pinch.tap&&(Math.hypot(a[0]-pinch.sa[0],a[1]-pinch.sa[1])>slop||Math.hypot(b[0]-pinch.sb[0],b[1]-pinch.sb[1])>slop))pinch.tap=false;
    if(!pinch.tap)set(pinch.scale*Math.hypot(b[0]-a[0],b[1]-a[1])/pinch.d,pinch.ux,pinch.uy,(a[0]+b[0])/2,(a[1]+b[1])/2);
  }
  function endPinch(tapped){
    if(tapped){if(scale>1)reset();else{const mx=(pinch.sa[0]+pinch.sb[0])/2,my=(pinch.sa[1]+pinch.sb[1])/2;set(TAP_ZOOM,...unzoom(mx,my),mx,my);}}
    else if(scale<SNAP)reset();
    pinch=null;
  }
  /** A pointer event on the canvas (pointerdown, pointermove, pointerup, pointercancel): true when the game must not see it now. */
  function take(e){
    if(e.pointerType!=='touch')return false;
    const at=local(e);
    if(e.type==='pointerdown'){
      down.set(e.pointerId,at);
      if(mode==='idle'){mode='held';first={id:e.pointerId,x:at[0],y:at[1],t:e.timeStamp};held=[['down',e,position(e)]];try{canvas.setPointerCapture(e.pointerId);}catch{}return true;}
      if(mode==='held'){startPinch(e);return true;}
      return mode!=='pass';
    }
    if(!down.has(e.pointerId))return mode!=='pass'&&mode!=='idle';
    if(e.type==='pointermove'){
      down.set(e.pointerId,at);
      if(mode==='held'){if(e.pointerId===first.id){held.push(['move',e,position(e)]);if(Math.hypot(at[0]-first.x,at[1]-first.y)>SLOP*unit())pass();}return true;}
      if(mode==='pinch'){if(e.pointerId===pinch.a||e.pointerId===pinch.b)movePinch();return true;}
      return mode!=='pass';
    }
    // pointerup, pointercancel
    down.delete(e.pointerId);let mine=true;
    if(mode==='held'&&e.pointerId===first.id){if(e.type==='pointerup'){held.push(['up',e,position(e)]);pass();}else held=[];}
    else if(mode==='pass')mine=false;
    else if(mode==='pinch'&&(e.pointerId===pinch.a||e.pointerId===pinch.b)){endPinch(e.type==='pointerup'&&pinch.tap&&e.timeStamp-pinch.at<=TAP_MS);mode='done';}
    if(!down.size){mode='idle';held=[];first=null;}
    return mine;
  }
  /** Each frame (performance.now's clock): a first finger held back HOLD_MS goes to the game; the minimap's picture. */
  function frame(now){
    if(mode==='held'&&now-first.t>=HOLD_MS)pass();
    if(!map.hidden){const r=mapCanvas;ctx.imageSmoothingEnabled=true;ctx.drawImage(canvas,0,0,canvas.width,canvas.height,0,0,r.width,r.height);}
  }
  /** Ctrl and the wheel, or a trackpad's pinch: zoom on the pointer. */
  function wheel(e){
    const px=e.deltaY*(e.deltaMode===1?40:e.deltaMode===2?box().h:1),[x,y]=local(e),[ux,uy]=unzoom(x,y);
    set(scale*Math.exp(-px*0.002),ux,uy,x,y);if(scale<SNAP&&Math.abs(px)<1)reset();
  }
  /** Every pointer gone (the window lost focus): nothing held back reaches the game. */
  function cancel(){down.clear();held=[];first=null;pinch=null;mode='idle';}
  // The canvas resized (fullscreen, the window): the same point stays in the middle.
  let last=box();
  const resized=new ResizeObserver(()=>{const now=box();if(now.w===last.w&&now.h===last.h)return;const cx=((last.w/2-panX)/scale)/last.w,cy=((last.h/2-panY)/scale)/last.h;last=now;if(scale>1)set(scale,cx*now.w,cy*now.h,now.w/2,now.h/2);else layoutMinimap();});
  resized.observe(canvas);
  // The browser's own pinch and pan stay off the canvas while the setting is on.
  canvas.style.touchAction='none';
  function destroy(){resized.disconnect();reset();map.remove();canvas.style.touchAction='';canvas.style.transformOrigin='';container.classList.remove('zoomed');}
  function configure(s){Object.assign(settings,s);placeX=settings.x;placeY=settings.y;barBelow=placeY<0.5;layoutMinimap();}
  const state=()=>({scale,panX,panY,mode,minimap:map.hidden?null:(()=>{const r=map.getBoundingClientRect(),b=bar.getBoundingClientRect(),x=reset1x.getBoundingClientRect(),m=mapCanvas.getBoundingClientRect(),v=seen.getBoundingClientRect();const l=q=>({x:q.left,y:q.top,w:q.width,h:q.height});return {box:l(r),bar:l(b),reset:l(x),map:l(m),seen:l(v)};})(),place:[placeX,placeY],barBelow,size:settings.size,on:settings.minimap});
  // For the browser checks (tests/zoom-ui.mjs): the zoom on a game point, in game units.
  function zoomOn(to,gx,gy){const p=picture(),{w,h}=box();set(to,gx*p.s+p.left,gy*p.s+p.top,w/2,h/2);}
  return {take,frame,wheel,cancel,destroy,configure,reset,state,zoomOn};
}
