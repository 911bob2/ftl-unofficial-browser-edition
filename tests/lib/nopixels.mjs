// FTL_NOPIXELS=1 (test setup only; the product never loads this): for test jobs whose checks read game state, not
// pictures. In every page of the context each WebGL draw and clear returns without rasterising, and each texture gets
// 1x1 storage without pixels (texSubImage2D does nothing). The engine still runs every update and render call with the
// same state calls, shaders and buffers and keeps its own image sizes, so what it computes is the same; only no
// pixels exist. That keeps image memory out of the software GL process (Chromium's SwiftShader holds every texture in
// RAM: 0.8-1 GB of a Multiverse engine-mode job) and its rasterising CPU out of the job.
// A picture cannot be read then: readPixels and the game canvas's toDataURL / toBlob throw, so a check that reads
// pixels fails loudly instead of comparing blanks.
export const noPixelsScript = `(()=>{
 const fail=()=>{throw new Error('FTL_NOPIXELS: this check reads pixels; run it with drawing on');};
 for(const C of [window.WebGLRenderingContext,window.WebGL2RenderingContext]){if(!C)continue;const p=C.prototype;
  for(const n of ['drawArrays','drawElements','drawArraysInstanced','drawElementsInstanced','drawRangeElements','clear','blitFramebuffer'])if(p[n])p[n]=function(){};
  const ti=p.texImage2D,ts=p.texStorage2D;
  p.texImage2D=function(...a){if(a.length>=9)return ti.call(this,a[0],a[1],a[2],1,1,0,a[6],a[7],null);if(a.length===6)return ti.call(this,a[0],a[1],a[2],1,1,0,a[3],a[4],null);return ti.apply(this,a);};
  p.texSubImage2D=function(){};if(ts)p.texStorage2D=function(t,l,f){return ts.call(this,t,1,f,1,1);};
  p.readPixels=fail;}
 const H=HTMLCanvasElement.prototype,gc=H.getContext,gl=new WeakSet();
 H.getContext=function(type,...a){const c=gc.call(this,type,...a);if(c&&/webgl/.test(type))gl.add(this);return c;};
 for(const n of ['toDataURL','toBlob']){const f=H[n];H[n]=function(...a){if(gl.has(this))fail();return f.apply(this,a);};}
 window.ftlNoPixels=true;
})()`;

/** Modes of tests/baseline.mjs whose checks read the canvas (BrowserPixels / BrowserCanvasShot): they always draw. */
export const PIXEL_MODES = new Set(['gameplay-parity']);
