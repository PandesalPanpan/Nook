import {expect,test} from '@playwright/test';
import type {Entity} from '../../../packages/schemas/src';
test('PARA context and related work persist offline on projects, tasks and notes',async({page,context})=>{
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.evaluate(async()=>{await navigator.serviceWorker.ready;});await page.reload();await page.waitForFunction(()=>!!navigator.serviceWorker.controller);await context.setOffline(true);
  await page.getByRole('button',{name:'Resources',exact:true}).click();await page.getByRole('textbox',{name:'New resource title'}).fill('Seed reference');await page.getByRole('button',{name:'+ Add resource'}).click();
  await page.getByRole('button',{name:'Areas',exact:true}).click();await page.getByRole('textbox',{name:'New area title'}).fill('Health');await page.getByRole('button',{name:'+ Add area'}).click();
  await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('textbox',{name:'New project title'}).fill('Garden');await page.getByRole('button',{name:'+ Add project'}).click();
  await page.getByRole('combobox',{name:'Area',exact:true}).selectOption({label:'Health'});
  await page.getByRole('textbox',{name:'Task title'}).fill('Plant seeds');await page.getByRole('button',{name:'+ Add',exact:true}).click();
  await page.getByRole('combobox',{name:'Next action',exact:true}).selectOption({label:'Plant seeds'});await page.getByRole('button',{name:'Save',exact:true}).click();
  await page.getByRole('textbox',{name:'Outcome',exact:true}).fill('Saved before opening task');
  await page.getByRole('button',{name:/^Plant seeds/}).click();await expect(page.getByRole('textbox',{name:'Title',exact:true})).toHaveValue('Plant seeds');
  await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('button',{name:/PROJECT Garden/}).click();await page.getByText('Project details',{exact:true}).click();await expect(page.getByRole('textbox',{name:'Outcome',exact:true})).toHaveValue('Saved before opening task');
  await page.getByRole('button',{name:'Resources',exact:true}).click();await page.getByRole('button',{name:/RESOURCE Seed reference/}).click();await expect(page.getByRole('textbox',{name:'Description',exact:true})).toBeVisible();await page.getByRole('combobox',{name:'Project',exact:true}).selectOption({label:'Garden'});await page.getByRole('button',{name:'Dismiss message'}).click();await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.getByRole('status')).toContainText('Saved locally');
  await page.getByRole('button',{name:'Areas',exact:true}).click();await page.getByRole('button',{name:/AREA Health/}).click();
  await expect(page.locator('.record-side').getByRole('button',{name:'Garden',exact:true})).toBeVisible();
  await expect(page.locator('.record-side').getByRole('button',{name:'Seed reference',exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:/^Plant seeds/})).toBeVisible();
  await page.getByRole('textbox',{name:'Standards',exact:true}).fill('Preserved before note creation');
  await page.getByRole('button',{name:'+ Add note'}).click();await expect(page.getByRole('textbox',{name:'Note body'})).toBeVisible();await page.getByRole('textbox',{name:'Title',exact:true}).fill('Planting reference');await page.getByRole('textbox',{name:'Note body'}).fill('Offline context proof');await expect(page.getByRole('textbox',{name:'Title',exact:true})).toHaveValue('Planting reference');
  await page.getByText('Organize note',{exact:true}).click();
  await page.getByRole('combobox',{name:'Project',exact:true}).selectOption({label:'Garden'});await page.getByRole('combobox',{name:'Resource',exact:true}).selectOption({label:'Seed reference'});await page.getByRole('button',{name:'Dismiss message'}).click();await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.getByRole('status')).toContainText('Saved locally');
  const notes=await page.evaluate(async()=>{const db=await new Promise<IDBDatabase>((resolve,reject)=>{const request=indexedDB.open('nook');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});try{return await new Promise<unknown[]>((resolve,reject)=>{const request=db.transaction('entities').objectStore('entities').getAll();request.onsuccess=()=>resolve(request.result.filter((record:{kind:string})=>record.kind==='note'||record.kind==='area'));request.onerror=()=>reject(request.error);});}finally{db.close();}});
  expect(notes).toEqual(expect.arrayContaining([expect.objectContaining({kind:'note',data:expect.objectContaining({title:'Planting reference',body:'Offline context proof'})}),expect.objectContaining({kind:'area',data:expect.objectContaining({standards:'Preserved before note creation'})})]));
  await page.reload();await page.getByRole('button',{name:'Search',exact:true}).click();await page.getByRole('textbox',{name:'Search your Nook'}).fill('Planting reference');await page.getByRole('button',{name:/note Planting reference/}).click();
  await page.getByText('Organize note',{exact:true}).click();
  await expect(page.getByRole('combobox',{name:'Area',exact:true}).locator('option:checked')).toHaveText('Health');
  await expect(page.getByRole('combobox',{name:'Project',exact:true}).locator('option:checked')).toHaveText('Garden');
  await expect(page.getByRole('combobox',{name:'Resource',exact:true}).locator('option:checked')).toHaveText('Seed reference');
});


