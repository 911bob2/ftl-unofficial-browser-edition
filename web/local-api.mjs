// Local-only mode answers the paths app.mjs sends to /api/* from this browser instead of the server: the game
// metadata (read from the public endpoint, exactly what an external user reads) and the diagnostic logs. Saves live in the
// device store (web/device-store.mjs, account `local-device`) and never reach the server in this mode.
import {LOCAL_ACCOUNT} from './device-store.mjs';
import * as logs from './local-logs.mjs';
export const LOCAL_ID=LOCAL_ACCOUNT;
let config;
const fail=(message,status)=>Object.assign(Error(message),{status});

export async function gameConfig(){
 if(config)return config;
 const r=await fetch('/api/public/game/config',{cache:'no-store'});
 if(!r.ok)throw fail('Game information is unavailable. Reload and retry.',r.status);
 return config=await r.json();
}
export async function localApi(path,{method='GET',body}={}){
 const [route,query='']=path.split('?');
 if(route==='/game/config')return gameConfig();
 if(route.startsWith('/debug/'))return logs.handle(route,method,body,query);
 throw fail('This is not available in local-only mode.',404);
}
export {logs};
