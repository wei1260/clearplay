const VIDEO_EXT = /\.(mp4|m4v|mov|webm|mkv|avi|ts|m2ts|flv|wmv)$/i;
const { MediaRegistry } = require('./media.cjs');

const BAIDU_ERRORS = {
  110: '百度授权已失效，请重新连接网盘。',
  111: '百度授权已过期，请重新连接网盘。',
  '-9': '文件不存在或已被移动，请刷新目录。',
  '-7': '百度接口拒绝访问，请检查应用权限。',
  31034: '百度暂未提供此文件的播放资源。',
  31326: '此资源需要相应的百度网盘账号权益。',
};

async function jsonRequest(fetchImpl, url, options = {}) {
  let response;
  try {
    response = await fetchImpl(url, { ...options, signal: AbortSignal.timeout(20000) });
  } catch {
    throw new Error('连接网盘超时或网络不可用，请稍后重试。');
  }
  let data;
  try {
    data = JSON.parse(await response.text(), (key, value, context) => key === 'fs_id' && typeof value === 'number' ? context.source : value);
  } catch {
    throw new Error(`网盘返回了无法识别的响应（HTTP ${response.status}）。`);
  }
  return { response, data };
}

function videoFile(file) {
  return file.isDir || file.category === 1 || VIDEO_EXT.test(file.name);
}

class BaiduDrive {
  constructor({ fetchImpl = fetch, credentials = {}, save = () => {}, now = Date.now } = {}) {
    this.fetch = fetchImpl;
    this.credentials = credentials;
    this.save = save;
    this.now = now;
    this.pending = null;
    this.refreshing = null;
  }

  status() {
    return { connected: !!this.credentials.accessToken, name: this.credentials.name || '百度网盘', appKey: this.credentials.appKey || '' };
  }

  async oauth(path, params) {
    const url = new URL(path, 'https://openapi.baidu.com');
    url.search = new URLSearchParams(params).toString();
    return jsonRequest(this.fetch, url);
  }

  async begin(appKey, secretKey) {
    if (!appKey?.trim() || !secretKey?.trim()) throw new Error('请填写百度应用的 AppKey 和 SecretKey。');
    this.cancel();
    const attempt = {};
    this.pending = attempt;
    const { response, data } = await this.oauth('/oauth/2.0/device/code', {
      response_type: 'device_code', client_id: appKey.trim(), scope: 'basic,netdisk',
    });
    if (this.pending !== attempt) throw new Error('授权已取消。');
    if (!response.ok || data.error || !data.device_code) {
      this.pending = null;
      throw new Error('无法开始百度授权，请检查 AppKey 和应用的网盘权限。');
    }
    const verification = new URL(data.verification_url);
    if (verification.protocol !== 'https:' || verification.hostname !== 'openapi.baidu.com') throw new Error('百度返回了异常的授权页面地址。');
    Object.assign(attempt, {
      appKey: appKey.trim(), secretKey: secretKey.trim(), code: data.device_code,
      expiresAt: this.now() + data.expires_in * 1000, interval: Math.max(5, Number(data.interval) || 5), nextPoll: 0,
      verificationUrl: verification.href,
    });
    return { userCode: data.user_code, expiresIn: data.expires_in, interval: attempt.interval };
  }

  async poll() {
    const attempt = this.pending;
    if (!attempt?.code || this.now() >= attempt.expiresAt) {
      this.cancel();
      throw new Error('授权码已过期，请重新发起授权。');
    }
    if (this.now() < attempt.nextPoll) return { pending: true, interval: attempt.interval };
    attempt.nextPoll = this.now() + attempt.interval * 1000;
    const { response, data } = await this.oauth('/oauth/2.0/token', {
      grant_type: 'device_token', code: attempt.code, client_id: attempt.appKey, client_secret: attempt.secretKey,
    });
    if (this.pending !== attempt) throw new Error('授权已取消。');
    if (['authorization_pending', 'slow_down'].includes(data.error)) {
      if (data.error === 'slow_down') attempt.interval += 5;
      return { pending: true, interval: attempt.interval };
    }
    if (!response.ok || data.error || !data.access_token) {
      this.cancel();
      throw new Error('百度授权未完成，请检查 SecretKey，或重新发起授权。');
    }
    this.credentials = {
      appKey: attempt.appKey, secretKey: attempt.secretKey, accessToken: data.access_token,
      refreshToken: data.refresh_token, expiresAt: this.now() + data.expires_in * 1000,
    };
    this.pending = null;
    await this.save(this.credentials);
    return { pending: false, ...this.status() };
  }

