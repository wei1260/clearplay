const { randomUUID } = require('node:crypto');
const { createReadStream } = require('node:fs');
const { stat } = require('node:fs/promises');
const { Readable } = require('node:stream');
const path = require('node:path');

const DOMAINS = {
  baidu: ['baidu.com', 'baidupcs.com', 'baidubce.com', 'bdstatic.com'],
  quark: ['quark.cn', 'quarkcdn.net', 'uc.cn', 'ucweb.com', 'aliyuncs.com'],
};

function validateRemote(value, provider) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || !DOMAINS[provider]?.some(d => url.hostname === d || url.hostname.endsWith(`.${d}`))) {
    throw new Error('网盘返回了不受支持的媒体地址。');
  }
  return url;
}

function byteRange(header, size) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2]) || size === 0) return false;
  let start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
  let end = match[1] && match[2] ? Number(match[2]) : size - 1;
  if (start >= size || start > end || !Number.isSafeInteger(start) || !Number.isSafeInteger(end)) return false;
  return { start, end: Math.min(end, size - 1) };
}

class MediaRegistry {
  constructor({ fetchImpl = fetch } = {}) {
    this.fetch = fetchImpl;
    this.entries = new Map();
    this.error = null;
  }

  clear() { this.entries.clear(); this.error = null; }

  add(entry) {
    if (entry.url) validateRemote(entry.url, entry.provider);
    if (entry.audio) {
      const { audio, ...video } = entry;
      const audioUrl = this.add({ ...audio, provider: entry.provider });
      const videoUrl = this.add(video);
      // 用同一 HLS 时间轴播放独立音轨，倍速、暂停和拖动仍由一个 video 元素处理。
      entry = { master: `#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="${audioUrl}"\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,AUDIO="sound"\n${videoUrl}\n` };
    }
    const id = randomUUID();
    this.entries.set(id, entry);
    return `clearplay://media/${id}`;
  }

