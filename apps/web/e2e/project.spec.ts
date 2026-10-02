import {expect,test} from '@playwright/test';
import {strToU8,zipSync} from 'fflate';
import type {Entity} from '../../../packages/schemas/src';

test('desktop Project source workspace and local task controls persist offline',async({page,context})=>{
  const time=new Date('2026-09-30T07:41:00').getTime();
  const base={accountId:'local:fixture',clientId:'fixture',schemaVersion:1,createdAt:time,updatedAt:time,deleted:false,archived:false} as const;
  const records:Entity[]=[
    {...base,id:'area',kind:'area',data:{title:'University',responsibility:'Study',standards:''}},
    {...base,id:'project',kind:'project',data:{title:'Classroom Management System',outcome:'Working classroom system with IoT display ready for final demonstration.',progress:64,targetDate:'2026-12-18',areaId:'area',nextActionId:'task-2'}},
    ...['Define requirements','Choose display','Build ESP32 prototype','Connect backend','Test classroom workflow'].map((title,index):Entity<'task'>=>({...base,id:`task-${index}`,kind:'task',createdAt:time+index,updatedAt:time+index,data:{title,completed:index<2,projectId:'project',...(index===2?{doDate:'2026-10-01'}:{})}})),
    ...['ESP32 display options','Power planning','Queue UX notes'].map((title,index):Entity<'note'>=>({...base,id:`note-${index}`,kind:'note',createdAt:time+index,updatedAt:time+index,data:{title,body:'Reference',attachmentIds:[],projectId:'project'}})),
  ];
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.evaluate(async()=>{await navigator.serviceWorker.ready;});await page.reload();await page.waitForFunction(()=>!!navigator.serviceWorker.controller);await context.setOffline(true);
  await page.getByRole('button',{name:'Settings',exact:true}).click();
  await page.getByLabel('Nook backup file',{exact:true}).setInputFiles({name:'project.zip',mimeType:'application/zip',buffer:Buffer.from(zipSync({'nook.json':strToU8(JSON.stringify({format:'nook',version:1,exportedAt:time,records}))}))});
  await expect(page.getByRole('status')).toContainText('Backup restored');
  async function openProject(){await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('button',{name:/PROJECT Classroom Management System/}).click();}
  await openProject();await page.getByRole('button',{name:'Dismiss message'}).click();
  await expect(page.locator('.page-header p')).toHaveText('University · target Dec 18 · 64% complete');
  await expect(page.locator('.project-details details')).not.toHaveAttribute('open');
  const expected=[['.project-outcome',296,138,760,132],['.project-next',1080,138,300,132],['.project-tasks',296,298,620,510],['.project-related',940,298,440,246],['.project-activity',940,568,440,240]] as const;
  for(const [selector,x,y,width,height] of expected){const box=(await page.locator(selector).boundingBox())!;expect(box.x).toBeCloseTo(x,0);expect(box.y).toBeCloseTo(y,0);expect(box.width).toBeCloseTo(width,0);expect(box.height).toBeCloseTo(height,0);}
  const assets=await page.locator('.sidebar img,.capture-trigger img').evaluateAll(images=>images.map(image=>{const item=image as HTMLImageElement;const box=item.getBoundingClientRect();return {src:item.getAttribute('src'),loaded:item.complete&&item.naturalWidth>0,width:box.width,height:box.height};}));
  expect(assets).toHaveLength(9);for(const asset of assets){expect(asset.loaded).toBe(true);if(asset.src==='/branding/nook-mascot-icon.png')expect(asset.width).toBe(28);else expect(asset.src).toMatch(/^\/figma\/3-322-/);expect(asset.width).toBe(asset.height);expect([18,24,28,32]).toContain(asset.width);}
  await page.screenshot({path:'artifacts/visual/web-project-populated.png',fullPage:true});
  for(const width of [1024,390,320]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);await page.screenshot({path:`artifacts/visual/web-project-${width}.png`,fullPage:true});}
  await page.setViewportSize({width:1440,height:900});
  await page.getByRole('checkbox',{name:'Complete Build ESP32 prototype',exact:true}).click();await expect(page.getByRole('checkbox',{name:'Complete Build ESP32 prototype',exact:true})).toBeChecked();
  await expect(page.getByRole('button',{name:'Start',exact:true})).toHaveCount(0);
  await page.reload();await openProject();await expect(page.getByRole('checkbox',{name:'Complete Build ESP32 prototype',exact:true})).toBeChecked();
  await page.getByText('Project details',{exact:true}).click();await page.getByRole('textbox',{name:'Outcome',exact:true}).fill('Preserved draft before related navigation');
  await page.getByText('Project details',{exact:true}).click();await page.getByRole('button',{name:'ESP32 display options',exact:true}).click();
  await expect(page.getByRole('textbox',{name:'Title',exact:true})).toHaveValue('ESP32 display options');
  await openProject();await page.getByText('Project details',{exact:true}).click();await expect(page.getByRole('textbox',{name:'Outcome',exact:true})).toHaveValue('Preserved draft before related navigation');
  await page.getByRole('button',{name:'Archive',exact:true}).last().click();await expect(page.getByRole('button',{name:'Restore',exact:true})).toBeVisible();await page.getByRole('button',{name:'Restore',exact:true}).click();
  await page.getByRole('button',{name:'+ Add note',exact:true}).click();await expect(page.getByRole('textbox',{name:'Note body',exact:true})).toBeVisible();
  await page.getByText('Organize note',{exact:true}).click();await expect(page.getByRole('combobox',{name:'Project',exact:true})).toHaveValue('project');
});

