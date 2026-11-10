// Bounded real-account benchmark. All account/media bytes remain inside the phone.
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const adb = path.resolve(__dirname, '../../android-build-tools/sdk/platform-tools/adb.exe');
const options = Object.fromEntries(process.argv.slice(2).map(arg => arg.replace(/^--/, '').split('=')));
if (!['baidu', 'quark'].includes(options.provider)) throw new Error('Specify --provider=baidu or --provider=quark');
const args = ['-d', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'local.clearplay.android.ThroughputTest', '-e', 'clearplayAccountCheck', 'true', '-e', 'clearplayProvider', options.provider];
for (const key of ['sample', 'mib', 'segment', 'seconds', 'rounds', 'workers']) {
  if (options[key] !== undefined) {
    if (!/^\d+$/.test(options[key])) throw new Error(`Invalid ${key}`);
    args.push('-e', key, options[key]);
  }
}
if (options.route) {
  if (!/^[a-z-]+$/.test(options.route)) throw new Error('Invalid route');
  args.push('-e', 'route', options.route);
}
args.push('local.clearplay.player.android.test/androidx.test.runner.AndroidJUnitRunner');
const rows = [];
let pending = '', output = '', foreground = false;
const child = cp.spawn(adb, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
const timer = setTimeout(() => {
  console.error('Benchmark deadline exceeded; stopping its app process.');
  cp.execFile(adb, ['-d', 'shell', 'am', 'force-stop', 'local.clearplay.player.android'], { windowsHide: true }, () => child.kill());
  process.exitCode = 1;
}, 15 * 60 * 1000);
child.stdout.on('data', data => {
  const text = data.toString(); output += text; pending += text;
  const lines = pending.split(/\r?\n/); pending = lines.pop();
  for (const line of lines) {
    if (!foreground && /INSTRUMENTATION_STATUS_CODE: 1/.test(line)) {
      foreground = true;
      cp.execFile(adb, ['-d', 'shell', 'am', 'start', '-W', '-n', 'local.clearplay.player.android/local.clearplay.android.MainActivity'], { windowsHide: true }, error => {
        if (error) console.error('Could not foreground the test app.');
      });
    }
    const marker = line.indexOf('CLEARPLAY_THROUGHPUT=');
    if (marker >= 0) {
      try { const row = JSON.parse(line.slice(marker + 'CLEARPLAY_THROUGHPUT='.length)); rows.push(row); console.log(JSON.stringify(row)); }
      catch { console.error('Invalid diagnostic result'); }
    } else if (/FAILURES|OK \(|INSTRUMENTATION_FAILED|Error:/.test(line)) console.log(line);
  }
});
child.stderr.on('data', data => process.stderr.write(data));
child.on('close', code => {
  clearTimeout(timer);
  const folder = path.resolve(__dirname, '../test-results/android/throughput'); fs.mkdirSync(folder, { recursive: true });
  const passed = code === 0 && output.includes('OK (1 test)');
  const file = path.join(folder, `${new Date().toISOString().replace(/[:.]/g, '-')}-${options.provider}.json`);
  fs.writeFileSync(file, JSON.stringify({ timestamp: new Date().toISOString(), options, passed, rows }, null, 2));
  console.log(`Report: ${file}`);
  process.exitCode = passed && rows.length > 0 ? 0 : 1;
});
