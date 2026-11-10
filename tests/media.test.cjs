const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { MediaRegistry, byteRange, validateRemote } = require('../src/media.cjs');

test('字节范围：普通、后缀、开放区间、越界、非法多区间', () => {
  assert.deepEqual(byteRange('bytes=2-5', 10), { start: 2, end: 5 });
  assert.deepEqual(byteRange('bytes=-3', 10), { start: 7, end: 9 });
  assert.deepEqual(byteRange('bytes=5-', 10), { start: 5, end: 9 });
  assert.deepEqual(byteRange('bytes=5-99', 10), { start: 5, end: 9 });
  for (const header of ['bytes=10-', 'bytes=9-4', 'bytes=-0', 'bytes=1-2,4-5', 'bytes=-']) assert.equal(byteRange(header, 10), false);
});

test('媒体域名限制阻止本机地址、伪造域名、外部主机、明文 HTTP', () => {
  assert.equal(validateRemote('https://d.pcs.baidu.com/file', 'baidu').hostname, 'd.pcs.baidu.com');
  for (const url of ['http://d.pcs.baidu.com/file', 'https://127.0.0.1/file', 'https://baidu.com.evil.test/file', 'https://evil.test/file', 'https://user:password@pan.baidu.com/file', 'https://pan.baidu.com:8888/file']) assert.throws(() => validateRemote(url, 'baidu'));
});

test('本地视频支持实际范围读取、HEAD 与 416，缺失文件返回可控错误', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'clearplay-range-'));
  try {
    const file = path.join(directory, 'test.mp4');
    await fs.writeFile(file, Buffer.from('0123456789'));
    const registry = new MediaRegistry();
    const url = registry.add({ file });
    const response = await registry.handle(new Request(url, { headers: { Range: 'bytes=3-6' } }));
    assert.equal(response.status, 206);
    assert.equal(response.headers.get('content-range'), 'bytes 3-6/10');
    assert.equal(await response.text(), '3456');
    const head = await registry.handle(new Request(url, { method: 'HEAD' }));
    assert.equal(head.headers.get('content-length'), '10');
    assert.equal(await head.text(), '');
    assert.equal((await registry.handle(new Request(url, { headers: { Range: 'bytes=50-' } }))).status, 416);
    assert.equal((await registry.handle(new Request(registry.add({ file: path.join(directory, 'missing.mp4') })))).status, 502);
    registry.clear();
    assert.equal((await registry.handle(new Request(url))).status, 404);
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});

test('网盘代理透传 Range/206 和媒体流，响应不含 Cookie 或签名链接', async () => {
  const registry = new MediaRegistry({ fetchImpl: async (_url, options) => {
    assert.equal(options.headers.Range, 'bytes=4-6');
    assert.equal(options.headers.Cookie, '__pus=example-only');
    return new Response('456', { status: 206, headers: { 'Content-Range': 'bytes 4-6/10', 'Content-Length': '3', 'Set-Cookie': '__pus=private', 'Location': 'https://dl.quark.cn/signed' } });
  } });
  const url = registry.add({ provider: 'quark', url: 'https://dl.quark.cn/video', headers: { Cookie: '__pus=example-only' } });
  assert.equal(url.includes('example-only'), false);
  const response = await registry.handle(new Request(url, { headers: { Range: 'bytes=4-6' } }));
  assert.equal(response.status, 206);
  assert.equal(response.headers.get('set-cookie'), null);
  assert.equal(response.headers.get('location'), null);
  assert.equal(await response.text(), '456');
});

test('重定向每一跳都验证主机，不向第三方对象存储发送夸克 Cookie', async () => {
  const calls = [];
  const registry = new MediaRegistry({ fetchImpl: async (url, options) => {
    calls.push(String(url));
    if (calls.length === 1) return new Response(null, { status: 302, headers: { Location: 'https://bucket.aliyuncs.com/video' } });
    assert.equal(options.headers.Cookie, undefined);
    return new Response('media');
  } });
  const url = registry.add({ provider: 'quark', url: 'https://dl.quark.cn/video', headers: { Cookie: '__pus=test' } });
  assert.equal(await (await registry.handle(new Request(url))).text(), 'media');
  registry.fetch = async () => new Response(null, { status: 302, headers: { Location: 'https://127.0.0.1/private' } });
  assert.equal((await registry.handle(new Request(url))).status, 502);
});

