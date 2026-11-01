// Starts the game from the Play page the way a player does (docs/SAVES.md): Launch FTL plays the current save of the
// enabled setup and makes the first one when there is none. On a fixture reused from an earlier run that save's lock
// is still held by the earlier browser, which closed without Save and close, so the lock is overridden. Returns once
// the engine runs (window.ftlEngine); a launch the page reports as failed throws its message.
export async function launchGame(page,{timeout=180000}={}){
 const settled=()=>page.waitForFunction(()=>!!window.ftlEngine||!!document.querySelector('#save-lock-dialog[open]')||!document.getElementById('message').hidden,null,{timeout});
 await page.locator('#launch').click();await settled();
 if(await page.locator('#save-lock-dialog[open]').count()){console.log('launch: Override lock');await page.locator('#save-lock-dialog-yes').click();await settled();}
 const failure=await page.evaluate(()=>window.ftlEngine?null:document.getElementById('message').textContent);
 if(failure!==null)throw Error('Launch failed: '+failure);
}
// Browser console errors that are normal answers, for page.on('console'): the signed-out session check before the login
// (401), the account answering 404 for a save it does not have yet, which the syncer reads as "upload it", and 409 for a
// lock held elsewhere, which asks Override lock (web/sync.mjs).
export function expectedConsoleError(message){
 const text=message.text(),{pathname}=new URL(message.location().url||'about:blank');
 return /status of 401/.test(text)||/status of 404/.test(text)&&/^\/api\/saves\/[0-9a-f-]{36}$/.test(pathname)||/status of 409/.test(text)&&/^\/api\/saves\/[0-9a-f-]{36}\/lock$/.test(pathname);
}
