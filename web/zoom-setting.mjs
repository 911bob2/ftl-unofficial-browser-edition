// The game bar's "Pinch to zoom" (off by default, a setting of this browser) and, while it is on, its Minimap options:
// shown or not, its size, and back to its first place (it is moved by dragging its bar in the game). The zoom itself
// is web/zoom.mjs; a change reaches the running game at once.
const {readZoomSettings,writeZoomSettings,DEFAULTS}=await import('./zoom.mjs'+new URL(import.meta.url).search);

export function initZoomSetting({$,getEngine}){
  const on=$('zoom-enabled'),options=$('zoom-options'),minimap=$('zoom-minimap'),size=$('zoom-size'),place=$('zoom-place');
  function render(){
    const s=readZoomSettings();
    on.checked=s.on;options.hidden=!s.on;if(!s.on)options.open=false;
    minimap.checked=s.minimap;size.value=s.size;place.disabled=s.x===DEFAULTS.x&&s.y===DEFAULTS.y;
  }
  function apply(){const s=readZoomSettings();getEngine()?.setZoom?.(s.on?s:null);}
  function change(edit){writeZoomSettings({...readZoomSettings(),...edit});render();apply();}
  on.addEventListener('change',()=>change({on:on.checked}));
  minimap.addEventListener('change',()=>change({minimap:minimap.checked}));
  size.addEventListener('change',()=>change({size:size.value}));
  place.addEventListener('click',()=>change({x:DEFAULTS.x,y:DEFAULTS.y}));
  // The minimap may have been dragged since.
  options.addEventListener('toggle',render);
  render();
  return {render,apply};
}
