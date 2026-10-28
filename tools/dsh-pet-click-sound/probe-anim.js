/**
 * 用 Electron 无头渲染一段 webm，输出时长/尺寸，并在几个时间点截帧。
 * 用法：electron.exe probe-anim.js <video 绝对路径> <输出目录>
 */
const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');

const videoPath = process.argv[2];
const outDir = process.argv[3];

app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  if (!videoPath || !outDir) throw new Error('缺少参数：<video> <outDir>');
  fs.mkdirSync(outDir, { recursive: true });

  const win = new BrowserWindow({
    width: 660,
    height: 380,
    show: false,
    webPreferences: { offscreen: true, backgroundThrottling: false },
  });

  const fileUrl = 'file:///' + videoPath.replace(/\\/g, '/').replace(/#/g, '%23').replace(/\?/g, '%3F');
  const html = `<!doctype html><html><body style="margin:0;background:#1b1b1b">
<video id="v" src="${fileUrl}" muted playsinline style="width:640px;height:360px;object-fit:contain"></video>
</body></html>`;
  const htmlPath = path.join(outDir, 'probe.html');
  fs.writeFileSync(htmlPath, html, 'utf8');
  await win.loadFile(htmlPath);

  const info = await win.webContents.executeJavaScript(`new Promise((resolve) => {
    const v = document.getElementById('v');
    const done = () => resolve({ duration: v.duration, w: v.videoWidth, h: v.videoHeight, error: v.error ? v.error.code : null });
    if (v.readyState >= 1) done();
    else {
      v.addEventListener('loadedmetadata', done, { once: true });
      v.addEventListener('error', done, { once: true });
      setTimeout(done, 5000);
    }
  })`);
  console.log('META ' + JSON.stringify(info));
  if (!Number.isFinite(info.duration) || info.duration <= 0) {
    console.log('无法读取时长，退出');
    app.quit();
    return;
  }

  const fractions = [0.05, 0.3, 0.55, 0.8, 0.98];
  for (let i = 0; i < fractions.length; i += 1) {
    const t = Math.min(info.duration - 0.05, Math.max(0, info.duration * fractions[i]));
    await win.webContents.executeJavaScript(`new Promise((resolve) => {
      const v = document.getElementById('v');
      const onSeeked = () => { v.removeEventListener('seeked', onSeeked); resolve(v.currentTime); };
      v.addEventListener('seeked', onSeeked, { once: true });
      v.currentTime = ${t};
    })`);
    await sleep(300);
    const image = await win.webContents.capturePage();
    const file = path.join(outDir, `frame-${i}.png`);
    fs.writeFileSync(file, image.toPNG());
    console.log('FRAME ' + i + ' t=' + t.toFixed(2) + ' -> ' + file);
  }

  app.quit();
}

app.whenReady().then(main).catch((err) => {
  console.error('PROBE FAILED: ' + (err && err.stack ? err.stack : err));
  app.quit();
});
