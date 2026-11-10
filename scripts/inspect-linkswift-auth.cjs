// Run with Electron and --account-check. Own Clearplay session only; no secret output.
const { app, session, safeStorage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const { createHash } = require('node:crypto');
if (!process.argv.includes('--account-check')) throw new Error('Explicit own-account check required');
const profile = path.join(app.getPath('appData'), '澄映播放器');
if (!fs.existsSync(path.join(profile, 'library.json'))) throw new Error('Existing Clearplay profile required');
app.setPath('userData', profile);
const rows = [];
const report = row => { rows.push(row); console.log(JSON.stringify(row)); };
const options = Object.fromEntries(process.argv.filter(x => /^--\w+=\d+$/.test(x)).map(x => x.slice(2).split('=')));
const integer = (key, fallback, min, max) => Math.max(min, Math.min(max, Number(options[key] || fallback)));
const total = integer('mib', 1, 1, 16) * 1024 * 1024;
const seconds = integer('seconds', 20, 5, 60), rounds = integer('rounds', 1, 1, 3), sampleIndex = integer('sample', 0, 0, 50);
const offset = integer('offset', 0, 0, 1000000000), variants = integer('variants', 0, 0, 1), candidateOnly = integer('candidateOnly', 0, 0, 1);
const whole = integer('whole', 0, 0, 1);
let referenceHash, expectedSize;
const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36';
const authorize = 'https://openapi.baidu.com/oauth/2.0/authorize?response_type=token&scope=basic,netdisk&client_id=omiOnr2tYnN9vSyDErcVFWpPU2mZA7YO&redirect_uri=oob&confirm_login=0';
const allowed = value => {
  const url = new URL(value);
  if (url.protocol === 'http:') url.protocol = 'https:';
  if (url.protocol !== 'https:' || url.username || url.password || url.port || !['baidu.com', 'baidupcs.com', 'baidubce.com'].some(d => url.hostname === d || url.hostname.endsWith(`.${d}`))) throw new Error('Unexpected endpoint');
  return url;
};
const query = (base, params) => { const url = allowed(base); for (const [k,v] of Object.entries(params)) url.searchParams.set(k,v); return url.href; };
let own;
async function request(url, headers = {}, signal) {
  for (let hop = 0; hop < 8; hop++) {
    url = allowed(url).href;
    const cookie = (await own.cookies.get({url})).map(row => `${row.name}=${row.value}`).join('; ');
    const res = await fetch(url, { headers: { 'User-Agent': ua, ...headers, ...(cookie ? {Cookie:cookie} : {}) }, signal: signal || AbortSignal.timeout(15000), redirect: 'manual' });
    const next = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && next) { await res.body?.cancel(); url = allowed(new URL(next, url).href).href; continue; }
    return res;
  }
  throw new Error('Redirect limit');
}
async function json(url, headers = {}) { const res = await request(url, headers); return JSON.parse(await res.text()); }
async function priorConsent() {
  let target = authorize;
  for (let hop = 0; hop < 8; hop++) {
    const url = allowed(target);
    const token = new URLSearchParams(url.hash.slice(1)).get('access_token');
    if (token) return token;
    const cookie = (await own.cookies.get({url:url.href})).map(row => `${row.name}=${row.value}`).join('; ');
    const res = await fetch(url.href, { headers:{'User-Agent':ua,...(cookie?{Cookie:cookie}:{})}, redirect: 'manual', signal: AbortSignal.timeout(15000) });
    const next = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && next) { await res.body?.cancel(); target = allowed(new URL(next, target).href).href; continue; }
    const html = await res.text();
    report({ phase: 'oauth', http: res.status, reason: html.includes('grant_permissions') ? 'consent-required' : 'login-or-consent-required' });
    return null; // Never auto-POST a new authorization grant.
  }
  return null;
}
async function sample(route, url, headers, round) {
  const started = performance.now(); const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), seconds * 1000);
  const hash = createHash('sha256');
  let bytes = 0, http = 0, serverCode = '', errorType = '', networkCode = '';
  try {
    const res = await request(url, { ...headers, ...(!whole ? {Range:`bytes=${offset}-${offset+total-1}`} : {}), 'Accept-Encoding': 'identity' }, controller.signal);
    http = res.status;
    if (http >= 400) { const text = await res.text(); serverCode = /"(?:error_code|errno|code)"\s*:\s*"?(-?\d{1,8})/.exec(text)?.[1] || ''; }
    else if (whole ? http !== 200 || Number(res.headers.get('content-length')) !== expectedSize : http !== 206 || res.headers.get('content-range') !== `bytes ${offset}-${offset+total-1}/${expectedSize}`) { await res.body?.cancel(); errorType = 'InvalidRange'; }
    else {
      for await (const block of res.body) { const part = whole ? block.subarray(0,total-bytes) : block; bytes += part.length; hash.update(part); if (whole && bytes === total) break; }
    }
  } catch (error) { errorType = error.name; networkCode = /ERR_[A-Z_]+/.exec(String(error.message))?.[0] || (error.message === 'Unexpected endpoint' ? 'UNEXPECTED_ENDPOINT' : ''); }
  finally { clearTimeout(timer); }
  const elapsedMs = Math.round(performance.now() - started);
  const complete = bytes === total && !errorType, digest = complete ? hash.digest('hex') : null;
  const hashCompared = complete && !!referenceHash, hashMatches = hashCompared && referenceHash === digest;
  if (complete && !referenceHash) referenceHash = digest;
  report({ phase: 'transfer', route, round, sample:sampleIndex, whole, offset, http, serverCode, requestedBytes:total, bytes, elapsedMs, bytesPerSecond: Math.round(bytes*1000/elapsedMs), complete, hashCompared, hashMatches, errorType, networkCode });
}
app.whenReady().then(async () => {
  own = session.fromPartition('persist:clearplay-baidu'); own.setUserAgent(ua);
  const library = JSON.parse(fs.readFileSync(path.join(profile,'library.json'),'utf8'));
  const files = library.history.filter(x => x.provider === 'baidu' && x.size > 1024*1024);
  let credentials = {};
  const auth = path.join(profile,'baidu-auth.bin');
  if (fs.existsSync(auth)) credentials = JSON.parse(safeStorage.decryptString(fs.readFileSync(auth)));
  report({ phase: 'account', samples: files.length, hasSavedOAuth: !!credentials.accessToken, hasWebSession: (await own.cookies.get({url:'https://pan.baidu.com/'})).some(x => x.name === 'BDUSS') });
  if (!files.length) return;
  const file = files[sampleIndex]; if (!file || file.size < offset+total) throw new Error('Requested sample unavailable');
  expectedSize = file.size;
  const filePath = file.path || file.dir;
  const routes = [];
  const web = await json(query('https://pan.baidu.com/api/gettemplatevariable', {fields: '["uk","bdstoken"]', web:1, clienttype:0}), {'User-Agent':ua});
  const token = credentials.accessToken || await priorConsent();
  if (token) {
    const account = await json(query('https://pan.baidu.com/rest/2.0/xpan/nas', {method:'uinfo',access_token:token}), {'User-Agent':'pan.baidu.com'});
    const sameAccount = String(account.uk) === String(web.result?.uk) && !!web.result?.uk;
    report({phase:'oauth',code:account.errno,sameAccount,vipType:account.vip_type});
    if (sameAccount) {
      if (!/^[0-9]+$/.test(String(file.id))) throw new Error('Invalid own file identifier');
      const meta = await json(query('https://pan.baidu.com/rest/2.0/xpan/multimedia', {method:'filemetas',dlink:1,fsids:`[${file.id}]`,access_token:token}), {'User-Agent':'pan.baidu.com'});
      const link = meta.list?.[0]?.dlink;
      report({phase:'route',route:'linkswift-oauth',code:meta.errno,available:!!link});
      if (link) routes.push({route:'linkswift-oauth',url:query(link,{access_token:token}),headers:{'User-Agent':'pan.baidu.com'}});
    }
  }
  for (const [route,api,params] of [
    ['filemetas','filemetas',{target:JSON.stringify([filePath]),dlink:1,web:1,clienttype:0,bdstoken:web.result?.bdstoken||''}],
    ['media-filemetas','filemetas',{target:JSON.stringify([filePath]),dlink:1,web:5,origin:'dlna'}],
    ['media-mediainfo','mediainfo',{type:'VideoURL',path:filePath,fs_id:file.id,devuid:'0%1',clienttype:1,channel:'android_15_25010PN30C_bd-netdisk_1523a',nom3u8:1,dlink:1,media:1,origin:'dlna'}]
  ]) {
    const data = await json(query(`https://pan.baidu.com/api/${api}`, params), {'User-Agent':ua,'Referer':'https://pan.baidu.com/disk/main'});
    const info = Array.isArray(data.info) ? data.info[0] : data.info;
    const link = data.errno === 0 && /^https?:\/\//.test(info?.dlink || '') ? info.dlink : null;
    report({phase:'route',route,code:data.errno,available:!!link});
    if (link) {
      const headers = {'User-Agent':route==='filemetas'?'pan.baidu.com':'netdisk','Referer':'https://pan.baidu.com/disk/main'};
      routes.push({route,url:link,headers});
      if (variants) routes.push({route:route+'-alternate-ua',url:link,headers:{...headers,'User-Agent':route==='filemetas'?'netdisk':'pan.baidu.com'}});
    }
  }
  for (let round = 1; round <= rounds; round++) {
    const selected = candidateOnly ? routes.filter(x => x.route === 'media-filemetas') : routes;
    for (const source of round % 2 ? selected : [...selected].reverse()) await sample(source.route,source.url,source.headers,round);
  }
}).catch(error => report({phase:'error',errorType:error.name})).finally(() => {
  const dir = path.resolve(__dirname,'../test-results/android/throughput'); fs.mkdirSync(dir,{recursive:true});
  const file = path.join(dir,`${new Date().toISOString().replace(/[:.]/g,'-')}-linkswift-desktop.json`);
  fs.writeFileSync(file,JSON.stringify({timestamp:new Date().toISOString(),platform:'Windows',options:{sample:sampleIndex,mib:total/1024/1024,offset,variants,candidateOnly,whole,seconds,rounds},rows},null,2));
  console.log(`Report: ${file}`); app.quit();
});
