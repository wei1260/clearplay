const test = require('node:test');
const assert = require('node:assert/strict');
const { BaiduDrive, BaiduWebDrive, QuarkDrive } = require('../src/providers.cjs');

const json = (data, status = 200) => new Response(JSON.stringify(data), { status });
const auth = { appKey: 'test-app', secretKey: 'test-secret', accessToken: 'test-access', refreshToken: 'test-refresh', expiresAt: 900000 };

test('百度设备授权：权限、轮询间隔、pending 和 slow_down，凭据不返回界面', async () => {
  let time = 1000;
  const calls = [];
  const saved = [];
  const responses = [
    { device_code: 'device-test', user_code: 'ABCD', verification_url: 'https://openapi.baidu.com/device', expires_in: 300, interval: 5 },
    { error: 'authorization_pending' }, { error: 'slow_down' },
    { access_token: 'test-access', refresh_token: 'test-refresh', expires_in: 3600 },
  ];
  const drive = new BaiduDrive({ now: () => time, save: value => saved.push(value), fetchImpl: async url => { calls.push(new URL(url)); return json(responses.shift()); } });
  const result = await drive.begin('app-test', 'secret-test');
  assert.equal(result.userCode, 'ABCD');
  assert.equal(calls[0].searchParams.get('scope'), 'basic,netdisk');
  assert.equal(calls[0].searchParams.has('client_secret'), false);
  assert.equal((await drive.poll()).pending, true);
  await drive.poll();
  assert.equal(calls.length, 2);
  time += 5000;
  assert.equal((await drive.poll()).interval, 10);
  time += 10000;
  const complete = await drive.poll();
  assert.equal(complete.connected, true);
  assert.equal(complete.accessToken, undefined);
  assert.equal(saved[0].accessToken, 'test-access');
  assert.equal(calls[1].searchParams.get('grant_type'), 'device_token');
});

test('取消正在等待响应的百度授权，不写入旧账号凭据', async () => {
  let resolve;
  const saved = [];
  const drive = new BaiduDrive({ now: () => 0, save: value => saved.push(value), fetchImpl: async url => {
    if (new URL(url).pathname.endsWith('/code')) return json({ device_code: 'test', user_code: 'test', verification_url: 'https://openapi.baidu.com/device', expires_in: 300 });
    return new Promise(r => { resolve = r; });
  } });
  await drive.begin('test', 'test');
  const poll = drive.poll();
  drive.cancel();
  resolve(json({ access_token: 'cancelled-token', expires_in: 3600 }));
  await assert.rejects(poll, /已取消/);
  assert.deepEqual(saved, []);
  assert.equal(drive.status().connected, false);
});

test('设备授权过期后停止轮询', async () => {
  let now = 0;
  const drive = new BaiduDrive({ now: () => now, fetchImpl: async () => json({ device_code: 'test', verification_url: 'https://openapi.baidu.com/device', expires_in: 1 }) });
  await drive.begin('test', 'test');
  now = 2000;
  await assert.rejects(drive.poll(), /过期/);
  assert.equal(drive.pending, null);
});

test('百度目录分页保留文件夹、过滤非视频，64 位文件 ID 不丢精度', async () => {
  let request;
  const drive = new BaiduDrive({ credentials: auth, now: () => 0, fetchImpl: async url => {
    request = new URL(url);
    return new Response('{"errno":0,"list":[{"fs_id":9223372036854775807,"server_filename":"电影.mp4","isdir":0,"category":1,"size":20},{"fs_id":2,"server_filename":"资料","path":"/资料","isdir":1},{"fs_id":3,"server_filename":"readme.txt","isdir":0,"category":4}]}');
  } });
  const result = await drive.list('/电影', 2);
  assert.equal(request.searchParams.get('start'), '100');
  assert.equal(request.searchParams.get('limit'), '100');
  assert.equal(result.files.length, 2);
  assert.equal(result.files[0].id, '9223372036854775807');
  assert.equal(result.files[1].isDir, true);
});

test('百度令牌过期：并发请求只续期一次', async () => {
  let refreshCount = 0;
  const drive = new BaiduDrive({ credentials: { ...auth, expiresAt: 0 }, now: () => 1000, fetchImpl: async url => {
    const request = new URL(url);
    if (request.hostname === 'openapi.baidu.com') {
      refreshCount++;
      await new Promise(resolve => setImmediate(resolve));
      return json({ access_token: 'renewed-test', expires_in: 3600 });
    }
    assert.equal(request.searchParams.get('access_token'), 'renewed-test');
    return json({ errno: 0, list: [] });
  } });
  await Promise.all([drive.list('/'), drive.list('/电影')]);
  assert.equal(refreshCount, 1);
});

