import {expect,test} from '@playwright/test';

async function start(page: import('@playwright/test').Page, context: import('@playwright/test').BrowserContext) {
  await page.goto('/');
  await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.evaluate(async()=>{await navigator.serviceWorker.ready;});
  await page.reload();
  await page.waitForFunction(()=>!!navigator.serviceWorker.controller);
  await context.setOffline(true);
}

async function capture(page: import('@playwright/test').Page, body:string, taskHint=false) {
  await page.getByRole('button',{name:'Quick capture'}).click();
  await page.getByRole('textbox',{name:'Thought',exact:true}).fill(body);
  if(taskHint) await page.getByRole('button',{name:'Task',exact:true}).click();
  await page.getByRole('button',{name:'Save',exact:true}).click();
}

test('split clarifies edited capture, saves and advances, then history and Resource navigation retain both links offline',async({page,context})=>{
  await start(page,context);
  await capture(page,'Read about sleep research');
  await page.waitForTimeout(10);
  await capture(page,'Bedroom lighting: warm lamps, dimmer, ask electrician for a quote.');
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await expect(page.getByRole('textbox',{name:'Your thought',exact:true})).toHaveValue('Bedroom lighting: warm lamps, dimmer, ask electrician for a quote.');
  await page.getByRole('button',{name:'Split',exact:true}).click();
  await page.getByRole('textbox',{name:'Task action',exact:true}).fill('Ask electrician for a quote');
  await page.locator('.clarification-card .home-trigger').click();
  const homes=page.getByRole('dialog',{name:'Add to a Project, Area, or Resource'});
  await homes.getByText('Create a Project, Area, or Resource',{exact:true}).click();
  await homes.getByRole('combobox',{name:'Type',exact:true}).selectOption('resource');
  await homes.getByRole('textbox',{name:'Name',exact:true}).fill('Bedroom ideas');
  await homes.getByRole('button',{name:'Create Resource',exact:true}).click();
  await page.getByRole('button',{name:'Tomorrow',exact:true}).click();
  await page.getByText('More options',{exact:true}).click();
  await page.getByRole('textbox',{name:'Note title',exact:true}).fill('Bedroom lighting plan');
  await page.getByRole('textbox',{name:'Note content',exact:true}).fill('Warm lamps, a dimmer, and soft overhead light.');
  await expect(page.getByRole('button',{name:'Save & next',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Save & next',exact:true}).click();
  await expect(page.getByRole('textbox',{name:'Your thought',exact:true})).toHaveValue('Read about sleep research');
  await page.getByRole('button',{name:'Save',exact:true}).click();
  await page.getByRole('button',{name:'History',exact:false}).click();
  await expect(page.getByRole('heading',{name:'Processed thoughts',exact:true})).toBeVisible();
  await page.getByRole('button',{name:/Bedroom lighting:/}).click();
  await expect(page.getByRole('heading',{name:'Original wording',exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:/Ask electrician for a quote/})).toBeVisible();
  await expect(page.getByRole('button',{name:/Bedroom lighting plan/})).toBeVisible();
  await page.getByRole('button',{name:/Bedroom lighting plan/}).click();
  await expect(page.locator('.record-home-control .home-trigger')).toContainText('Bedroom ideas');
  await page.getByRole('button',{name:'Resources',exact:true}).click();
  await page.getByRole('button',{name:/RESOURCE Bedroom ideas/}).click();
  await expect(page.getByRole('button',{name:/Ask electrician for a quote/})).toBeVisible();
  await page.reload();
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await page.getByRole('button',{name:/^History/}).click();
  await page.getByRole('button',{name:/Bedroom lighting:/}).click();
  await expect(page.getByRole('heading',{name:'Original wording',exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:/Bedroom lighting plan/})).toBeVisible();
});

test('custom Schedule uses the in-app calendar, cancel keeps the date, and More offers archive, restore, and confirmed delete',async({page,context})=>{
  await start(page,context);
  await capture(page,'Send the workshop outline',true);
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await expect(page.getByRole('button',{name:'Choose date',exact:true})).toBeVisible();
  const chosen=await page.evaluate(()=>{const now=new Date();return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-15`;});
  const spokenDate=await page.evaluate(value=>new Date(`${value}T12:00:00`).toLocaleDateString(undefined,{weekday:'long',year:'numeric',month:'long',day:'numeric'}),chosen);
  await page.getByRole('button',{name:'Choose date',exact:true}).click();
  let dateDialog=page.getByRole('dialog',{name:'Schedule',exact:true});
  await dateDialog.getByRole('button',{name:spokenDate,exact:true}).click();
  await dateDialog.getByRole('button',{name:'Set date',exact:true}).click();
  const schedule=page.locator('.schedule-section').first().locator('.calendar-button');
  await expect(schedule).toContainText('15');
  await schedule.click();
  dateDialog=page.getByRole('dialog',{name:'Schedule',exact:true});
  const otherDate=await page.evaluate(value=>{const day=new Date(`${value}T12:00:00`);day.setDate(day.getDate()+1);return new Date(day.getFullYear(),day.getMonth(),day.getDate()).toLocaleDateString(undefined,{weekday:'long',year:'numeric',month:'long',day:'numeric'});},chosen);
  await dateDialog.getByRole('button',{name:otherDate,exact:true}).click();
  await page.keyboard.press('Escape');
  await expect(schedule).toContainText('15');
  await expect(page.getByRole('heading',{name:'Deadline',exact:true})).toHaveCount(0);
  await page.getByText('More options',{exact:true}).click();
  await expect(page.getByRole('heading',{name:'Deadline',exact:true})).toBeVisible();
  await page.getByRole('button',{name:/^Archive thought/}).click();
  await page.getByRole('button',{name:'Archive',exact:true}).click();
  await page.getByRole('button',{name:/CAPTURE Send the workshop outline/}).click();
  await page.getByRole('button',{name:'Restore to Inbox',exact:true}).click();
  await page.getByRole('button',{name:'Inbox',exact:true}).click();
  await page.getByText('More options',{exact:true}).click();
  await page.getByRole('button',{name:/^Delete thought/}).click();
  const confirm=page.getByRole('dialog',{name:'Delete this thought?',exact:true});
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button',{name:'Delete thought',exact:true}).click();
  await expect(page.locator('.capture-list .capture-item')).toHaveCount(0);
});