  cancel() { this.pending = null; }

  async refresh() {
    if (this.refreshing) return this.refreshing;
    const previous = this.credentials;
    this.refreshing = (async () => {
      if (!previous.refreshToken) throw new Error('请重新授权百度网盘。');
      const { response, data } = await this.oauth('/oauth/2.0/token', {
        grant_type: 'refresh_token', refresh_token: previous.refreshToken,
        client_id: previous.appKey, client_secret: previous.secretKey,
      });
      if (this.credentials !== previous) throw new Error('账号已断开连接。');
      if (!response.ok || data.error || !data.access_token) throw new Error('百度授权无法续期，请重新连接网盘。');
      this.credentials = { ...previous, accessToken: data.access_token, refreshToken: data.refresh_token || previous.refreshToken, expiresAt: this.now() + data.expires_in * 1000 };
      await this.save(this.credentials);
    })().finally(() => { this.refreshing = null; });
    return this.refreshing;
  }

  async api(path, params, retry = true) {
    if (!this.credentials.accessToken) throw new Error('请先连接百度网盘。');
    if (this.credentials.expiresAt <= this.now() + 60000) await this.refresh();
    const url = new URL(path, 'https://pan.baidu.com');
    url.search = new URLSearchParams({ ...params, access_token: this.credentials.accessToken }).toString();
    const { response, data } = await jsonRequest(this.fetch, url);
    if ([110, 111].includes(Number(data.errno)) && retry && this.credentials.refreshToken) {
      await this.refresh();
      return this.api(path, params, false);
    }
    if (!response.ok || data.errno !== 0) throw new Error(BAIDU_ERRORS[data.errno] || `百度接口请求失败（${data.errno ?? response.status}），请检查应用权限和账号权益。`);
    return data;
  }

  async list(dir = '/', page = 1) {
    if (typeof dir !== 'string' || !dir.startsWith('/')) throw new Error('无效的百度网盘目录。');
    const data = await this.api('/rest/2.0/xpan/file', { method: 'list', dir, start: (page - 1) * 100, limit: 100, order: 'name', folder: 0 });
    return {
      files: (data.list || []).map(f => ({ provider: 'baidu', id: String(f.fs_id), name: f.server_filename, dir: f.path, isDir: f.isdir === 1, category: f.category, size: f.size, modifiedAt: f.server_mtime * 1000 })).filter(videoFile),
      hasMore: data.list?.length === 100,
    };
  }

  async sources(id) {
    if (!/^\d+$/.test(id)) throw new Error('无效的百度文件编号。');
    const data = await this.api('/rest/2.0/xpan/multimedia', { method: 'filemetas', fsids: `[${id}]`, dlink: 1 });
    const file = data.list?.[0];
    if (!file) throw new Error('当前授权的网盘中未找到此文件，请刷新目录后重新选择视频。');
    if (!file?.dlink) throw new Error('百度未提供此文件的原画链接，请检查文件及账号权益。');
    const url = new URL(file.dlink);
    url.searchParams.set('access_token', this.credentials.accessToken);
    return { sources: [{ label: '原画', url: url.href, headers: { 'User-Agent': 'pan.baidu.com' } }], notice: '' };
  }

  async logout() {
    this.cancel();
    this.credentials = {};
    await this.save({});
  }
}

