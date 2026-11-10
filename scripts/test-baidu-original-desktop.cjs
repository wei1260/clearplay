// Actual packaged-player check. Own account only; restores playback history afterwards.
const { _electron: electron } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
if (!process.argv.includes('--account-check')) throw new Error('Explicit account check required');
const libraryFile=path.join(process.env.APPDATA,'澄映播放器','library.json');
const saved=fs.readFileSync(libraryFile);
const files=JSON.parse(saved).history.filter(x=>x.provider==='baidu'&&x.size>1024*1024);
const results=[];
let desktop;
async function main() {
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
  desktop=await electron.launch({executablePath:path.resolve(__dirname,'../release/win-unpacked/澄映播放器.exe'),env,timeout:45000});
  const page=await desktop.firstWindow();
  await page.waitForFunction(()=>!!window.clearplay&&typeof playFile==='function');
  await desktop.evaluate(({app})=>{
    const {BaiduWebDrive}=process.mainModule.require(app.getAppPath()+'/src/providers.cjs');
    const original=BaiduWebDrive.prototype.sources;
    BaiduWebDrive.prototype.sources=async function(...args) {const result=await original.apply(this,args);global.__originalUA=result.sources.find(x=>x.label==='原画')?.headers?.['User-Agent'];return result;};
  });
  const snapshot=()=>page.locator('video').evaluate(v=>({time:v.currentTime,ready:v.readyState,paused:v.paused,seeking:v.seeking,width:v.videoWidth,height:v.videoHeight,frames:v.webkitDecodedFrameCount,audio:v.webkitAudioDecodedByteCount,error:v.error?.code||0,duration:v.duration}));
  for (const sample of [0,1]) {
    const started=Date.now();
    try {
      await page.evaluate(async file=>{await playFile(file);const i=state.sources.findIndex(x=>x.label==='原画');if(i<0)throw new Error('No original');document.querySelector('video').muted=true;document.querySelector('#speed').value='2';loadSource(i,0,true);},files[sample]);
      assert.equal(await desktop.evaluate(()=>global.__originalUA),'netdisk');
      console.log(JSON.stringify({phase:'prepared',sample,elapsedMs:Date.now()-started}));
      await page.waitForFunction(()=>{const v=document.querySelector('video');return v.videoWidth>0&&v.webkitDecodedFrameCount>0&&v.webkitAudioDecodedByteCount>0&&v.currentTime>.2&&!v.paused;},{},{timeout:30000});
      const first=await snapshot(),firstFrameMs=Date.now()-started;
      const measured=Date.now();let previous=Date.now(),bufferingMs=0;
      while(Date.now()-measured<30000) {
        await page.waitForTimeout(200);const now=Date.now(),s=await snapshot();
        assert.equal(s.error,0);if(s.ready<3||s.paused)bufferingMs+=now-previous;previous=now;
      }
      const end=await snapshot(),elapsedMs=Date.now()-measured,advancedMs=Math.round((end.time-first.time)*1000);
      assert.ok(advancedMs>=elapsedMs*2*.95,'Insufficient 2x progress');assert.ok(bufferingMs<1000,'Unexpected buffering');
      const target=Math.min(120,end.duration/2),seekStarted=Date.now();
      await page.locator('video').evaluate((v,target)=>{v.currentTime=target;},target);
      await page.waitForFunction(target=>{const v=document.querySelector('video');return !v.seeking&&v.currentTime>target+.2;},target,{timeout:30000});
      const row={sample,passed:true,firstFrameMs,width:first.width,height:first.height,rate:2,elapsedMs,advancedMs,bufferingMs,audioDecoded:end.audio-first.audio,videoDecoded:end.frames-first.frames,seekMs:Date.now()-seekStarted};
      results.push(row);console.log(JSON.stringify(row));
    } catch(error) {const row={sample,passed:false,errorType:error.name,state:await snapshot()};results.push(row);console.log(JSON.stringify(row));}
    await page.locator('video').evaluate(v=>v.pause());
  }
}
main().catch(error=>console.log(JSON.stringify({phase:'error',errorType:error.name,reason:String(error.message).replace(/https?:\/\/[^\s]+/g,'[URL]').split('\n').slice(0,4).join('\n')}))).finally(async()=>{
  if(desktop) {await desktop.close();fs.writeFileSync(libraryFile,saved);}
  const passed=results.length===2&&results.every(x=>x.passed);
  const file=path.resolve(__dirname,'../test-results/baidu-original-0.1.4-playback.json');
  fs.writeFileSync(file,JSON.stringify({timestamp:new Date().toISOString(),packaged:true,passed,results},null,2));
  console.log(`Report: ${file}`);process.exitCode=passed?0:1;
});
