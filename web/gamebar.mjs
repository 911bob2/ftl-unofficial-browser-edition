// The control bar above the engine: Console, Save, Fullscreen, Hide header and Save and close.
// Everything here only drives buttons; saving and shutting down are injected by app.mjs.
export function initGameBar({$,getEngine,save,saveAndClose,message}){
  const container=$('game-container'),bar=container.querySelector('.gamebar'),canvas=$('game');
  const fullscreenButton=$('fullscreen'),hideButton=$('hide-header'),showButton=$('show-header'),consoleButton=$('console'),closeButton=$('save-close');
  // The header is hidden only in fullscreen and returns with it. While hidden it slides in when the pointer
  // touches the top edge of the screen (or a control inside it has keyboard focus) and leaves shortly after the
  // pointer moves away. Touch screens get a small tab at the top edge instead.
  let hidden=false,peek=false,leaving,hint;
  const fullscreen=()=>document.fullscreenElement===container;
  const focusGame=()=>canvas.focus({preventScroll:true});
  function render(){
    const full=fullscreen();
    if(!full){hidden=false;peek=false;}
    fullscreenButton.textContent=full?'Exit fullscreen':'Fullscreen';
    hideButton.hidden=!full;
    showButton.hidden=!hidden;
    container.classList.toggle('header-hidden',hidden);
    container.classList.toggle('header-peek',hidden&&peek);
    if(!hidden)hideHint();
  }
  function hideHint(){hint?.remove();hint=undefined;}
  function showHint(){
    hideHint();hint=document.createElement('p');hint.className='header-hint';hint.setAttribute('role','status');
    hint.textContent='Header hidden. Move the pointer to the top edge to show it.';container.append(hint);
    const mine=hint;setTimeout(()=>{if(hint===mine)hideHint();},3500);
  }
  function setPeek(value){clearTimeout(leaving);leaving=undefined;if(peek!==value){peek=value;if(value)hideHint();render();}}
  fullscreenButton.addEventListener('click',()=>{
    // Pressing it again leaves fullscreen; the label follows fullscreenchange, so Escape keeps it right too.
    const change=document.fullscreenElement?document.exitFullscreen():container.requestFullscreen();
    change.catch(e=>message(e.message));
    focusGame();
  });
  document.addEventListener('fullscreenchange',render);
  hideButton.addEventListener('click',()=>{if(!fullscreen())return;hidden=true;peek=false;render();showHint();focusGame();});
  showButton.addEventListener('click',()=>{hidden=false;peek=false;render();focusGame();});
  container.addEventListener('pointermove',e=>{
    if(!hidden)return;
    const near=e.clientY<=2||(peek&&e.clientY<=bar.getBoundingClientRect().bottom+6);
    if(near)setPeek(true);
    else if(peek&&leaving===undefined)leaving=setTimeout(()=>{leaving=undefined;setPeek(false);},350);
  });
  consoleButton.addEventListener('click',()=>{
    const engine=getEngine();
    if(engine?.consoleToggle)engine.consoleToggle();else engine?.consoleOpen?.();
    focusGame();
  });
  $('save').addEventListener('click',()=>save());
  closeButton.addEventListener('click',async()=>{
    if(closeButton.disabled)return;
    const label=closeButton.textContent;
    closeButton.disabled=true;closeButton.textContent='Saving…';
    try{if(await saveAndClose()!==false)return;}
    catch(e){
      // Stay in the game and say why, where it can be seen in fullscreen too.
      $('save-status').textContent='Not saved: '+e.message;message(e.message);
      if(hidden){hidden=false;peek=false;render();}
    }
    closeButton.disabled=false;closeButton.textContent=label;
  });
  render();
  return {render};
}
