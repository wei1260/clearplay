const { _electron: electron } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');

async function main() {
  const root = path.resolve(__dirname, '..');
  const output = path.join(root, 'test-results');
  await fs.mkdir(output, { recursive: true });
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), 'clearplay-desktop-'));
  const env = { ...process.env, CLEARPLAY_USER_DATA: userData };
  delete env.ELECTRON_RUN_AS_NODE;
  let desktop;
  const results = [];
  const check = async (name, fn) => { await fn(); results.push(name); console.log(`PASS ${name}`); };
  const launch = async () => {
    desktop = await electron.launch({ args: [root], cwd: root, env, timeout: 45000 });
    const page = await desktop.firstWindow();
    page.setDefaultTimeout(15000);
    return page;
  };
  try {
    const page = await launch();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.waitForFunction(() => !!window.clearplay && document.querySelector('.library-empty'));
    await check('首页、百度/夸克/本地/历史导航，初始无伪造影片', async () => {
      assert.equal(await page.locator('.file-row').count(), 0);
      await page.screenshot({ path: path.join(output, '01-home.png'), fullPage: true });
      await page.locator('[data-view="quark"]').click();
      await page.getByRole('heading', { name: '你的夸克网盘，也是你的片库' }).waitFor();
      await page.locator('[data-view="recent"]').click();
      await page.getByRole('heading', { name: '每次回来，接着上次看' }).waitFor();
      await page.locator('[data-view="local"]').click();
      await page.getByRole('heading', { name: '把本地收藏，也带进来' }).waitFor();
      await page.locator('[data-view="baidu"]').click();
    });
    await check('网盘连接对话框、切换夸克、未登录错误、关闭与取消', async () => {
      await page.locator('#connect-drive').click();
      await page.locator('#login-baidu').waitFor({ state: 'visible' });
      assert.equal(await page.locator('#app-key').isVisible(), false);
      await page.locator('#baidu-advanced summary').click();
      await page.locator('#app-key').waitFor({ state: 'visible' });
      await page.locator('#tab-quark').click();
      await page.locator('#finish-quark').click();
      await page.locator('#connect-error').waitFor({ state: 'visible' });
      assert.match(await page.locator('#connect-error').innerText(), /登录/);
      await page.locator('#close-connect').click();
      await page.locator('#connect-dialog').waitFor({ state: 'hidden' });
      await page.locator('#connect-drive').click();
      await page.evaluate(() => { state.accounts.baidu = { connected: true, mode: 'oauth' }; state.authProvider = 'baidu'; renderConnect(); });
      await page.locator('#switch-baidu-web').waitFor({ state: 'visible' });
      await page.locator('#switch-baidu-web').click();
      await page.locator('#login-baidu').waitFor({ state: 'visible' });
      assert.equal(await page.locator('#app-key').isVisible(), false);
      await page.evaluate(() => { state.accounts.baidu = { connected: false, mode: 'web' }; });
      await page.locator('#close-connect').click();
    });
    await check('1080P 示例实际解码、播放和暂停', async () => {
      await page.locator('#play-sample').click();
      await page.waitForFunction(() => {
        const v = document.querySelector('video');
        return v.videoWidth === 1920 && v.videoHeight === 1080 && Number.isFinite(v.duration) && v.duration > 10 && !v.paused;
      });
      await page.locator('#toggle-play').click();
      assert.equal(await page.locator('video').evaluate(v => v.paused), true);
    });
    await check('倍速实际生效、4× 上限、进度拖动与音量', async () => {
      await page.locator('#speed').selectOption('4');
      assert.equal(await page.locator('video').evaluate(v => v.playbackRate), 4);
      await page.locator('#speed').selectOption('2');
      await page.locator('#seek').evaluate(input => { input.value = '250'; input.dispatchEvent(new Event('input')); input.dispatchEvent(new Event('change')); });
      await page.waitForFunction(() => document.querySelector('video').currentTime >= 2.8);
      const before = await page.locator('video').evaluate(v => v.currentTime);
      await page.locator('#toggle-play').click();
      await page.waitForTimeout(600);
      await page.locator('#toggle-play').click();
      const after = await page.locator('video').evaluate(v => v.currentTime);
      assert.ok(after - before > 0.7 && after - before < 3, `2× 时间推进异常：${after - before}`);
      await page.locator('#volume').evaluate(input => { input.value = '0.4'; input.dispatchEvent(new Event('input')); });
      assert.equal(await page.locator('video').evaluate(v => v.volume), 0.4);
      await page.locator('#toggle-mute').click();
      assert.equal(await page.locator('video').evaluate(v => v.muted), true);
      await page.screenshot({ path: path.join(output, '02-player.png'), fullPage: true });
    });
    await check('全屏与快捷键帮助', async () => {
      await page.locator('#fullscreen').click();
      await page.waitForFunction(() => !!document.fullscreenElement);
      await page.locator('#fullscreen').click();
      await page.waitForFunction(() => !document.fullscreenElement);
      await page.locator('#show-shortcuts').click();
      await page.locator('#shortcuts-dialog').waitFor({ state: 'visible' });
      await page.locator('#close-shortcuts').click();
    });
    await check('本地多视频导入、播放队列、历史进度与断点续播', async () => {
      const sample = path.join(root, 'src/assets/sample.webm');
      const second = path.join(userData, '第二部测试视频.webm');
      await fs.copyFile(sample, second);
      await desktop.evaluate(({ dialog }, { sample, second }) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [sample, second] }); }, { sample, second });
      await page.locator('#open-local').click();
      await page.waitForFunction(() => document.querySelector('#now-detail').textContent.includes('1920'));
      assert.equal(await page.locator('.queue-item').count(), 2);
      if (!await page.locator('video').evaluate(v => v.paused)) await page.locator('#toggle-play').click();
      await page.locator('video').evaluate(v => { v.currentTime = 5; });
      await page.waitForTimeout(200);
      await page.locator('#next-video').click();
      await page.waitForFunction(() => document.querySelector('#now-title').textContent === '第二部测试视频.webm' && document.querySelector('video').readyState >= 2);
      await page.locator('.queue-item').first().click();
      await page.waitForFunction(() => document.querySelector('#now-title').textContent === 'sample.webm' && document.querySelector('video').currentTime >= 4.8);
      await page.locator('#toggle-play').click();
      await page.locator('[data-view="recent"]').click();
      await page.locator('.file-row').first().waitFor();
      assert.equal(await page.locator('.file-row').count(), 2);
      assert.match(await page.locator('#file-list').innerText(), /已看到/);
    });
    await check('夸克目录/搜索/画质切换保持进度（适配器模拟，视频实际解码）', async () => {
      await desktop.evaluate(({ app }) => {
        const require = process.getBuiltinModule('module').createRequire(app.getAppPath() + '/package.json');
        const path = require('node:path');
        const { QuarkDrive } = require(path.join(app.getAppPath(), 'src/providers.cjs'));
        QuarkDrive.prototype.status = () => ({ connected: true, name: '夸克网盘' });
        QuarkDrive.prototype.connect = async function () { return this.status(); };
        QuarkDrive.prototype.list = async () => ({ files: [{ provider: 'quark', id: 'mock-video', name: '适配器测试影片.webm', isDir: false }], hasMore: false });
        QuarkDrive.prototype.sources = async () => ({ sources: [{ label: '原画', file: path.join(app.getAppPath(), 'src/assets/sample.webm') }, { label: '1080P', file: path.join(app.getAppPath(), 'src/assets/sample.webm') }] });
      });
      await page.locator('[data-view="quark"]').click();
      await page.locator('#connect-drive').click();
      await page.locator('#finish-quark').click();
      await page.locator('.file-row').first().waitFor();
      await page.locator('#search').fill('不存在的视频');
      await page.getByRole('heading', { name: '没有找到匹配的视频' }).waitFor();
      await page.locator('#search').fill('');
      await page.locator('.file-row').first().click();
      await page.waitForFunction(() => document.querySelector('video').readyState >= 2 && document.querySelector('#quality').options.length === 2);
      if (!await page.locator('video').evaluate(v => v.paused)) await page.locator('#toggle-play').click();
      await page.locator('video').evaluate(v => { v.currentTime = 5; });
      await page.locator('#quality').selectOption('1');
      await page.waitForFunction(() => document.querySelector('video').readyState >= 2 && document.querySelector('video').currentTime >= 4.8);
      assert.equal(await page.locator('video').evaluate(v => v.paused), true);
      assert.equal(await page.locator('video').evaluate(v => v.playbackRate), 2);
    });
    await check('取流失败显示错误并可重新取得资源', async () => {
      await desktop.evaluate(({ app }) => { const require = process.getBuiltinModule('module').createRequire(app.getAppPath() + '/package.json'); const { QuarkDrive } = require(require('node:path').join(app.getAppPath(), 'src/providers.cjs')); QuarkDrive.prototype.sources = async () => { throw new Error('测试：资源已过期'); }; });
      await page.locator('.file-row').first().click();
      await page.locator('#retry-play').waitFor({ state: 'visible' });
      assert.match(await page.locator('#player-message-detail').innerText(), /资源已过期/);
    });
    assert.deepEqual(pageErrors, [], '界面有未处理异常');
    await desktop.close(); desktop = null;
    const restarted = await launch();
    await check('重启后倍速/音量/本地记录持久化', async () => {
      await restarted.waitForFunction(() => !!window.clearplay && document.querySelector('.library-empty'));
      assert.equal(await restarted.locator('#speed').inputValue(), '2');
      assert.equal(await restarted.locator('#volume').inputValue(), '0.4');
      await restarted.locator('[data-view="local"]').click();
      await restarted.locator('.file-row').first().waitFor();
      assert.equal(await restarted.locator('.file-row').count(), 2);
    });
    await fs.writeFile(path.join(output, 'desktop-report.json'), JSON.stringify({ passed: results, realCloudAccountValidated: false }, null, 2));
    console.log(`Desktop checks passed: ${results.length}. Screenshots: test-results/`);
  } finally {
    if (desktop) await desktop.close();
    await fs.rm(userData, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
