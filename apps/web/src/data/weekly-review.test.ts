import 'fake-indexeddb/auto';
import {afterEach,expect,test} from 'vitest';
import {NookDatabase,Repository} from './repository';
import {saveWeeklyFocus,weekStart} from './weekly-review';
const databases:NookDatabase[]=[];
function setup(account='local:review') {const db=new NookDatabase(crypto.randomUUID());databases.push(db);return new Repository(db,account,'web');}
afterEach(async()=>{await Promise.all(databases.splice(0).map(db=>db.delete()));});
test('week boundaries use Monday across month and year transitions',()=>{
  expect(weekStart('2026-09-30')).toBe('2026-09-28');expect(weekStart('2027-01-01')).toBe('2026-12-28');expect(weekStart('2026-09-28')).toBe('2026-09-28');
});
test('weekly focus updates one note, isolates accounts, and cannot resurrect deleted generations',async()=>{
  const repo=setup();const first=await saveWeeklyFocus(repo,'2026-09-30','Finish prototype');
  expect((await saveWeeklyFocus(repo,'2026-10-04','Prepare demo')).id).toBe(first.id);
  expect(await repo.list('note')).toHaveLength(1);expect((await repo.list('note'))[0].data.body).toBe('Prepare demo');
  const other=new Repository(repo.db,'local:other','other');expect((await saveWeeklyFocus(other,'2026-09-30','Other focus')).id).toBe(first.id);
  await repo.remove(first.id);const replacement=await saveWeeklyFocus(repo,'2026-09-30','New focus');expect(replacement.id).toBe('weekly-focus-2026-09-28-2');
  await repo.receive(first);expect((await repo.db.records.get([repo.accountId,first.id]))?.deleted).toBe(true);
  await expect(saveWeeklyFocus(repo,'2026-09-30','  ')).rejects.toThrow();
});
