(() => {
  // Keep the official desktop authentication endpoint, which supports phone login.
  // A mobile user agent on this site redirects to an app-download landing page.
  let viewport = document.querySelector('meta[name="viewport"]');
  if (!viewport) { viewport = document.createElement('meta'); viewport.name = 'viewport'; document.head.append(viewport); }
  viewport.content = 'width=device-width,initial-scale=1';
  if (document.getElementById('clearplay-mobile-login')) return;
  const style = document.createElement('style'); style.id = 'clearplay-mobile-login';
  style.textContent = `
    html,body { min-width:0!important; width:100%!important; overflow-x:hidden!important; }
    .user-layout { position:fixed!important; inset:0!important; width:100%!important; overflow:hidden!important; }
    [class*="HomeLayout--flex-wrap--"] { transform:none!important; min-width:0!important; width:100%!important; }
    .first-screen { min-width:0!important; width:100%!important; }
    .first-screen > .left { display:none!important; }
    .first-screen > .right { width:100%!important; }
    [class*="LoginComponent--modal--"] { position:fixed!important; left:50%!important; top:24px!important; transform:translateX(-50%)!important; margin:0!important; width:min(360px,calc(100vw - 24px))!important; max-height:calc(100dvh - 32px)!important; overflow-y:auto!important; box-sizing:border-box!important; background:white!important; z-index:1000!important; }
    [class*="LoginComponent--modal--"] iframe { max-width:100%!important; }
  `;
  document.head.append(style);
  // Select the official phone-login view; never submit a form or accept agreements.
  const choosePhone = () => {
    const phone = [...document.querySelectorAll('[class*="LoginComponent--other-mode-item--"]')]
      .find(element => element.textContent.trim() === '手机登录');
    if (phone) { phone.click(); return true; }
    return false;
  };
  if (!choosePhone()) {
    const observer = new MutationObserver(() => { if (choosePhone()) observer.disconnect(); });
    observer.observe(document.body, { childList:true, subtree:true });
    setTimeout(() => observer.disconnect(), 15000);
  }
})();