test('百度原画链接使用 multimedia 元信息接口，错误消息不泄露凭据', async () => {
  const drive = new BaiduDrive({ credentials: auth, now: () => 0, fetchImpl: async url => {
    const request = new URL(url);
    assert.equal(request.pathname, '/rest/2.0/xpan/multimedia');
    assert.equal(request.searchParams.get('fsids'), '[123]');
    return json({ errno: 0, list: [{ dlink: 'https://d.pcs.baidu.com/file/test' }] });
  } });
  const result = await drive.sources('123');
  assert.equal(new URL(result.sources[0].url).searchParams.get('access_token'), 'test-access');
  assert.equal(result.sources[0].headers['User-Agent'], 'pan.baidu.com');
  drive.fetch = async () => json({ errno: 31326, error_msg: 'test-secret' });
  await assert.rejects(drive.sources('123'), error => /账号权益/.test(error.message) && !error.message.includes('test-secret'));
});

function quark(fetchImpl) { return new QuarkDrive({ fetchImpl, getCookie: async () => '__pus=test-cookie', userAgent: 'test-agent' }); }

test('夸克目录分页使用登录会话，并正确识别文件夹', async () => {
  const drive = quark(async (url, options) => {
    const request = new URL(url);
    assert.equal(request.searchParams.get('_page'), '2');
    assert.equal(request.searchParams.get('pdir_fid'), 'folder-test');
    assert.equal(options.headers.Cookie, '__pus=test-cookie');
    return json({ status: 200, code: 0, data: { list: [{ fid: 'a', file_name: '电影', file: false }, { fid: 'b', file_name: '视频.mkv', file: true, category: 1 }, { fid: 'c', file_name: 'a.txt', file: true, category: 4 }] }, metadata: { _total: 250 } });
  });
  const result = await drive.list('folder-test', 2);
  assert.equal(result.files.length, 2);
  assert.equal(result.files[0].isDir, true);
  assert.equal(result.hasMore, true);
});

test('夸克清晰度仅显示接口实际返回的资源，下载沿用请求时的 Cookie', async () => {
  const drive = quark(async (url, options) => {
    if (new URL(url).pathname.endsWith('/download')) {
      assert.deepEqual(JSON.parse(options.body), { fids: ['video-test'] });
      return json({ status: 200, code: 0, data: [{ download_url: 'https://dl.quark.cn/video-test' }] });
    }
    return json({ status: 200, code: 0, data: { video_list: [
      { resolution: 'super', video_info: { url: 'https://dl.quark.cn/1080.m3u8', height: 1080, success: true, finish: true } },
      { resolution: '4k', video_info: { height: 2160, success: false } },
    ] } });
  });
  const result = await drive.sources('video-test');
  assert.deepEqual(result.sources.map(s => s.label), ['原画', '1080P']);
  assert.equal(result.sources[0].headers.Cookie, '__pus=test-cookie');
  assert.equal(result.sources[1].hls, true);
});

test('夸克原画无权限时仍可使用成功返回的转码流', async () => {
  const drive = quark(async url => new URL(url).pathname.endsWith('/download') ? json({ status: 403, code: 32003 }, 403) : json({ status: 200, code: 0, data: { video_list: [{ resolution: 'high', video_info: { url: 'https://dl.quark.cn/720.m3u8' } }] } }));
  const result = await drive.sources('video-test');
  assert.equal(result.sources.length, 1);
  assert.equal(result.sources[0].label, '720P');
  assert.match(result.notice, /部分/);
});

test('夸克未登录或接口拒绝，不能显示连接成功', async () => {
  const drive = quark(async () => json({ status: 401, code: 31001, message: '__pus=secret' }, 401));
  await assert.rejects(drive.connect(), error => !error.message.includes('secret'));
  assert.equal(drive.status().connected, false);
});

test('百度直接登录读取目录，不需要 AppKey/SecretKey/access_token', async () => {
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'Mozilla/5.0 test', fetchImpl: async (url, options) => {
    const request = new URL(url);
    assert.equal(request.pathname, '/api/list');
    assert.equal(request.searchParams.has('access_token'), false);
    assert.equal(request.searchParams.has('client_id'), false);
    assert.equal(options.headers.Cookie, 'BDUSS=test-only');
    return new Response('{"errno":0,"list":[{"fs_id":9223372036854775807,"server_filename":"测试.mp4","path":"/测试.mp4","category":1,"isdir":0}]}');
  } });
  assert.equal((await drive.connect()).connected, true);
  assert.equal((await drive.list('/')).files[0].id, '9223372036854775807');
});

