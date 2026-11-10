const cp = require('node:child_process'), fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const root = path.resolve(__dirname, '..'), out = path.join(root, 'test-results/android');
const tools = path.resolve(root, '../android-build-tools');
const adb = path.join(tools, 'sdk/platform-tools/adb.exe');
const run = (file,args) => cp.execFileSync(file,args,{windowsHide:true,encoding:'utf8'}).trim();
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const version = fs.readFileSync(path.join(root, 'android/app/build.gradle'), 'utf8').match(/versionName '([^']+)'/)[1];
const apk = path.join(root, `release/Clearplay-${version}.apk`);
const staged = path.join(tools, 'work/clearplay-android/app/build/outputs/apk/release/app-release.apk');
const jdk=fs.readdirSync(path.join(tools,'jdk21'),{withFileTypes:true}).find(e=>e.isDirectory()).name;
const signature=run(path.join(tools,'jdk21',jdk,'bin/java.exe'),['-jar',path.join(tools,'sdk/build-tools/35.0.0/lib/apksigner.jar'),'verify','--verbose',staged]);
if(!signature.includes('Verified using v2 scheme (APK Signature Scheme v2): true')) throw Error('Release APK signature check failed.');
const badging = run(path.join(tools,'sdk/build-tools/35.0.0/aapt.exe'),['dump','badging',staged]);
const remote = run(adb,['-d','shell','pm','path','local.clearplay.player.android']).split('\n').find(s=>s.startsWith('package:'))?.slice(8);
if(!remote) throw Error('Release package is not installed.');
const temporary = path.join(out,'installed-package.tmp.apk');
let installedHash;
try { run(adb,['-d','pull',remote,temporary]); installedHash=hash(temporary); }
finally { if(fs.existsSync(temporary)) fs.unlinkSync(temporary); }
if(hash(apk)!==installedHash || hash(apk)!==hash(staged)) throw Error('Installed APK does not match the delivered artifact.');
const unitFolder=path.join(tools,'work/clearplay-android/app/build/test-results/testReleaseUnitTest');
const unitReports=fs.readdirSync(unitFolder).filter(f=>/^TEST-.*\.xml$/.test(f)).map(f=>{
  const xml=fs.readFileSync(path.join(unitFolder,f),'utf8');
  fs.copyFileSync(path.join(unitFolder,f),path.join(out,`${version}-${f}`));
  return {tests:Number(xml.match(/tests="(\d+)"/)[1]),failures:Number(xml.match(/failures="(\d+)"/)[1]),errors:Number(xml.match(/errors="(\d+)"/)[1])};
});
const lintFile=path.join(tools,'work/clearplay-android/app/build/reports/lint-results-release.xml');
const lint=fs.readFileSync(lintFile,'utf8');
fs.copyFileSync(lintFile,path.join(out,`${version}-lint-results.xml`));
function playback(file) {
  const content=fs.readFileSync(path.join(out,file),'utf8');
  if(!content.includes('OK (1 test)')) throw Error(`Playback test did not pass: ${file}`);
  for(const line of content.split('\n')) {
    const marker='CLEARPLAY_REPORTED=', at=line.indexOf(marker);
    if(at>=0) { const event=JSON.parse(line.slice(at+marker.length)); if(event.phase==='reported-playback') return event.result; }
  }
  throw Error(`Playback result missing: ${file}`);
}
const report={apk,bytes:fs.statSync(apk).size,sha256:hash(apk),installedSha256:installedHash,
  packageLine:badging.split('\n').find(s=>s.startsWith('package:')),minSdk:'26',targetSdk:'35',signatureVerified:true,
  unitTests:{passed:unitReports.reduce((n,r)=>n+r.tests-r.failures-r.errors,0),failed:unitReports.reduce((n,r)=>n+r.failures+r.errors,0)},
  lintErrors:(lint.match(/severity="Error"/g)||[]).length,
  phone:{model:run(adb,['-d','shell','getprop','ro.product.model']),api:Number(run(adb,['-d','shell','getprop','ro.build.version.sdk'])),releaseInstalled:true,
    baidu720:playback('reported-baidu-play-720P-sample-0-strict.txt'),
    quarkOriginal:[0,1].map(i=>playback(`reported-quark-play-原画-sample-${i}-strict.txt`))},
  realCloudAccountValidated:{quark:true,baidu:true},limitations:['Baidu original acceleration did not pass stability checks; no Baidu acceleration route enabled.','Quark validation covers two files on this account/network, not every file or account.','No real 1440P cloud file was tested.']};
if(report.unitTests.failed || report.lintErrors) throw Error('Required checks failed.');
fs.writeFileSync(path.join(out,'package-report.json'),JSON.stringify(report,null,2)+'\n');
fs.writeFileSync(path.join(out,`package-report-${version}.json`),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
