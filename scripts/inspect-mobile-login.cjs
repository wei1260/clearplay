// Public, signed-out provider pages only; never reads credential values.
const { chromium } = require('playwright');
const fs = require('node:fs'), path = require('node:path');
const assert = require('node:assert/strict');
async function main() {
  const cache = path.join(process.env.LOCALAPPDATA, 'ms-playwright');
  const executablePath = fs.readdirSync(cache).filter(n => /^chromium-\d+$/.test(n)).sort((a,b) => Number(b.split('-')[1])-Number(a.split('-')[1]))
    .flatMap(n => ['chrome-win64/chrome.exe','chrome-win/chrome.exe'].map(p => path.join(cache,n,p))).find(fs.existsSync);
  const browser = await chromium.launch({ executablePath, headless:true });
  const output = path.resolve(__dirname, '../test-results/android'); fs.mkdirSync(output,{recursive:true});
  const reports=[];
  try {
    for (const provider of ['quark','baidu']) {
      const ua = provider==='baidu' ? 'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36' : 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36';
      let page = await browser.newPage({viewport:{width:390,height:700},userAgent:ua,isMobile:true,hasTouch:true});
      await page.goto(provider==='baidu'?'https://wappass.baidu.com/passport/?login&tpl=netdisk&u=https%3A%2F%2Fpan.baidu.com%2Fwap%2Fhome':'https://pan.quark.cn/',{waitUntil:'domcontentloaded',timeout:30000});
      if(provider==='baidu') {
        const popup = page.waitForEvent('popup',{timeout:5000}).catch(()=>null);
        await page.getByText('登录',{exact:true}).click({timeout:15000}).catch(()=>{});
        page = await popup || page;
      }
      else await page.getByText('登录',{exact:true}).first().click({timeout:15000}).catch(()=>{});
      await page.waitForTimeout(5000);
      if(provider==='quark') await page.evaluate(fs.readFileSync(path.resolve(__dirname,'../android/app/src/main/assets/quark-login-mobile.js'),'utf8'));
      await page.waitForTimeout(5000);
      const frames=[];
      for(const frame of page.frames()) frames.push(await frame.evaluate(()=>({host:location.hostname,viewport:document.querySelector('meta[name=viewport]')?.content,width:document.documentElement.scrollWidth,innerWidth,
        inputs:[...document.querySelectorAll('input')].map(e=>({type:e.type,placeholder:e.getAttribute('placeholder'),width:e.getBoundingClientRect().width})),
        login:[...document.querySelectorAll('[class*=modal--], [class*=login-box],iframe')].slice(0,8).map(e=>({class:e.className,tag:e.tagName,rect:e.getBoundingClientRect().toJSON(),ancestors:[...function*(n){for(;n;n=n.parentElement)yield n}(e)].map(n=>({class:n.className,tag:n.tagName,transform:getComputedStyle(n).transform,width:getComputedStyle(n).width,position:getComputedStyle(n).position}))}))})).catch(e=>({error:e.name})));
      assert(frames[0].width <= 390, `${provider} must fit the phone viewport`);
      assert(frames.some(f=>f.inputs?.some(i=>i.width>150 && ['text','tel','password'].includes(i.type))), `${provider} must expose an official phone login input`);
      if (provider==='quark') {
        const modal=frames[0].login.find(e=>String(e.class).includes('LoginComponent--modal--'));
        assert(modal && modal.rect.left>=0 && modal.rect.right<=390);
      }
      reports.push({provider,frames}); await page.screenshot({path:path.join(output,`mobile-${provider}-login-public.png`)}); await page.close();
    }
    fs.writeFileSync(path.join(output,'mobile-login-layout.json'),JSON.stringify(reports,null,2)); console.log(JSON.stringify(reports));
  } finally { await browser.close(); }
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
