// Explicit real-account check on the connected phone. Results contain metadata only.
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const adb = path.resolve(__dirname, '../../android-build-tools/sdk/platform-tools/adb.exe');
const play = process.argv.includes('--play');
const reported = process.argv.includes('--reported');
const option = name => process.argv.find(a => a.startsWith(`--${name}=`))?.split('=').slice(1).join('=');
const args = ['-d', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', reported ? 'local.clearplay.android.ReportedPlaybackTest' : 'local.clearplay.android.QuarkAccountTest', '-e', 'clearplayAccountCheck', 'true'];
if (option('provider')) args.push('-e', 'clearplayProvider', option('provider'));
if (option('sample') && /^\d+$/.test(option('sample'))) args.push('-e', 'clearplaySample', option('sample'));
if (process.argv.includes('--strict')) args.push('-e', 'clearplayStrictPlayback', 'true');
if (option('target')) args.push('-e', 'clearplayTarget', Buffer.from(option('target')).toString('base64url'));
if (option('quality')) args.push('-e', 'clearplayQuality', option('quality'));
if (option('seek') && /^\d+$/.test(option('seek'))) args.push('-e', 'clearplaySeekMs', option('seek'));
if (option('speed') && /^[.\d]+$/.test(option('speed'))) args.push('-e', 'clearplaySpeed', option('speed'));
if (option('duration') && /^\d+$/.test(option('duration'))) args.push('-e', 'clearplayDurationMs', option('duration'));
if (process.argv.includes('--cold-auth')) args.push('-e', 'clearplayColdAuth', 'true');
if (process.argv.includes('--sync-cookies')) args.push('-e', 'clearplaySyncCookies', 'true');
if (process.argv.includes('--ignore-phantom')) args.push('-e', 'clearplayIgnorePhantom', 'true');
if (process.argv.includes('--range-probe')) args.push('-e', 'clearplayRangeProbe', 'true');
if (process.argv.includes('--refresh')) args.push('-e', 'clearplayRefreshCheck', 'true');
if (play) args.push('-e', 'clearplayPlayCheck', 'true');
args.push('local.clearplay.player.android.test/androidx.test.runner.AndroidJUnitRunner');
let opened = false, output = '';
const test = cp.spawn(adb, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
const timeout = 90000 + (/^\d+$/.test(option('duration') || '') ? Number(option('duration')) : 0);
const timer = setTimeout(() => { console.error('Phone check exceeded its deadline.'); test.kill(); process.exitCode = 1; }, timeout);
test.stdout.on('data', data => {
  const text = data.toString(); output += text; process.stdout.write(text);
  if (play && !opened && output.includes('INSTRUMENTATION_STATUS_CODE: 1')) {
    opened = true;
    // Release apps need an explicit foreground launch on some Android variants.
    cp.execFile(adb, ['-d', 'shell', 'am', 'start', '-W', '-n', 'local.clearplay.player.android/local.clearplay.android.MainActivity'], { windowsHide: true }, error => {
      console.log(error ? 'Foreground launch failed.' : 'Foreground player launch requested.');
    });
  }
});
test.stderr.on('data', data => process.stderr.write(data));
test.on('close', code => {
  clearTimeout(timer);
  const directory = path.resolve(__dirname, '../test-results/android'); fs.mkdirSync(directory, { recursive: true });
  const mode = process.argv.includes('--ignore-phantom') ? 'ignore-aac' : process.argv.includes('--sync-cookies') ? 'sync' : 'baseline';
  const suffix = process.argv.includes('--strict') ? `-sample-${option('sample') || '0'}-strict` : '';
  fs.writeFileSync(path.join(directory, reported ? `reported-${option('provider') || 'quark'}-${play ? 'play-' : ''}${option('quality') || mode}${suffix}.txt` : 'quark-real-playback.txt'), output);
  process.exitCode = code || (output.includes('OK (1 test)') ? 0 : 1);
});
