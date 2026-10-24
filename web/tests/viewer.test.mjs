// Drives web/viewer.html in Chromium against tools/mock_server.py:
// live video, sound, Take photo and Record.
//
//   cd web && npm install && npx playwright install chromium && npm test

import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = fileURLToPath(new URL('../../', import.meta.url));
const port = 8190 + Math.floor(Math.random() * 500);
const base = `http://127.0.0.1:${port}`;
let server;
let browser;

before(async () => {
  server = spawn('python3', ['tools/mock_server.py', 'tools/testdata/frames', '--port', String(port)], {
    cwd: root,
    stdio: 'ignore',
  });
  for (let i = 0; i < 50; i++) {
    try {
      if ((await fetch(base + '/status')).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  // Lets the viewer start sound without a click, as OBS browser sources do.
  browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
});

after(async () => {
  await browser?.close();
  server?.kill();
});

async function openViewer(query = '') {
  const page = await browser.newPage({ acceptDownloads: true });
  await page.goto(base + '/' + query);
  await page.locator('#state', { hasText: 'live' }).waitFor();
  await page.waitForFunction(() => document.getElementById('video').naturalWidth > 0);
  return page;
}

test('shows the live stream and status', async () => {
  const page = await openViewer();
  assert.equal(await page.locator('#res').textContent(), '252×448');
  assert.equal(await page.locator('#placeholder').isHidden(), true);
  await page.close();
});

test('Take photo shows the new photo', async () => {
  const page = await openViewer();
  await page.click('#capture');
  await page.locator('#photoToast').waitFor({ state: 'visible', timeout: 5000 });
  const ok = await page.waitForFunction(() => {
    const img = document.getElementById('photoThumb');
    return img.complete && img.naturalWidth > 0;
  });
  assert.ok(ok);
  await page.close();
});

test('Record downloads a video with sound', async () => {
  const page = await openViewer('?audio');
  await page.locator('#sound', { hasText: 'Sound on' }).waitFor();
  // Give the audio stream a moment to start before recording.
  await page.waitForTimeout(1000);
  await page.click('#record');
  await page.locator('#recPill').waitFor({ state: 'visible' });
  await page.waitForTimeout(3000);
  // The controls hide after 3 s without mouse movement; wake them as a user would.
  await page.mouse.move(20, 20);
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('#record')]);
  const name = download.suggestedFilename();
  assert.match(name, /^glasscast-.*\.(mp4|webm)$/);
  const bytes = await readFile(await download.path());
  assert.ok(bytes.length > 20_000, `recording is only ${bytes.length} bytes`);
  const isWebm = bytes.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]));
  const isMp4 = bytes.subarray(4, 8).toString() === 'ftyp';
  assert.ok(isWebm || isMp4, 'not a WebM or MP4 file');
  // Both containers name their audio codec in the header.
  const head = bytes.subarray(0, 64 * 1024).toString('latin1');
  assert.ok(/A_OPUS|mp4a|Opus/.test(head), 'recording has no audio track');
  await page.close();
});