class BaiduWebDrive {
  constructor({ fetchImpl = fetch, getCookie, userAgent, wait = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
    this.fetch = fetchImpl;
    this.getCookie = getCookie;
    this.userAgent = userAgent;
    this.wait = wait;
    this.connected = false;
  }

  status() { return { connected: this.connected, name: '百度网盘', mode: 'web' }; }

  async api(path, params = {}, cookie) {
    const sessionCookie = cookie ?? await this.getCookie();
    if (!sessionCookie) throw new Error('请在百度官网登录窗口中完成登录。');
    const url = new URL(path, 'https://pan.baidu.com');
    url.search = new URLSearchParams({ web: 1, clienttype: 0, ...params }).toString();
    const { response, data } = await jsonRequest(this.fetch, url, { headers: { Cookie: sessionCookie, Referer: 'https://pan.baidu.com/disk/main', 'User-Agent': this.userAgent } });
    if (!response.ok || data.errno !== 0) {
      if ([-6, -2].includes(Number(data.errno))) this.connected = false;
      throw new Error(`百度网页请求未成功（${data.errno ?? response.status}），请检查官网登录状态后重试。`);
    }
    return data;
  }

  async connect() {
    await this.list('/', 1);
    this.connected = true;
    return this.status();
  }

  async list(dir = '/', page = 1) {
    if (typeof dir !== 'string' || !dir.startsWith('/')) throw new Error('无效的百度网盘目录。');
    const data = await this.api('/api/list', { dir, page, num: 100, order: 'name', desc: 0, showempty: 0 });
    return {
      files: (data.list || []).map(f => ({ provider: 'baidu', id: String(f.fs_id), name: f.server_filename, dir: f.path, isDir: f.isdir === 1, category: f.category, size: f.size, modifiedAt: f.server_mtime * 1000 })).filter(videoFile),
      hasMore: data.has_more != null ? !!data.has_more : data.list?.length === 100,
    };
  }

  async sources(id, path, { onPreparing = () => {} } = {}) {
    if (!/^\d+$/.test(id) || typeof path !== 'string' || !path.startsWith('/')) throw new Error('视频路径已失效，请从网盘目录重新选择视频。');
    const cookie = await this.getCookie();
    const headers = { Cookie: cookie, Referer: 'https://pan.baidu.com/disk/main', 'User-Agent': this.userAgent };
    const template = await this.api('/api/gettemplatevariable', { fields: JSON.stringify(['bdstoken']) }, cookie);
    const token = template.result?.bdstoken;
    const sources = [];
    try {
      const meta = await this.api('/api/filemetas', { target: JSON.stringify([path]), dlink: 1, web:5, origin:'dlna', ...(token ? { bdstoken: token } : {}) }, cookie);
      const original = meta.info?.[0]?.dlink;
      if (original) {
        const source = { label:'原画', provider:'baidu', url:original.replace(/^http:/,'https:'), headers:{...headers,'User-Agent':'netdisk'} };
        const probe = new MediaRegistry({fetchImpl:this.fetch});
        const response = await probe.handle(new Request(probe.add(source), {headers:{Range:'bytes=0-0'},signal:AbortSignal.timeout(3000)}));
        const usable = response.status === 206 && /^bytes 0-0\/[1-9][0-9]*$/.test(response.headers.get('content-range') || '');
        await response.body?.cancel();
        if (usable) sources.push(source);
      }
    } catch { /* 原画只使用已验证的视频取链，不回退普通下载链路。 */ }
    const streams = await Promise.all([
      ['1080P', 'M3U8_AUTO_1080'], ['720P', 'M3U8_AUTO_720'], ['480P', 'M3U8_AUTO_480'], ['音频', 'M3U8_HLS_MP3_128'],
    ].map(async ([label, type]) => {
      const url = new URL('/api/streaming', 'https://pan.baidu.com');
      url.search = new URLSearchParams({ path, type, web: 1, clienttype: 0, ...(token ? { bdstoken: token } : {}) }).toString();
      try {
        for (let attempt = 0; attempt < 2; attempt++) {
          const response = await this.fetch(url, { headers, signal: AbortSignal.timeout(20000) });
          if (!response.ok) { await response.body?.cancel(); return null; }
          const manifest = await response.text();
          if (manifest.trimStart().startsWith('#EXTM3U')) return { label, url: url.href, headers, hls: true, manifest };
          let preparation;
          try { preparation = JSON.parse(manifest); } catch { return null; }
          const seconds = Number(preparation.adTime);
          if (attempt !== 0 || preparation.errno !== 133 || !preparation.adToken || !Number.isFinite(seconds) || seconds < 0) return null;
          onPreparing(seconds);
          await this.wait(seconds * 1000);
          url.searchParams.set('adToken', preparation.adToken);
        }
        return null;
      } catch { return null; }
    }));
    const audio = streams.pop();
    sources.push(...streams.filter(Boolean).map(source => audio ? { ...source, audio } : source));
    if (!sources.length) throw new Error('百度未返回可播放资源，请在官网检查此视频的转码状态和账号播放权益，或尝试高级应用授权。');
    return { sources, notice: streams.some(Boolean) ? '' : '当前仅取得原画资源；原文件读取可能较慢。' };
  }
}

class QuarkDrive {
  constructor({ fetchImpl = fetch, getCookie, setCookies = async () => {}, userAgent }) {
    this.fetch = fetchImpl;
    this.getCookie = getCookie;
    this.setCookies = setCookies;
    this.userAgent = userAgent;
    this.connected = false;
  }