test('HLS 相对分片、密钥、子播放列表全部改写为本地不透明地址', async () => {
  const registry = new MediaRegistry({ fetchImpl: async () => new Response('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin?private=signature"\nchild.m3u8\n#EXT-X-MAP:URI="init.mp4"\nsegment.ts\n', { headers: { 'content-type': 'application/vnd.apple.mpegurl' } }) });
  const url = registry.add({ provider: 'quark', url: 'https://dl.quark.cn/folder/master.m3u8', headers: {}, hls: true });
  const response = await registry.handle(new Request(url));
  const manifest = await response.text();
  assert.match(manifest, /#EXTM3U/);
  assert.equal(manifest.includes('signature'), false);
  assert.equal(manifest.includes('https://'), false);
  assert.equal((manifest.match(/clearplay:\/\/media\//g) || []).length, 4);
  assert.equal([...registry.entries.values()].filter(entry => entry.hls).length, 2);
});

test('网盘 403 和 416 不误报成功，不透传上游错误正文', async () => {
  const registry = new MediaRegistry({ fetchImpl: async () => new Response('private upstream message', { status: 403 }) });
  const url = registry.add({ provider: 'baidu', url: 'https://d.pcs.baidu.com/file' });
  const response = await registry.handle(new Request(url));
  assert.equal(response.status, 403);
  assert.equal(await response.text(), '');
});

test('预先校验的百度 HLS 清单按原始地址解析分片，不把父清单附给分片', async () => {
  let requested;
  const registry = new MediaRegistry({ fetchImpl: async url => { requested = String(url); return new Response('segment-data'); } });
  const url = registry.add({ provider: 'baidu', url: 'https://pan.baidu.com/api/streaming?bdstoken=test', hls: true, manifest: '#EXTM3U\nhttps://d.pcs.baidu.com/video.ts\n', headers: {} });
  const manifest = await (await registry.handle(new Request(url))).text();
  const segment = manifest.split('\n').find(line => line.startsWith('clearplay://'));
  assert.equal(await (await registry.handle(new Request(segment))).text(), 'segment-data');
  assert.equal(requested, 'https://d.pcs.baidu.com/video.ts');
});

test('独立音频和视频生成单个 HLS 播放入口，两个清单各自改写且不泄露签名', async () => {
  const registry = new MediaRegistry();
  const source = { provider: 'baidu', url: 'https://pan.baidu.com/api/streaming?type=video&secret=test', hls: true, headers: { Cookie: 'BDUSS=test-only' }, manifest: '#EXTM3U\nhttps://d.pcs.baidu.com/video.ts?secret=test\n' };
  const url = registry.add({ ...source, audio: { url: 'https://pan.baidu.com/api/streaming?type=audio&secret=test', hls: true, headers: source.headers, manifest: '#EXTM3U\nhttps://d.pcs.baidu.com/audio.ts?secret=test\n' } });
  const manifest = await (await registry.handle(new Request(url))).text();
  assert.match(manifest, /#EXT-X-MEDIA:TYPE=AUDIO,.*DEFAULT=YES.*URI="clearplay:\/\/media\//);
  assert.match(manifest, /#EXT-X-STREAM-INF:.*AUDIO="sound"/);
  assert.equal(manifest.includes('secret'), false);
  const audio = /URI="([^"]+)"/.exec(manifest)[1];
  const video = manifest.split('\n').find(line => line.startsWith('clearplay://'));
  for (const child of [video, audio]) {
    const text = await (await registry.handle(new Request(child))).text();
    assert.match(text, /^#EXTM3U\nclearplay:\/\/media\//);
    assert.equal(text.includes('secret'), false);
    const segment = text.split('\n')[1];
    const entry = registry.entries.get(new URL(segment).pathname.slice(1));
    assert.equal(entry.headers.Cookie, 'BDUSS=test-only');
    assert.equal(entry.audio, undefined);
    assert.equal(entry.manifest, undefined);
  }
  assert.throws(() => registry.add({ ...source, audio: { url: 'https://evil.test/audio.m3u8' } }));
});

test('百度原画按 256 KB 取回连续数据，只解析一次 CDN 重定向', async () => {
  const file = Buffer.alloc(700000);
  for (let i = 0; i < file.length; i++) file[i] = i % 251;
  let redirects = 0;
  const ranges = [];
  const registry = new MediaRegistry({ fetchImpl: async (url, options) => {
    if (new URL(url).pathname === '/entry') {
      redirects++;
      return new Response(null, { status: 302, headers: { Location: 'https://d.pcs.baidu.com/data' } });
    }
    const range = byteRange(options.headers.Range, file.length);
    ranges.push(range);
    return new Response(file.subarray(range.start, range.end + 1), { status: 206, headers: { 'Content-Type': 'video/mp4', 'Content-Range': `bytes ${range.start}-${range.end}/${file.length}`, 'Content-Length': String(range.end - range.start + 1) } });
  } });
  const url = registry.add({ provider: 'baidu', label: '原画', url: 'https://pan.baidu.com/entry', headers: {} });
  const response = await registry.handle(new Request(url, { headers: { Range: 'bytes=0-' } }));
  assert.equal(response.headers.get('content-range'), `bytes 0-${file.length - 1}/${file.length}`);
  assert.equal(response.headers.get('content-length'), String(file.length));
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), file);
  assert.equal(redirects, 1);
  assert.deepEqual(ranges.map(range => range.start), [0, 262144, 524288]);
});

test('原画后续分段遭到拒绝时中断媒体流，保留具体 HTTP 状态', async () => {
  const total = 500000;
  const registry = new MediaRegistry({ fetchImpl: async (_url, options) => {
    if (options.headers.Range !== 'bytes=0-262143') return new Response(null, { status: 403 });
    return new Response(new Uint8Array(262144), { status: 206, headers: { 'Content-Range': `bytes 0-262143/${total}`, 'Content-Length': '262144' } });
  } });
  const url = registry.add({ provider: 'baidu', label: '原画', url: 'https://d.pcs.baidu.com/video' });
  const response = await registry.handle(new Request(url, { headers: { Range: 'bytes=0-' } }));
  await assert.rejects(response.arrayBuffer(), /中断/);
  assert.equal(registry.error.httpStatus, 403);
});
