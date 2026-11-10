const test = require('node:test');
const assert = require('node:assert/strict');
const { browserUserAgent, allowLoginPage } = require('../src/drive-login.cjs');

test('中文应用名必须移出浏览器请求头，网盘 API 可构造有效 HTTP 请求', () => {
  const original = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) 澄映播放器/0.1.1 Chrome/156.0.8078.80 Electron/45.2.3 Safari/537.36';
  assert.throws(() => new Headers({ 'User-Agent': original }), TypeError);
  const userAgent = browserUserAgent(original, '澄映播放器', '0.1.1');
  assert.ok(userAgent.includes('Chrome/156.0.8078.80'));
  assert.ok(!userAgent.includes('澄映') && !userAgent.includes('Electron'));
  assert.equal(new Headers({ 'User-Agent': userAgent }).get('User-Agent'), userAgent);
});

test('登录导航只允许对应网盘的 HTTPS 页面，拒绝假域名和本机地址', () => {
  assert.equal(allowLoginPage('https://passport.baidu.com/v2/', 'baidu'), true);
  assert.equal(allowLoginPage('https://pan.quark.cn/', 'quark'), true);
  assert.equal(allowLoginPage('https://id.uc.cn/login', 'quark'), true);
  for (const url of ['https://baidu.com.attacker.test/', 'http://pan.baidu.com/', 'file:///C:/test', 'https://127.0.0.1/', 'https://pan.baidu.com:1234/', 'https://name:secret@pan.baidu.com/']) assert.equal(allowLoginPage(url, 'baidu'), false);
  assert.equal(allowLoginPage('https://pan.baidu.com/', 'quark'), false);
});