  rewriteManifest(text, base, entry) {
    const register = uri => this.add({ ...entry, url: new URL(uri, base).href, file: undefined, hls: /\.m3u8(?:\?|$)/i.test(uri) });
    return text.split('\n').map(line => {
      if (!line.trim()) return line;
      if (line.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, uri) => `URI="${register(uri)}"`);
      return register(line.trim());
    }).join('\n');
  }

  async handle(request) {
    try {
      if (!['GET', 'HEAD'].includes(request.method)) return new Response(null, { status: 405 });
      const entry = this.entries.get(new URL(request.url).pathname.slice(1));
      if (!entry) return new Response('播放会话已结束，请重新打开视频。', { status: 404 });
      if (entry.file) return await this.local(request, entry.file);
      if (entry.master || entry.manifest) return new Response(entry.master || this.rewriteManifest(entry.manifest, entry.url, { ...entry, manifest: undefined }), { headers: { 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': 'clearplay://app' } });
      let url = validateRemote(entry.resolvedUrl || entry.url, entry.provider);
      let upstream;
      const bodyController = new AbortController();
      const openRange = entry.provider === 'baidu' && entry.label === '原画' ? /^bytes=(\d+)-$/.exec(request.headers.get('range') || '') : null;
      const chunkSize = 256 * 1024;
      for (let redirects = 0; redirects < 6; redirects++) {
        const headers = { ...entry.headers, 'Accept-Encoding': 'identity' };
        if (request.headers.has('range')) headers.Range = request.headers.get('range');
        if (openRange) headers.Range = `bytes=${openRange[1]}-${Number(openRange[1]) + chunkSize - 1}`;
        if (entry.provider === 'quark' && !['quark.cn', 'uc.cn', 'ucweb.com'].some(d => url.hostname === d || url.hostname.endsWith(`.${d}`))) delete headers.Cookie;
        if (entry.provider === 'baidu' && !['baidu.com', 'baidupcs.com'].some(d => url.hostname === d || url.hostname.endsWith(`.${d}`))) delete headers.Cookie;
        const connection = new AbortController();
        const timeout = setTimeout(() => connection.abort(), 20000);
        try {
          upstream = await this.fetch(url, { headers, redirect: 'manual', signal: AbortSignal.any([request.signal, bodyController.signal, connection.signal]), method: request.method });
        } finally { clearTimeout(timeout); }
        if (![301, 302, 303, 307, 308].includes(upstream.status)) break;
        const location = upstream.headers.get('location');
        await upstream.body?.cancel();
        if (!location) throw new Error('媒体重定向地址缺失。');
        url = validateRemote(new URL(location, url).href, entry.provider);
        upstream = null;
      }
      if (!upstream) throw new Error('媒体重定向次数过多。');
      const headers = new Headers({ 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': 'clearplay://app' });
      for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
        if (upstream.headers.has(name)) headers.set(name, upstream.headers.get(name));
      }
      if (!upstream.ok) {
        this.error = { httpStatus: upstream.status };
        await upstream.body?.cancel();
        return new Response(null, { status: upstream.status, headers });
      }
      entry.resolvedUrl = url.href;
      const contentType = upstream.headers.get('content-type') || '';
      if (request.method !== 'HEAD' && (entry.hls || /mpegurl/i.test(contentType) || /\.m3u8$/i.test(url.pathname))) {
        const manifest = this.rewriteManifest(await upstream.text(), url, entry);
        headers.delete('content-length');
        headers.set('content-type', 'application/vnd.apple.mpegurl');
        return new Response(manifest, { headers });
      }
      const returnedRange = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(upstream.headers.get('content-range') || '');
      if (request.method === 'GET' && openRange && upstream.status === 206 && returnedRange && Number(returnedRange[1]) === Number(openRange[1]) && Number(returnedRange[2]) < Number(returnedRange[3]) - 1) {
        // 对原画的大范围读取分段取回，浏览器仍获得完整且连续的 Range 响应。
        const start = Number(returnedRange[1]);
        const total = Number(returnedRange[3]);
        let offset = start;
        let reader = upstream.body.getReader();
        headers.set('content-range', `bytes ${start}-${total - 1}/${total}`);
        headers.set('content-length', String(total - start));
        const registry = this;
        const body = new ReadableStream({
          async pull(controller) {
            try {
              while (true) {
                let timer;
                const chunk = await Promise.race([
                  reader.read(),
                  new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('媒体读取超时')), 15000); }),
                ]).finally(() => clearTimeout(timer));
                if (!chunk.done) { offset += chunk.value.byteLength; controller.enqueue(chunk.value); return; }
                if (offset >= total) { controller.close(); return; }
                const next = await registry.handle(new Request(request.url, { headers: { Range: `bytes=${offset}-${Math.min(total - 1, offset + chunkSize - 1)}` }, signal: AbortSignal.any([request.signal, bodyController.signal]) }));
                const range = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(next.headers.get('content-range') || '');
                if (next.status !== 206 || !range || Number(range[1]) !== offset || Number(range[3]) !== total) {
                  await next.body?.cancel();
                  throw new Error('媒体分段响应不完整');
                }
                reader = next.body.getReader();
              }
            } catch {
              bodyController.abort();
              registry.error ||= { message: '视频数据读取中断或超时，请重新获取播放资源。' };
              controller.error(new Error('视频数据读取中断'));
            }
          },
          cancel() { bodyController.abort(); reader.cancel().catch(() => {}); },
        });
        return new Response(body, { status: 206, headers });
      }
      return new Response(upstream.body, { status: upstream.status, headers });
    } catch {
      this.error = { message: '视频资源请求失败，请重新获取播放资源。' };
      return new Response('媒体读取失败，请刷新播放资源或重新连接网盘。', { status: 502 });
    }
  }

  async local(request, file) {
    const { size } = await stat(file);
    const range = byteRange(request.headers.get('range'), size);
    const headers = { 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': 'clearplay://app', 'Content-Type': ({ '.webm': 'video/webm', '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mov': 'video/quicktime', '.mkv': 'video/x-matroska' }[path.extname(file).toLowerCase()] || 'application/octet-stream') };
    if (range === false) return new Response(null, { status: 416, headers: { ...headers, 'Content-Range': `bytes */${size}` } });
    const start = range?.start ?? 0;
    const end = range?.end ?? size - 1;
    headers['Content-Length'] = String(size ? end - start + 1 : 0);
    if (range) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
    const body = request.method === 'HEAD' || size === 0 ? null : Readable.toWeb(createReadStream(file, { start, end, signal: request.signal }));
    return new Response(body, { status: range ? 206 : 200, headers });
  }
}

module.exports = { MediaRegistry, validateRemote, byteRange };