test('百度网页播放只展示实际返回 HLS 的清晰度，不把无权限 JSON 当作视频', async () => {
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'Mozilla/5.0 test', fetchImpl: async url => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({ errno: 0, result: { bdstoken: 'test-session-token' } });
    if (request.pathname.endsWith('filemetas')) return json({ errno: -6 });
    if (request.searchParams.get('type') === 'M3U8_AUTO_1080') return json({ errno: 31341 });
    if (request.searchParams.get('type') === 'M3U8_AUTO_720') return new Response('#EXTM3U\n#EXTINF:6,\nhttps://d.pcs.baidu.com/segment.ts\n');
    return new Response(null, { status: 403 });
  } });
  const result = await drive.sources('123', '/测试.mp4');
  assert.deepEqual(result.sources.map(s => s.label), ['720P']);
  assert.equal(result.sources[0].hls, true);
  assert.equal(result.sources[0].headers.Cookie, 'BDUSS=test-only');
  assert.equal(result.sources[0].url.includes('access_token'), false);
});

test('百度网页画质绑定当前文件的独立音频流，音频准备完整等待且不列为视频画质', async () => {
  const waits = [];
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'test-agent', wait: async ms => waits.push(ms), fetchImpl: async url => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({ errno: 0, result: {} });
    if (request.pathname.endsWith('filemetas')) return json({ errno: 0, info: [] });
    const type = request.searchParams.get('type');
    if (type === 'M3U8_AUTO_720') return new Response('#EXTM3U\n#EXTINF:10,\nhttps://d.pcs.baidu.com/video.ts\n');
    if (type !== 'M3U8_HLS_MP3_128') return json({ errno: 31341 });
    const path = request.searchParams.get('path');
    if (!request.searchParams.has('adToken')) return json({ errno: 133, adTime: 8, adToken: `audio:${path}` });
    assert.equal(request.searchParams.get('adToken'), `audio:${path}`);
    return new Response('#EXTM3U\n#EXTINF:10,\nhttps://d.pcs.baidu.com/audio.ts\n');
  } });
  for (const [id, path] of [['123', '/目录一/视频.mp4'], ['456', '/目录二/另一条.mp4']]) {
    const result = await drive.sources(id, path);
    assert.deepEqual(result.sources.map(source => source.label), ['720P']);
    const audio = result.sources[0].audio;
    assert.equal(new URL(audio.url).searchParams.get('path'), path);
    assert.equal(new URL(audio.url).searchParams.get('type'), 'M3U8_HLS_MP3_128');
    assert.equal(audio.headers.Cookie, 'BDUSS=test-only');
    assert.equal(audio.hls, true);
    assert.match(audio.manifest, /audio.ts/);
  }
  assert.deepEqual(waits, [8000, 8000]);
});

test('百度未完成网页登录不能显示连接成功，提示中不包含 Cookie', async () => {
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'Mozilla/5.0 test', fetchImpl: async () => json({ errno: -6, error_msg: 'BDUSS=test-only' }) });
  await assert.rejects(drive.connect(), error => /登录状态/.test(error.message) && !error.message.includes('test-only'));
  assert.equal(drive.status().connected, false);
});

test('百度网页原画仅使用视频取链的 netdisk User-Agent', async () => {
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'Mozilla/5.0 test', fetchImpl: async url => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({ errno: 0, result: { bdstoken: 'test-session-token' } });
    if (request.pathname.endsWith('filemetas')) return json({ errno: 0, info: [{ dlink: 'https://d.pcs.baidu.com/test' }] });
    if (request.hostname === 'd.pcs.baidu.com') return new Response(new Uint8Array([0]),{status:206,headers:{'Content-Range':'bytes 0-0/1000'}});
    return json({ errno: 133 });
  } });
  const result = await drive.sources('123', '/测试.mp4');
  assert.equal(result.sources[0].headers['User-Agent'], 'netdisk');
  assert.equal(result.sources[0].headers.Cookie, 'BDUSS=test-only');
});