test('legacy parent-only subtasks can be chosen as Project next actions offline and reopened', async({page,context})=>{
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.evaluate(async()=>{await navigator.serviceWorker.ready;});await page.reload();
  await page.waitForFunction(()=>!!navigator.serviceWorker.controller);await context.setOffline(true);
  await page.getByRole('button',{name:'Projects',exact:true}).click();
  await page.getByRole('textbox',{name:'New project title'}).fill('Inherited garden');
  await page.getByRole('button',{name:'+ Add project'}).click();
  await page.getByRole('textbox',{name:'Task title'}).fill('Parent step');
  await page.getByRole('button',{name:'+ Add',exact:true}).click();
  await expect(page.getByRole('button',{name:/^Parent step/})).toBeVisible();
  // Seed a preexisting parent-only record shape; current UI creation also stores direct context.
  const childId=await page.evaluate(async()=>{
    const db=await new Promise<IDBDatabase>((resolve,reject)=>{const request=indexedDB.open('nook');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
    try {
      return await new Promise<string>((resolve,reject)=>{
        const transaction=db.transaction('entities','readwrite');const store=transaction.objectStore('entities');
        const request=store.getAll();let id='';
        request.onsuccess=()=>{
          const parent=request.result.find((r:Entity)=>r.kind==='task'&&r.data.title==='Parent step') as Entity<'task'>;
          if(!parent){transaction.abort();return;}
          id=crypto.randomUUID();
          store.put({...parent,id,data:{title:'Inherited next step',completed:false,parentTaskId:parent.id}});
          store.put({...parent,id:crypto.randomUUID(),data:{title:'Explicit other Project',completed:false,parentTaskId:parent.id,projectId:'missing-project'}});
          store.put({...parent,id:crypto.randomUUID(),data:{title:'Completed child',completed:true,parentTaskId:parent.id}});
          store.put({...parent,id:'cycle-child',data:{title:'Cycle child',completed:false,parentTaskId:'cycle-child'}});
        };
        transaction.oncomplete=()=>resolve(id);transaction.onerror=()=>reject(transaction.error);transaction.onabort=()=>reject(transaction.error??new Error('Fixture unavailable'));
      });
    } finally {db.close();}
  });
  await page.reload();await page.getByRole('button',{name:'Projects',exact:true}).click();
  await page.getByRole('button',{name:/PROJECT Inherited garden/}).click();
  await expect(page.getByRole('button',{name:/^Inherited next step/})).toBeVisible();
  const picker=page.getByRole('combobox',{name:'Next action',exact:true});
  await expect(picker.getByRole('option',{name:'Inherited next step',exact:true})).toHaveCount(1);
  for(const name of ['Explicit other Project','Completed child','Cycle child']) await expect(picker.getByRole('option',{name,exact:true})).toHaveCount(0);
  await picker.selectOption(childId);await page.getByRole('button',{name:'Save',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('Saved locally');
  await page.reload();await page.getByRole('button',{name:'Projects',exact:true}).click();
  await page.getByRole('button',{name:/PROJECT Inherited garden/}).click();
  await expect(page.getByRole('combobox',{name:'Next action',exact:true})).toHaveValue(childId);
  await page.getByRole('button',{name:'Today',exact:true}).click();
  await page.getByRole('button',{name:/^Inherited next step/}).click();
  await expect(page.getByRole('textbox',{name:'Title',exact:true})).toHaveValue('Inherited next step');
  await expect(page.getByRole('combobox',{name:'Project',exact:true})).toHaveValue('');
  await page.getByRole('checkbox',{name:'Completed',exact:true}).check();
  await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.getByRole('status')).toContainText('Saved locally');
  await page.reload();await page.getByRole('button',{name:'Today',exact:true}).click();
  await expect(page.getByRole('button',{name:/^Inherited next step/})).toHaveCount(0);
});
