import {test,expect} from '@playwright/test';
test('AI proposals require explicit selection and confirmation and Off clears the local key',async({page})=>{
  let calls=0;
  await page.route('https://api.openai.com/v1/chat/completions',async route=>{
    calls++;expect(route.request().headers().authorization).toBe('Bearer fake-local-key');
    const input=JSON.parse(route.request().postDataJSON().messages[1].content);
    const result=input.operation==='actions'?{kind:'actions',actions:['Buy seeds','Plant seeds']}:{kind:'summary',summary:'Short note'};
    await route.fulfill({contentType:'application/json',body:JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify(result)}}]})});
  });
  await page.goto('/');await page.getByRole('button',{name:'Use Nook without an account'}).click();
  await page.getByRole('button',{name:'Settings',exact:true}).click();await page.getByLabel('Provider',{exact:true}).selectOption('openai');await page.getByLabel('API key',{exact:true}).fill('fake-local-key');await page.getByRole('button',{name:'Save AI settings',exact:true}).click();
  await expect(page.getByText('AI settings saved on this browser',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('textbox',{name:'New project title'}).fill('AI garden');await page.getByRole('button',{name:'+ Add project'}).click();
  await page.getByRole('button',{name:'Suggest next actions',exact:true}).click();await expect(page.getByLabel('Plant seeds',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'Add selected actions',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'Buy seeds',exact:true})).toHaveCount(0);
  await page.getByLabel('Plant seeds',{exact:true}).check();await page.getByRole('button',{name:'Add selected actions',exact:true}).click();
  await expect(page.getByRole('button',{name:/^Plant seeds/})).toBeVisible();await expect(page.getByRole('button',{name:/^Buy seeds/})).toHaveCount(0);expect(calls).toBe(1);
  await page.reload();await page.getByRole('button',{name:'Search',exact:true}).click();await page.getByRole('textbox',{name:'Search your Nook'}).fill('Plant seeds');await expect(page.getByRole('button',{name:'task Plant seeds',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Settings',exact:true}).click();await page.locator('section[aria-label="AI settings"]').screenshot({path:'artifacts/visual/web-ai-settings-enabled.png'});await page.getByLabel('Provider',{exact:true}).selectOption('off');await page.getByRole('button',{name:'Save AI settings',exact:true}).click();
  expect(await page.evaluate(()=>Object.keys(localStorage).filter(k=>k.startsWith('nook-ai:')))).toEqual([]);expect(calls).toBe(1);
  await page.screenshot({path:'artifacts/visual/web-ai-settings.png'});
});
