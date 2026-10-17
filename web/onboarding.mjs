// First-visit screens: the fan-project notice, the cloud/local choice and account registration.
// The server renders the right first screen from the consent and mode cookies; this module only handles the clicks.
const $=id=>document.getElementById(id);
export function mountOnboarding({api,show,shell,message,token,signedIn,getMe}){
 const mark=()=>{try{localStorage.setItem('ftl-enable-cloud','1');}catch{}};
 // A visitor who came from local mode keeps their browser save and is offered to move it after signing in.
 document.addEventListener('click',e=>{if(getMe()?.local&&e.target.closest?.('a[href="/?view=register"],a[href="/?view=signin"]'))mark();},true);

 $('consent-agree').addEventListener('click',async e=>{
  e.target.disabled=true;
  try{await api('/onboarding/consent',{method:'POST'});shell.consent='1';if(getMe())shell.mode='cloud';message('');show(getMe()?'launch-panel':'mode-panel');}
  catch(error){message(error.message);}
  finally{e.target.disabled=false;}
 });
 async function chooseMode(mode,button){
  button.disabled=true;
  try{
   await api('/onboarding/mode',{method:'POST',body:{mode}});shell.mode=mode;message('');
   // The local launcher is rendered by the server; reload so it carries the local navigation and cards.
   if(mode==='local')location.assign('/');else{show('register-panel');button.disabled=false;}
  }catch(error){message(error.message);button.disabled=false;}
 }
 $('mode-cloud').addEventListener('click',e=>chooseMode('cloud',e.target));
 $('mode-local').addEventListener('click',e=>chooseMode('local',e.target));
 $('play-local').addEventListener('click',e=>chooseMode('local',e.target));

 // Links between the sign-in and registration screens stay in the page.
 for(const [id,panel] of [['login-register','register-panel'],['register-signin','login-panel']])$(id).addEventListener('click',e=>{if(getMe()?.local)return;e.preventDefault();message('');show(panel);});
 $('register-back').addEventListener('click',()=>{message('');if(shell.mode==='local')location.assign('/');else show('mode-panel');});

 function showClosed(){
  $('register-open').hidden=true;$('register-closed').hidden=false;$('login-register').hidden=true;$('login-closed').hidden=false;
 }
 async function register(username,password){
  try{return await api('/auth/register',{method:'POST',body:{username,password}});}
  catch(e){if(e.status!==409)throw e;await token();return api('/auth/register',{method:'POST',body:{username,password}});}
 }
 $('register-form').addEventListener('submit',async e=>{
  e.preventDefault();const form=new FormData(e.target),button=e.submitter;
  if(form.get('password')!==form.get('confirm'))return message('The passwords do not match.');
  button.disabled=true;
  try{
   await register(form.get('username'),form.get('password'));e.target.reset();message('');
   // Registered and signed in; a page rendered for local mode reloads into the account version of the launcher.
   if(shell.mode==='local'&&getMe()?.local){location.assign('/');return;}
   await signedIn();
  }catch(error){if(error.code==='registration_closed')showClosed();message(error.message);}
  finally{button.disabled=false;}
 });
}
