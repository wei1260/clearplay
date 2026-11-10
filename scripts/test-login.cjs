const { _electron: electron } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');

async function main() {
  const root = path.resolve(__dirname, '..');
  const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'clearplay-login-test-'));
  const output = path.join(root, 'test-results');
  await fs.mkdir(output, { recursive: true });
  const env = { ...process.env, CLEARPLAY_USER_DATA: temporary };
  delete env.ELECTRON_RUN_AS_NODE;
  let desktop;
  const checks = [];
  try {
    desktop = await electron.launch({ ...(process.env.CLEARPLAY_PACKAGED_EXE ? { executablePath: process.env.CLEARPLAY_PACKAGED_EXE, args: [] } : { args: [root] }), env });
    const mainPage = await desktop.firstWindow();
    await mainPage.waitForFunction(() => !!window.clearplay && document.querySelector('.library-empty'));
    for (const provider of ['baidu', 'quark']) {
      await mainPage.locator(`[data-view="${provider}"]`).click();
      await mainPage.locator('#connect-drive').click();
      if (provider === 'baidu') {
        assert.equal(await mainPage.locator('#app-key').isVisible(), false);
        await mainPage.screenshot({ path: path.join(output, 'baidu-direct-login.png') });
      }
      await desktop.evaluate(({ session }, provider) => {
        const filter = provider === 'baidu' ? 'https://pan.baidu.com/*' : 'https://pan.quark.cn/*';
        session.fromPartition(`persist:clearplay-${provider}`).webRequest.onBeforeRequest({ urls: [filter] }, (_details, callback) => callback({ cancel: true }));
      }, provider);
      await mainPage.locator(`#login-${provider}`).click();
      let loginPage;
      const deadline = Date.now() + 15000;
      while (!loginPage && Date.now() < deadline) {
        loginPage = desktop.context().pages().find(page => page.url().startsWith('clearplay://app/drive-login.html') && page.url().includes(provider));
        if (!loginPage) await new Promise(resolve => setTimeout(resolve, 100));
      }
      assert.ok(loginPage, '未找到登录窗口');
      await loginPage.locator('#retry').waitFor({ state: 'visible', timeout: 25000 });
      assert.match(await loginPage.locator('#detail').innerText(), /ERR_/);
      assert.ok(await loginPage.locator('body').innerText(), '不应白屏');
      await loginPage.screenshot({ path: path.join(output, `${provider}-login-error.png`) });
      checks.push(`${provider}: 网络请求失败显示错误和重试按钮`);
      await desktop.evaluate(({ session }, provider) => session.fromPartition(`persist:clearplay-${provider}`).webRequest.onBeforeRequest(null), provider);
      await loginPage.locator('#retry').click();
      await loginPage.waitForFunction(() => document.querySelector('#status').textContent.includes('官网已加载'), { timeout: 30000 });
      const result = await desktop.evaluate(async ({ webContents }, provider) => {
        const host = provider === 'baidu' ? 'baidu.com' : 'quark.cn';
        const remote = webContents.getAllWebContents().find(contents => {
          try { return new URL(contents.getURL()).protocol === 'https:' && new URL(contents.getURL()).hostname.endsWith(host); } catch { return false; }
        });
        if (!remote) return null;
        const preferences = remote.getLastWebPreferences();
        return {
          hasLogin: await remote.executeJavaScript('document.body.innerText.includes("登录")'),
          leakedBridge: await remote.executeJavaScript('typeof window.driveLogin !== "undefined"'),
          sandbox: preferences.sandbox, contextIsolation: preferences.contextIsolation, nodeIntegration: preferences.nodeIntegration, preload: !!preferences.preload,
          asciiUserAgent: /^[\x20-\x7e]+$/.test(remote.getUserAgent()),
        };
      }, provider);
      assert.ok(result?.hasLogin, '官网必须实际呈现登录内容');
      assert.equal(result.leakedBridge, false);
      assert.equal(result.sandbox, true);
      assert.equal(result.contextIsolation, true);
      assert.equal(result.nodeIntegration, false);
      assert.equal(result.preload, false);
      assert.equal(result.asciiUserAgent, true);
      checks.push(`${provider}: 网络恢复后重试成功，官网登录内容真实显示，隔离设置正确`);
      await loginPage.locator('#back').click();
      await mainPage.locator('#close-connect').click();
    }
    const packaged = await desktop.evaluate(({ app }) => app.isPackaged);
    await fs.writeFile(path.join(output, packaged ? 'login-report-packaged.json' : 'login-report.json'), JSON.stringify({ packaged, checks, realAccountAuthenticated: false }, null, 2));
    console.log(JSON.stringify({ packaged, checks, realAccountAuthenticated: false }, null, 2));
  } finally {
    if (desktop) await desktop.close();
    await fs.rm(temporary, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
