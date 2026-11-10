const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

async function main() {
  const cache = path.join(process.env.LOCALAPPDATA, 'ms-playwright');
  const executablePath = fs.readdirSync(cache).filter(n => /^chromium-\d+$/.test(n)).sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]))
    .flatMap(n => ['chrome-win64/chrome.exe', 'chrome-win/chrome.exe'].map(p => path.join(cache, n, p))).find(fs.existsSync);
  const browser = await chromium.launch({ executablePath, headless: true, args: ['--autoplay-policy=no-user-gesture-required'] });
  try {
    const page = await browser.newPage();
    const bytes = await page.evaluate(async () => {
      const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 360;
      const ctx = canvas.getContext('2d'), stream = canvas.captureStream(15);
      const audio = new AudioContext(); await audio.resume();
      const tone = audio.createOscillator(), gain = audio.createGain(), destination = audio.createMediaStreamDestination();
      tone.frequency.value = 440; gain.gain.value = .1;
      tone.connect(gain); gain.connect(destination); tone.start(); stream.addTrack(destination.stream.getAudioTracks()[0]);
      const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8,opus', videoBitsPerSecond: 400000, audioBitsPerSecond: 64000 });
      const chunks = [];
      const done = new Promise(resolve => {
        recorder.ondataavailable = e => chunks.push(e.data);
        recorder.onstop = async () => resolve([...new Uint8Array(await new Blob(chunks).arrayBuffer())]);
      });
      recorder.start(); const start = performance.now();
      await new Promise(resolve => {
        const timer = setInterval(() => {
          const time = (performance.now() - start) / 1000;
          ctx.fillStyle = '#101217'; ctx.fillRect(0, 0, 640, 360); ctx.fillStyle = '#eebd83'; ctx.font = '28px sans-serif';
          ctx.fillText('CLEARPLAY AUDIO + VIDEO', 35, 150); ctx.fillText(`${time.toFixed(1)} s / 440 Hz`, 35, 200);
          ctx.fillRect(35, 280, 570 * time / 12, 5);
          if (time >= 12) { clearInterval(timer); recorder.stop(); tone.stop(); stream.getTracks().forEach(t => t.stop()); resolve(); }
        }, 1000 / 15);
      });
      const result = await done; await audio.close(); return result;
    });
    const output = path.resolve(__dirname, '../android/app/src/androidTest/assets/audio-video.webm');
    fs.mkdirSync(path.dirname(output), { recursive: true }); fs.writeFileSync(output, Buffer.from(bytes));
    const ffmpeg = fs.readdirSync(cache).filter(n => n.startsWith('ffmpeg-')).map(n => path.join(cache, n, 'ffmpeg-win64.exe')).find(fs.existsSync);
    if (!ffmpeg) throw new Error('Playwright FFmpeg is needed to index the Android test fixture.');
    const indexed = output.replace('.webm', '-indexed.webm');
    const result = spawnSync(ffmpeg, ['-y', '-i', output, '-c', 'copy', indexed], { windowsHide: true });
    if (result.status !== 0) throw new Error(result.stderr.toString().slice(-800));
    fs.renameSync(indexed, output); console.log(`Generated test-only audiovisual fixture (${fs.statSync(output).size} bytes).`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
