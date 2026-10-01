import {chromium} from 'playwright';
import {readFile, mkdir} from 'node:fs/promises';

// Render the original Figma brand asset; do not replace its geometry or paths.
// Run from the repository root when that source asset changes.
const source = await readFile('apps/android/app/src/main/assets/figma/2-121-imgEllipse.svg');
const browser = await chromium.launch();
try {
  const page = await browser.newPage({viewport:{width:512,height:512},deviceScaleFactor:1});
  await page.setContent(`<html><body style="margin:0;background:transparent"><img src="data:image/svg+xml;base64,${source.toString('base64')}" style="display:block;transform:scale(${512/24});transform-origin:top left"></body></html>`);
  await page.locator('img').evaluate(image=>image.decode());
  await mkdir('apps/android/app/src/main/res/drawable-nodpi',{recursive:true});
  await page.screenshot({path:'apps/android/app/src/main/res/drawable-nodpi/nook_brand.png',omitBackground:true});
} finally { await browser.close(); }
