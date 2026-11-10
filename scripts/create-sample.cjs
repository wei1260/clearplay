const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

async function main() {
  const browsers = path.join(process.env.LOCALAPPDATA || '', 'ms-playwright');
  const cached = fs.existsSync(browsers) ? fs.readdirSync(browsers).filter(n => /^chromium-\d+$/.test(n)).sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1])).flatMap(n => ['chrome-win64/chrome.exe', 'chrome-win/chrome.exe'].map(exe => path.join(browsers, n, exe))).find(fs.existsSync) : undefined;
  const browser = await chromium.launch({ ...(cached ? { executablePath: cached } : {}), headless: true });
  try {
    const page = await browser.newPage();
    const bytes = await page.evaluate(async () => {
      const canvas = document.createElement('canvas');
      canvas.width = 1920;
      canvas.height = 1080;
      const ctx = canvas.getContext('2d');
      const stream = canvas.captureStream(24);
      const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8', videoBitsPerSecond: 2600000 });
      const chunks = [];
      const done = new Promise(resolve => {
        recorder.ondataavailable = event => chunks.push(event.data);
        recorder.onstop = async () => resolve(Array.from(new Uint8Array(await new Blob(chunks).arrayBuffer())));
      });
      function draw(t) {
        const gradient = ctx.createLinearGradient(0, 0, 1920, 1080);
        gradient.addColorStop(0, '#101b25');
        gradient.addColorStop(1, '#384c4b');
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, 1920, 1080);
        ctx.fillStyle = '#dfc8a3';
        ctx.beginPath(); ctx.arc(1400 - t * 2, 290, 105, 0, Math.PI * 2); ctx.fill();
        for (let layer = 0; layer < 5; layer++) {
          ctx.beginPath(); ctx.moveTo(0, 1080);
          for (let x = 0; x <= 1920; x += 5) {
            const y = 590 + layer * 85 + Math.sin(x / (220 + layer * 70) + layer + t / 7) * (80 - layer * 10) + Math.cos(x / 180 + t / 9) * 45;
            ctx.lineTo(x, y);
          }
          ctx.lineTo(1920, 1080); ctx.closePath();
          ctx.fillStyle = ['#59716c', '#465f5b', '#364e4c', '#263d3d', '#192c31'][layer]; ctx.fill();
          ctx.strokeStyle = '#adbca144'; ctx.lineWidth = 1; ctx.stroke();
        }
        ctx.textAlign = 'left'; ctx.fillStyle = '#d6d6c7'; ctx.font = '18px sans-serif';
        ctx.fillText('C L E A R P L A Y   /   O R I G I N A L   S A M P L E', 90, 90);
        ctx.fillStyle = '#f0d2a7'; ctx.font = '64px sans-serif'; ctx.fillText('A MOMENT OF STILLNESS.', 90, 800);
        ctx.fillStyle = '#b5c4bd'; ctx.font = '22px sans-serif'; ctx.fillText('1920 × 1080  /  24 FPS  /  GENERATED LOCALLY', 94, 855);
        ctx.textAlign = 'right'; ctx.fillStyle = '#e8d1b1'; ctx.font = '20px monospace'; ctx.fillText(`${t.toFixed(1)} s`, 1820, 996);
        ctx.fillStyle = '#e8be86'; ctx.fillRect(90, 986, 1500 * (t / 12), 2);
      }
      draw(0);
      recorder.start();
      const start = performance.now();
      await new Promise(resolve => {
        const timer = setInterval(() => {
          const elapsed = (performance.now() - start) / 1000;
          draw(elapsed);
          if (elapsed >= 12) { clearInterval(timer); recorder.stop(); stream.getTracks().forEach(track => track.stop()); resolve(); }
        }, 1000 / 24);
      });
      return done;
    });
    const destination = path.join(__dirname, '../src/assets/sample.webm');
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, Buffer.from(bytes));
    const ffmpeg = process.env.FFMPEG_PATH || (fs.existsSync(browsers) ? fs.readdirSync(browsers).filter(n => n.startsWith('ffmpeg-')).map(n => path.join(browsers, n, 'ffmpeg-win64.exe')).find(fs.existsSync) : undefined);
    if (!ffmpeg) throw new Error('请设置 FFMPEG_PATH 为 FFmpeg 可执行文件，以写入示例视频的时长和索引。');
    const indexed = destination.replace('.webm', '-indexed.webm');
    const result = spawnSync(ffmpeg, ['-y', '-i', destination, '-c', 'copy', indexed], { windowsHide: true, stdio: 'pipe' });
    if (result.status !== 0) throw new Error('示例视频索引生成失败。');
    fs.renameSync(indexed, destination);
    console.log(`Generated 1080P sample (${bytes.length} bytes).`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