  status() { return { connected: this.connected, name: '夸克网盘' }; }

  async api(path, { params = {}, body, cookie } = {}) {
    const url = new URL(`https://drive.quark.cn/1/clouddrive${path}`);
    url.search = new URLSearchParams({ pr: 'ucpro', fr: 'pc', ...params }).toString();
    const sessionCookie = cookie ?? await this.getCookie();
    if (!sessionCookie) throw new Error('请在登录窗口中登录夸克网盘，再点击“完成登录”。');
    const { response, data } = await jsonRequest(this.fetch, url, {
      method: body ? 'POST' : 'GET', headers: { Cookie: sessionCookie, Referer: 'https://pan.quark.cn/', 'User-Agent': this.userAgent, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    await this.setCookies(response.headers.getSetCookie?.() || []);
    if (!response.ok || data.status !== 200 || Number(data.code) !== 0) {
      if ([401, 403].includes(response.status) || data.code === 31001) this.connected = false;
      throw new Error(`夸克请求未成功（${data.code ?? response.status}），请检查登录状态、账号权益后重试。`);
    }
    return data;
  }

  async connect() {
    await this.list('0', 1);
    this.connected = true;
    return this.status();
  }

  async list(dir = '0', page = 1) {
    if (!/^[\w-]+$/.test(dir)) throw new Error('无效的夸克网盘目录。');
    const data = await this.api('/file/sort', { params: { pdir_fid: dir, _page: page, _size: 100, _fetch_total: 1, _sort: 'file_type:asc,file_name:asc' } });
    return {
      files: (data.data?.list || []).map(f => ({ provider: 'quark', id: String(f.fid), dir: String(f.fid), name: f.file_name, isDir: f.file === false || f.dir === true, category: f.category, size: f.size, modifiedAt: f.updated_at })).filter(videoFile),
      hasMore: data.metadata?._total != null ? page * 100 < data.metadata._total : data.data?.list?.length === 100,
    };
  }

  async sources(id) {
    if (!/^[\w-]+$/.test(id)) throw new Error('无效的夸克文件编号。');
    const sources = [];
    const notices = [];
    const cookie = await this.getCookie();
    const headers = { Cookie: cookie, Referer: 'https://pan.quark.cn/', 'User-Agent': this.userAgent };
    try {
      const data = await this.api('/file/download', { body: { fids: [id] }, cookie });
      if (data.data?.[0]?.download_url) sources.push({ label: '原画', url: data.data[0].download_url, headers });
    } catch (error) { notices.push(error.message); }
    try {
      const data = await this.api('/file/v2/play/project', { body: { fid: id, resolutions: 'low,normal,high,super,2k,4k', supports: 'fmp4_av,m3u8' } });
      for (const item of data.data?.video_list || []) {
        const info = item.video_info;
        if (!info?.url || info.success === false || info.finish === false) continue;
        const label = info.height ? `${info.height}P` : ({ low: '流畅', normal: '480P', high: '720P', super: '1080P', '2k': '2K', '4k': '4K' }[item.resolution] || item.resolution || '高清');
        if (!sources.some(s => s.label === label)) sources.push({ label, url: info.url, headers: { Referer: headers.Referer, 'User-Agent': this.userAgent }, hls: /m3u8/i.test(info.format || '') || /\.m3u8(?:\?|$)/i.test(info.url) });
      }
    } catch (error) { notices.push(error.message); }
    if (!sources.length) throw new Error(notices[0] || '夸克未返回可播放资源，请检查转码状态及账号权益。');
    return { sources, notice: notices.length ? '部分播放资源暂不可用，已显示成功取得的画质。' : '' };
  }
}

module.exports = { BaiduDrive, BaiduWebDrive, QuarkDrive, VIDEO_EXT };
