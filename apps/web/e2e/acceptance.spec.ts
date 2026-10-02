import {expect,test,type Page} from '@playwright/test';
import {strToU8,zipSync} from 'fflate';
import type {Entity} from '../../../packages/schemas/src';

async function readableVisibleText(page:Page) {
  const failures=await page.evaluate(()=>{
    function rgb(value:string){return value.match(/[\d.]+/g)?.map(Number)??[];}
    function luminance(color:number[]){const channels=color.slice(0,3).map(v=>{const s=v/255;return s<=.04045?s/12.92:((s+.055)/1.055)**2.4;});return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;}
    const failures:string[]=[];
    for(const element of Array.from(document.querySelectorAll<HTMLElement>('button,p,small,label,a,h1,h2,h3,summary,.pill'))) {
      const ownText=Array.from(element.childNodes).filter(node=>node.nodeType===Node.TEXT_NODE).map(node=>node.textContent).join('').trim();
      const box=element.getBoundingClientRect();const style=getComputedStyle(element);
      if(!ownText||box.width<2||box.height<2||style.visibility==='hidden'||style.display==='none'||element.closest('[hidden],.sr-only')||element.matches(':disabled')||ownText==='+'||ownText==='×')continue;
      let ancestor:HTMLElement|null=element;let background:number[]=[];
      while(ancestor){background=rgb(getComputedStyle(ancestor).backgroundColor);if(background.length>=3&&(background.length<4||background[3]===1))break;ancestor=ancestor.parentElement;}
      if(!ancestor)continue;
      const color=rgb(style.color);const light=luminance(color);const dark=luminance(background);
      const ratio=(Math.max(light,dark)+.05)/(Math.min(light,dark)+.05);
      const font=parseFloat(style.fontSize);const threshold=font>=24||font>=18.666&&Number(style.fontWeight)>=700?3:4.5;
      if(ratio+.001<threshold)failures.push(`${element.tagName} ${ownText.slice(0,60)}: ${style.color} / ${getComputedStyle(ancestor).backgroundColor} = ${ratio.toFixed(3)} < ${threshold}`);
    }
    return failures;
  });
  expect(failures).toEqual([]);
}

