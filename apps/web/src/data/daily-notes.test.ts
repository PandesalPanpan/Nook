import 'fake-indexeddb/auto';
import {afterEach,expect,test} from 'vitest';
import {NookDatabase,Repository} from './repository';
import {dailyNote} from './daily-notes';
import {search} from './search';
const databases:NookDatabase[]=[];
function setup(account='local:daily') {const db=new NookDatabase(crypto.randomUUID());databases.push(db);return new Repository(db,account,'web');}
afterEach(async()=>{await Promise.all(databases.splice(0).map(db=>db.delete()));});
test('daily notes reuse a date, persist body and relationships, and isolate accounts',async()=>{
  const repo=setup();
  const first=await dailyNote(repo,'2026-09-30');
  const project=await repo.create('project',{title:'Garden',outcome:'',progress:0});
  await repo.update(first.id,r=>r.kind==='dailyNote'?{...r,data:{...r.data,body:'A quiet journal',relatedIds:[project.id]}}:r);
  repo.db.close();await repo.db.open();
  const second=await dailyNote(repo,'2026-09-30');
  expect(second.id).toBe(first.id);expect(second.data.body).toBe('A quiet journal');expect(second.data.relatedIds).toEqual([project.id]);
  expect((await search(repo,'journal')).map(r=>r.id)).toEqual([first.id]);
  const other=new Repository(repo.db,'local:other','other');
  expect((await dailyNote(other,'2026-09-30')).id).toBe(first.id);
  expect((await dailyNote(other,'2026-09-30')).data.body).not.toBe(second.data.body);
  expect(await repo.db.outbox.where('accountId').equals(repo.accountId).count()).toBe(2);
});
test('two clients share a daily identity and deleted generations remain deleted',async()=>{
  const first=setup();const second=setup();
  const a=await dailyNote(first,'2026-09-30'), b=await dailyNote(second,'2026-09-30');
  expect(a.id).toBe(b.id);await first.receive(b);
  expect(await first.list('dailyNote')).toHaveLength(1);
  await first.remove(a.id);
  const replacement=await dailyNote(first,'2026-09-30');
  expect(replacement.id).toBe('daily-2026-09-30-2');
  await first.receive(b);
  expect((await first.db.records.get([first.accountId,a.id]))?.deleted).toBe(true);
  expect(await first.list('dailyNote')).toHaveLength(1);
  await expect(dailyNote(first,'2026-02-30')).rejects.toThrow();
});
