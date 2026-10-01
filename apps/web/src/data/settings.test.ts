import 'fake-indexeddb/auto';
import {afterEach,expect,test} from 'vitest';
import {NookDatabase,Repository} from './repository';
import {currentSettings} from './settings';
import {exportBackup,restoreBackup} from './backup';
const databases:NookDatabase[]=[];
function setup(account='local:tips') {const db=new NookDatabase(crypto.randomUUID());databases.push(db);return new Repository(db,account,'client');}
afterEach(async()=>{await Promise.all(databases.splice(0).map(db=>db.delete()));});
test('concurrent first settings edits stay atomic, preserve unrelated preferences, and reopen per account',async()=>{
  const repo=setup();
  await Promise.all([repo.updateSettings(s=>({...s,dismissedTips:[...s.dismissedTips,'para:area']})),repo.updateSettings(s=>({...s,inboxReviewEnabled:true,dailyNotesEnabled:true}))]);
  expect(await repo.list('settings')).toHaveLength(1);
  repo.db.close();await repo.db.open();
  expect(currentSettings(await repo.list('settings'))?.data).toEqual({tipsEnabled:true,dismissedTips:['para:area'],inboxReviewEnabled:true,dailyNotesEnabled:true});
  const bob=new Repository(repo.db,'bob','client');await bob.updateSettings(s=>({...s,tipsEnabled:false}));
  expect(currentSettings(await repo.list('settings'))?.data.tipsEnabled).toBe(true);
  expect(await repo.db.outbox.where('accountId').equals(repo.accountId).count()).toBe(1);
});
test('dismissals survive backups and incoming cloud settings, and deleted settings are not revived',async()=>{
  const repo=setup();await repo.updateSettings(s=>({...s,dismissedTips:['para:inbox']}));
  const restored=setup();await restoreBackup(restored,await exportBackup(repo));
  expect(currentSettings(await restored.list('settings'))?.data.dismissedTips).toEqual(['para:inbox']);
  const settings=currentSettings(await repo.list('settings'))!;
  await repo.receive({...settings,updatedAt:settings.updatedAt+100,data:{...settings.data,tipsEnabled:false}});
  await repo.updateSettings(s=>({...s,inboxReviewEnabled:true}));
  expect(currentSettings(await repo.list('settings'))?.data.tipsEnabled).toBe(false);
  await repo.remove(settings.id);await repo.updateSettings(s=>({...s,dismissedTips:['para:archive']}));
  expect((await repo.db.records.get([repo.accountId,settings.id]))?.deleted).toBe(true);
  expect(currentSettings(await repo.list('settings'))?.data.dismissedTips).toEqual(['para:archive']);
});