test('desktop Today and Inbox source hierarchy, visible contrast and local actions work offline',async({page,context})=>{
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.evaluate(async()=>{await navigator.serviceWorker.ready;});await page.reload();await page.waitForFunction(()=>!!navigator.serviceWorker.controller);
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href','/favicon-48.png');
  const webManifest=await page.evaluate(async()=>fetch('/manifest.webmanifest').then(response=>response.json()));
  expect(webManifest.icons).toEqual([
    {src:'/icons/nook-pwa-192.png',sizes:'192x192',type:'image/png',purpose:'any maskable'},
    {src:'/icons/nook-pwa-512.png',sizes:'512x512',type:'image/png',purpose:'any maskable'},
  ]);
  await context.setOffline(true);
  const today=await page.evaluate(()=>{const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;});
  const time=Date.now();const base={accountId:'local:fixture',clientId:'fixture',schemaVersion:1,createdAt:time,updatedAt:time,deleted:false,archived:false} as const;
  const records:Entity[]=[
    {...base,id:'university',kind:'area',data:{title:'University',responsibility:'Study',standards:''}},
    {...base,id:'classroom',kind:'project',data:{title:'Classroom Management System',outcome:'A working classroom display',progress:64,areaId:'university',nextActionId:'activity'}},
    {...base,id:'capstone',kind:'project',data:{title:'Capstone',outcome:'Display prototype',progress:20,nextActionId:'module'}},
    {...base,id:'embedded',kind:'project',data:{title:'Embedded Systems',outcome:'Prepare for the quiz',progress:30}},
    {...base,id:'activity',kind:'task',data:{title:'Finish Activity 3',completed:false,doDate:today,deadline:'2030-10-02',projectId:'classroom'}},
    {...base,id:'module',kind:'task',data:{title:'Capstone display module',completed:false,projectId:'capstone',deadline:'2030-10-04'}},
    {...base,id:'quiz',kind:'task',data:{title:'Embedded Systems quiz',completed:false,deadline:'2030-10-07'}},
    ...['Research ESP32 deep sleep','Send assessment follow-up','Whiteboard after capstone','Offline-first sync article','Voice capture later','Activity 3 research','Queue UX notes'].map((body,index):Entity<'capture'>=>({...base,id:`capture-${index}`,createdAt:time-index,kind:'capture',data:{body,captureType:([ 'text','task','image','link','text','text','text'] as const)[index],attachmentIds:[]}})),
  ];
  await page.getByRole('button',{name:'Settings',exact:true}).click();await page.getByLabel('Nook backup file',{exact:true}).setInputFiles({name:'acceptance.zip',mimeType:'application/zip',buffer:Buffer.from(zipSync({'nook.json':strToU8(JSON.stringify({format:'nook',version:1,exportedAt:time,records}))}))});
  await expect(page.getByRole('status')).toContainText('Backup restored');await page.getByRole('button',{name:'Dismiss message'}).click();
  await page.getByRole('button',{name:'Today',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Things worth doing.'})).toBeVisible();await expect(page.getByRole('checkbox',{name:'Complete Finish Activity 3'})).toBeVisible();
  await expect(page.locator('.inbox-summary .pill')).toHaveText('7');await expect(page.locator('.upcoming .deadline')).toHaveCount(3);
  await readableVisibleText(page);await expect(page.locator('.page-mascot')).toHaveAttribute('alt',"Nook's dormouse mascot");await page.screenshot({path:'artifacts/branding/mascot/screenshots/web-today-v0.2.3.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});await expect(page.locator('.page-mascot')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({path:'artifacts/branding/mascot/screenshots/web-today-mobile-v0.2.3.png',fullPage:true});
  await page.setViewportSize({width:1440,height:900});
  const todayAssets=await page.locator('.sidebar img,.capture-trigger img').evaluateAll(images=>images.map(image=>{const item=image as HTMLImageElement;const box=item.getBoundingClientRect();return {src:item.getAttribute('src'),loaded:item.complete&&item.naturalWidth>0,width:box.width,height:box.height};}));
  expect(todayAssets).toHaveLength(9);for(const asset of todayAssets){expect(asset.loaded).toBe(true);if(asset.src==='/branding/nook-mascot-icon.png')expect(asset.width).toBe(28);else expect(asset.src).toMatch(/^\/figma\/3-154-/);expect(asset.width).toBe(asset.height);expect([18,24,28,32]).toContain(asset.width);}
  await page.getByRole('button',{name:'Inbox',exact:true}).click();await expect(page.getByRole('heading',{name:'Research ESP32 deep sleep',exact:true})).toBeVisible();
  await expect(page.locator('.capture-item')).toHaveCount(7);await readableVisibleText(page);await page.screenshot({path:'artifacts/branding/mascot/screenshots/web-inbox-v0.2.3.png',fullPage:true});
  const markers=await page.locator('.capture-item img').evaluateAll(images=>images.map(image=>{const item=image as HTMLImageElement;const box=item.getBoundingClientRect();return {src:item.getAttribute('src'),loaded:item.complete&&item.naturalWidth>0,width:box.width,height:box.height};}));
  expect(markers.slice(0,5).map(item=>item.src)).toEqual([5,6,7,8,9].map(index=>`/figma/3-235-imgEllipse${index}.svg`));for(const item of markers){expect(item.loaded).toBe(true);expect(item.width).toBe(34);expect(item.height).toBe(34);}
  await page.getByRole('button',{name:'Today',exact:true}).click();
  await page.getByRole('button',{name:'Open weekly review',exact:true}).click();await readableVisibleText(page);
  await page.keyboard.press('Tab');await page.getByRole('button',{name:'Review',exact:true}).focus();expect(await page.getByRole('button',{name:'Review',exact:true}).evaluate(element=>getComputedStyle(element).outlineStyle)).toBe('solid');await page.keyboard.press('Enter');await expect(page.getByRole('button',{name:'Review',exact:true})).toHaveAttribute('aria-expanded','true');
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await page.getByRole('button',{name:'Edit capture',exact:true}).click();await page.getByRole('textbox',{name:'Edit capture',exact:true}).fill('Keyboard thought proof');await page.keyboard.press('t');await expect(page.getByRole('textbox',{name:'Edit capture',exact:true})).toHaveValue('Keyboard thought prooft');await page.getByRole('button',{name:'Save edit',exact:true}).click();
  await page.getByRole('region',{name:'Clarify capture',exact:true}).focus();await page.keyboard.press('t');await expect(page.getByLabel('Title',{exact:true})).toHaveValue('Keyboard thought prooft');
});
