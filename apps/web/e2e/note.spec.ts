import {expect,test} from '@playwright/test';
import {strToU8,zipSync} from 'fflate';
import {readFile} from 'node:fs/promises';
import type {Entity} from '../../../packages/schemas/src';

test('desktop note source layout, keyboard links and original files persist offline',async({page,context})=>{
  const time=new Date('2026-09-30T07:41:00').getTime();
  const base={accountId:'local:fixture',clientId:'fixture',schemaVersion:1,createdAt:time,updatedAt:time,deleted:false,archived:false} as const;
  const body='Deep sleep dramatically reduces power use when the board can spend most of its time idle.\n\n## Possible uses\n\n- Battery-powered environmental node\n- Periodic sensor sampling\n- Wake by timer or GPIO\n\n[[classroom]]';
  const records:Entity[]=[
    {...base,id:'esp32',kind:'resource',data:{title:'ESP32',description:''}},
    {...base,id:'classroom',kind:'note',data:{title:'Classroom Management System',body:'Connected project notes',attachmentIds:[]}},
    {...base,id:'sleep',kind:'note',data:{title:'ESP32 deep sleep notes',body,resourceId:'esp32',attachmentIds:[]}},
    {...base,id:'activity',kind:'note',data:{title:'Activity 3 research',body:'[[sleep]]',attachmentIds:[]}},
    {...base,id:'capstone',kind:'note',data:{title:'Capstone power planning',body:'[[sleep]]',attachmentIds:[]}},
  ];
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.evaluate(async()=>{await navigator.serviceWorker.ready;});await page.reload();
  await page.waitForFunction(()=>!!navigator.serviceWorker.controller);await context.setOffline(true);
  await page.getByRole('button',{name:'Settings',exact:true}).click();
  const zip=zipSync({'nook.json':strToU8(JSON.stringify({format:'nook',version:1,exportedAt:time,records}))});
  await page.getByLabel('Nook backup file',{exact:true}).setInputFiles({name:'notes.zip',mimeType:'application/zip',buffer:Buffer.from(zip)});
  await expect(page.getByRole('status')).toContainText('Backup restored');
  async function openNote() {
    await page.getByRole('button',{name:'Search',exact:true}).click();await page.getByRole('textbox',{name:'Search your Nook'}).fill('ESP32 deep sleep');
    await page.getByRole('button',{name:'note ESP32 deep sleep notes',exact:true}).click();
  }
  await openNote();await page.getByRole('button',{name:'Dismiss message'}).click();
  await expect(page.locator('.page-header p')).toHaveText('Resource · ESP32');
  await expect(page.locator('.note-backlinks .linked-record')).toHaveCount(2);
  await expect(page.getByRole('textbox',{name:'Note body',exact:true})).toHaveCount(0);
  await expect(page.locator('.note-details details')).not.toHaveAttribute('open');
  await expect(page.locator('.sidebar .brand-mascot')).toBeVisible();
  const assets=await page.locator('.sidebar img,.capture-trigger img').evaluateAll(nodes=>nodes.map(node=>{
    const image=node as HTMLImageElement;const rect=image.getBoundingClientRect();return {source:image.getAttribute('src'),width:rect.width,height:rect.height,loaded:image.complete && image.naturalWidth>0};
  }));
  expect(assets).toEqual([
    {source:'/branding/nook-mascot-icon.png',width:28,height:28,loaded:true},
    ...[1,1,1,1,2,1].map(index=>({source:`/figma/3-402-imgEllipse${index}.svg`,width:18,height:18,loaded:true})),
    {source:'/figma/3-402-imgEllipse3.svg',width:32,height:32,loaded:true},
    {source:'/figma/3-402-imgEllipse4.svg',width:24,height:24,loaded:true},
  ]);
  await page.screenshot({path:'artifacts/visual/web-note-populated.png',fullPage:true});
  const main=await page.locator('.note-record-main').boundingBox(),side=await page.locator('.note-record-side').boundingBox();
  expect(main?.x).toBe(296);expect(main?.width).toBe(760);expect(side?.x).toBe(1080);expect(side?.width).toBe(300);
  for(const width of [1024,390,320]) {
    await page.setViewportSize({width,height:900});
    const overflows=await page.locator('.note-record-grid button,.note-record-grid input,.note-record-grid summary').evaluateAll(nodes=>nodes.filter(node=>{const r=node.getBoundingClientRect();return r.left<0 || r.right>innerWidth+1;}).map(node=>node.textContent));
    expect(overflows).toEqual([]);
    await page.screenshot({path:`artifacts/visual/web-note-${width}.png`,fullPage:true});
  }
  await page.setViewportSize({width:1440,height:900});
  // Related navigation leaves an unchanged note version untouched.
  async function version() {
    return page.evaluate(async()=>{
      const db=await new Promise<IDBDatabase>((resolve,reject)=>{const request=indexedDB.open('nook');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
      try{return await new Promise<number>((resolve,reject)=>{const request=db.transaction('entities').objectStore('entities').getAll();request.onsuccess=()=>resolve(request.result.find((record:{id:string})=>record.id==='sleep').updatedAt);request.onerror=()=>reject(request.error);});}finally{db.close();}
    });
  }
  const updated=await version();
  await page.getByRole('button',{name:'↗ Classroom Management System',exact:true}).click();
  await expect(page.getByRole('textbox',{name:'Title',exact:true})).toHaveValue('Classroom Management System');
  await openNote();expect(await version()).toBe(updated);
  await page.getByRole('button',{name:'Link note',exact:true}).click();await expect(page.getByRole('dialog',{name:'Link note',exact:true})).toBeVisible();
  await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'Link note',exact:true})).toBeFocused();
  await page.getByRole('button',{name:'Edit note',exact:true}).click();
  const input=page.getByRole('textbox',{name:'Note body',exact:true});await input.fill('one two three');
  await input.evaluate((element:HTMLTextAreaElement)=>{element.focus();element.setSelectionRange(4,7);});
  // Keyboard focus changes must not discard the textarea's selected range.
  await page.getByRole('button',{name:'Link note',exact:true}).focus();await page.keyboard.press('Enter');
  await page.getByRole('textbox',{name:'Find a note'}).fill('Classroom');
  await page.getByRole('dialog',{name:'Link note',exact:true}).getByRole('button',{name:'Classroom Management System',exact:true}).click();
  await expect(input).toHaveValue('one [[classroom]] three');await expect(input).toBeFocused();
  const [chooser]=await Promise.all([page.waitForEvent('filechooser'),page.getByRole('button',{name:'Attach',exact:true}).click()]);
  await chooser.setFiles({name:'original.txt',mimeType:'text/plain',buffer:Buffer.from('offline original')});
  await expect(page.getByRole('button',{name:'original.txt',exact:true})).toBeVisible();
  const [download]=await Promise.all([page.waitForEvent('download'),page.getByRole('button',{name:'original.txt',exact:true}).click()]);
  expect((await readFile((await download.path())!)).toString()).toBe('offline original');
  await page.getByText('Organize note',{exact:true}).click();await page.getByRole('combobox',{name:'Resource',exact:true}).selectOption('');
  await page.getByText('Organize note',{exact:true}).click();
  await page.getByRole('button',{name:'Archive',exact:true}).last().click();await expect(page.getByRole('button',{name:'Restore',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Restore',exact:true}).click();await page.reload();await openNote();
  await expect(page.locator('.page-header p')).toHaveText('Saved locally');
  await expect(page.locator('.markdown')).toContainText('one Classroom Management System three');
  await expect(page.getByRole('button',{name:'original.txt',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Activity 3 research Linked note',exact:true}).click();
  await expect(page.getByRole('textbox',{name:'Title',exact:true})).toHaveValue('Activity 3 research');
});