test('百度视频专用取链验证首字节后使用 netdisk，目录切换独立取链', async () => {
  const paths = [];
  const drive = new BaiduWebDrive({getCookie:async () => 'BDUSS=test-only',userAgent:'browser',fetchImpl:async (url,options) => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({errno:0,result:{bdstoken:'session'}});
    if (request.pathname.endsWith('filemetas')) {
      assert.equal(request.searchParams.get('web'),'5'); assert.equal(request.searchParams.get('origin'),'dlna');
      paths.push(JSON.parse(request.searchParams.get('target'))[0]);
      return json({errno:0,info:[{dlink:'http://d.pcs.baidu.com/video'}]});
    }
    if (request.hostname === 'd.pcs.baidu.com') {
      assert.equal(request.protocol,'https:'); assert.equal(options.headers['User-Agent'],'netdisk');
      assert.equal(options.headers.Range,'bytes=0-0');
      return new Response(new Uint8Array([0]),{status:206,headers:{'Content-Range':'bytes 0-0/1000'}});
    }
    return json({errno:31341});
  }});
  for (const dir of ['/目录一/a.mp4','/目录二/b.mp4']) {
    const result = await drive.sources('123',dir);
    assert.equal(result.sources[0].headers['User-Agent'],'netdisk');
    assert.equal(result.sources[0].headers.Cookie,'BDUSS=test-only');
  }
  assert.deepEqual(paths,['/目录一/a.mp4','/目录二/b.mp4']);
});

test('百度视频专用链接返回 403 时不再请求普通原画', async () => {
  let calls=0;
  const drive = new BaiduWebDrive({getCookie:async () => 'BDUSS=test-only',userAgent:'browser',fetchImpl:async url => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({errno:0,result:{}});
    if (request.pathname.endsWith('filemetas')) { calls++; assert.equal(request.searchParams.get('origin'),'dlna'); return json({errno:0,info:[{dlink:'https://d.pcs.baidu.com/denied'}]}); }
    if (request.hostname === 'd.pcs.baidu.com') return new Response(null,{status:403});
    return json({errno:31341});
  }});
  await assert.rejects(drive.sources('123','/test.mp4'),/未返回可播放资源/);
  assert.equal(calls,1);
});

test('百度每条视频独立处理 133 准备响应，完整等待后携带其 adToken 重试', async () => {
  const waits = [];
  const notices = [];
  const retriedPaths = [];
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'Mozilla/5.0 test', wait: async ms => waits.push(ms), fetchImpl: async url => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({ errno: 0, result: { bdstoken: 'test-session-token' } });
    if (request.pathname.endsWith('filemetas')) return json({ errno: 0, info: [] });
    if (request.searchParams.get('type') !== 'M3U8_AUTO_720') return json({ errno: 2 });
    const path = request.searchParams.get('path');
    if (!request.searchParams.has('adToken')) return json({ errno: 133, adTime: 8, adToken: `test-preparation:${path}` });
    assert.equal(request.searchParams.get('adToken'), `test-preparation:${path}`);
    retriedPaths.push(path);
    return new Response('#EXTM3U\n#EXTINF:6,\nhttps://d.pcs.baidu.com/segment.ts\n');
  } });
  const first = await drive.sources('123', '/目录一/视频.mp4', { onPreparing: seconds => notices.push(seconds) });
  const second = await drive.sources('456', '/目录二/另一条.mp4', { onPreparing: seconds => notices.push(seconds) });
  assert.deepEqual(first.sources.map(source => source.label), ['720P']);
  assert.deepEqual(second.sources.map(source => source.label), ['720P']);
  assert.deepEqual(waits, [8000, 8000]);
  assert.deepEqual(notices, [8, 8]);
  assert.deepEqual(retriedPaths, ['/目录一/视频.mp4', '/目录二/另一条.mp4']);
});

test('百度准备响应缺少凭据或等待时间无效，不猜造参数重试', async () => {
  let waits = 0;
  const drive = new BaiduWebDrive({ getCookie: async () => 'BDUSS=test-only', userAgent: 'Mozilla/5.0 test', wait: async () => waits++, fetchImpl: async url => {
    const request = new URL(url);
    if (request.pathname.endsWith('gettemplatevariable')) return json({ errno: 0, result: {} });
    if (request.pathname.endsWith('filemetas')) return json({ errno: 0, info: [{ dlink: 'https://d.pcs.baidu.com/test' }] });
    if (request.hostname === 'd.pcs.baidu.com') return new Response(new Uint8Array([0]),{status:206,headers:{'Content-Range':'bytes 0-0/1000'}});
    return json({ errno: 133, adTime: 8 });
  } });
  const result = await drive.sources('123', '/视频.mp4');
  assert.deepEqual(result.sources.map(source => source.label), ['原画']);
  assert.equal(waits, 0);
});
