// The first-visit consent and cloud/local choice screens are decided from two long-lived cookies. UI tests that are not
// about onboarding start as a returning visitor who already agreed and chose cloud saves, exactly as the server would
// have set the cookies after those screens.
export function onboardingCookies(origin,mode='cloud'){
 const url=new URL(origin),names=url.protocol==='http:'?{consent:'ftl-consent-local-'+url.port,mode:'ftl-mode-local-'+url.port}:{consent:'__Host-ftl-consent',mode:'__Host-ftl-mode'};
 return [{name:names.consent,value:'1',url:origin},...(mode?[{name:names.mode,value:mode,url:origin}]:[])];
}
// Every context created from this browser starts with those cookies. `browser.newContextWithoutOnboarding` is the original.
export function withOnboarding(browser,origin,mode='cloud'){
 const original=browser.newContext.bind(browser);
 browser.newContextWithoutOnboarding=original;
 browser.newContext=async(...args)=>{const context=await original(...args);await context.addCookies(onboardingCookies(origin,mode));return context;};
 return browser;
}
