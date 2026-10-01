import {test,expect} from '@playwright/test';
import {mkdir,readFile} from 'node:fs/promises';
test('account settings register, merge, disconnect and reopen the same account',async({page})=>{
  test.skip(!process.env.NOOK_AUTH_E2E,'Runs against the configured Firebase emulators');
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.getByRole('button',{name:'Quick capture',exact:true}).click();
  await page.getByRole('textbox',{name:'Thought',exact:true}).fill('Account browser proof');
  await page.getByRole('dialog').getByRole('button',{name:'Save',exact:true}).click();
  await page.getByRole('button',{name:'Settings',exact:true}).click();
  await expect(page.getByRole('button',{name:'Continue with Google'})).toBeVisible();
  await mkdir('artifacts/visual',{recursive:true});
  await page.locator('.account-controls').screenshot({path:'artifacts/visual/web-account-controls.png'});
  await page.getByRole('button',{name:'Use email',exact:true}).click();
  const email=`browser-${Date.now()}@example.com`;
  await page.getByRole('textbox',{name:'Email',exact:true}).fill(email);
  await page.getByLabel('Password',{exact:true}).fill('test-only-password');
  await page.getByRole('checkbox',{name:'Create a new account'}).check();
  await page.getByRole('button',{name:'Create account',exact:true}).click();
  await expect(page.getByText('Sync enabled · saved locally',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await expect(page.getByRole('button',{name:/Account browser proof/})).toBeVisible();
  await page.getByRole('button',{name:'Settings',exact:true}).click();
  await expect(page.locator('.account-controls')).toContainText(email);
  await expect(page.locator('.account-controls')).toContainText('0 changes waiting to sync');
  await page.getByRole('button',{name:'Disconnect web',exact:true}).click();
  await expect(page.getByText('Local-only · saved locally',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await expect(page.getByRole('button',{name:/Account browser proof/})).toHaveCount(0);
  await page.getByRole('button',{name:'Settings',exact:true}).click();
  await page.getByRole('button',{name:'Use email',exact:true}).click();
  await page.getByRole('textbox',{name:'Email',exact:true}).fill(email);
  await page.getByLabel('Password',{exact:true}).fill('test-only-password');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.getByText('Sync enabled · saved locally',{exact:true})).toBeVisible();
  await page.reload();await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await expect(page.getByRole('button',{name:/Account browser proof/})).toBeVisible();
});
test('cloud originals arrive in a separate browser and remain available offline',async({page,browser})=>{
  test.skip(!process.env.NOOK_AUTH_E2E,'Runs against the configured Firebase emulators');
  const email=`original-${Date.now()}@example.com`,bytes=Buffer.from([0,255,1,128,3]);
  let entered!:()=>void,release!:()=>void,held=false;
  const uploadEntered=new Promise<void>(resolve=>{entered=resolve;}),uploadGate=new Promise<void>(resolve=>{release=resolve;});
  await page.route('http://127.0.0.1:9199/v0/b/**',async route=>{
    if(route.request().method()==='POST' && !held){held=true;entered();await uploadGate;}
    await route.continue();
  });
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.getByRole('button',{name:'Settings',exact:true}).click();
  await page.getByRole('button',{name:'Use email',exact:true}).click();
  await page.getByRole('textbox',{name:'Email',exact:true}).fill(email);await page.getByLabel('Password',{exact:true}).fill('test-only-password');
  await page.getByRole('checkbox',{name:'Create a new account'}).check();await page.getByRole('button',{name:'Create account',exact:true}).click();
  await expect(page.getByText('Sync enabled · saved locally',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Quick capture',exact:true}).click();await page.getByRole('textbox',{name:'Thought',exact:true}).fill('Cross-browser bytes');
  await page.getByRole('dialog').getByRole('button',{name:'Save',exact:true}).click();await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await page.getByRole('button',{name:'Note',exact:true}).click();await page.getByRole('textbox',{name:'Title',exact:true}).fill('Cloud original proof');await page.getByRole('button',{name:'Save',exact:true}).click();
  await page.getByLabel('Add attachment',{exact:true}).setInputFiles({name:'private.bin',mimeType:'application/octet-stream',buffer:bytes});
  await expect(page.getByRole('button',{name:'private.bin',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Settings',exact:true}).click();await uploadEntered;
  try {
    await expect(page.getByLabel('File sync progress')).toContainText('Uploading private.bin (0%)');
    await expect(page.getByRole('button',{name:'Sync now',exact:true})).toBeDisabled();
    await page.locator('.account-controls').screenshot({path:'artifacts/visual/web-file-sync-active.png'});
  } finally {release();}
  await expect(page.getByRole('button',{name:'Sync now',exact:true})).toBeEnabled();
  await expect(page.getByLabel('File sync progress')).toHaveText('1 of 1 files checked');
  await page.getByRole('button',{name:'Sync now',exact:true}).click();
  await expect(page.getByRole('button',{name:'Sync now',exact:true})).toBeEnabled();await expect(page.getByRole('alert')).toHaveCount(0);
  const other=await browser.newContext({baseURL:'http://127.0.0.1:4173',viewport:{width:1440,height:900}});
  try {
    const second=await other.newPage();await second.goto('/');await second.getByRole('button',{name:'Use Nook without an account'}).click();
    await second.getByRole('button',{name:'Settings',exact:true}).click();await second.getByRole('button',{name:'Use email',exact:true}).click();
    await second.getByRole('textbox',{name:'Email',exact:true}).fill(email);await second.getByLabel('Password',{exact:true}).fill('test-only-password');await second.getByRole('button',{name:'Sign in',exact:true}).click();
    await expect(second.getByText('Sync enabled · saved locally',{exact:true})).toBeVisible();
    await second.getByRole('button',{name:'Settings',exact:true}).click();
    await second.getByRole('button',{name:'Sync now',exact:true}).click();await expect(second.getByRole('button',{name:'Sync now',exact:true})).toBeEnabled();await expect(second.getByRole('alert')).toHaveCount(0);
    await expect(second.getByLabel('File sync progress')).toHaveText('1 of 1 files checked');
    await second.evaluate(async()=>{await navigator.serviceWorker.ready;});await second.reload();await second.waitForFunction(()=>!!navigator.serviceWorker.controller);await other.setOffline(true);
    await second.getByRole('button',{name:'Search',exact:true}).click();await second.getByRole('textbox',{name:'Search your Nook'}).fill('Cloud original proof');await second.getByRole('button',{name:'note Cloud original proof',exact:true}).click();
    const [download]=await Promise.all([second.waitForEvent('download'),second.getByRole('button',{name:'private.bin',exact:true}).click()]);
    expect(await readFile((await download.path())!)).toEqual(bytes);
  }finally {await other.close();}
});
