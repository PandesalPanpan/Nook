import 'fake-indexeddb/auto';
import {afterEach,expect,test,vi} from 'vitest';
import {AccountSession} from './account-session';
import {NookDatabase,Repository} from './repository';
import {SyncScheduler} from './sync-scheduler';
const databases:NookDatabase[]=[];const schedulers:SyncScheduler[]=[];
afterEach(async()=>{await Promise.all(schedulers.splice(0).map(s=>s.stop()));await Promise.all(databases.splice(0).map(db=>db.delete()));});
test('committed local edits sync automatically and reconnect retries pending work',async()=>{
  const db=new NookDatabase(crypto.randomUUID());databases.push(db);
  let online=false;const remote=new Map<string,string>();
  const accounts=new AccountSession(new Repository(db,'local:test','client'),()=>({push:async record=>{
    if(!online)throw new Error('Offline');remote.set(record.id,JSON.stringify(record));return record;
  },pull:async()=>[]}));
  await accounts.activate('alice');const events=new EventTarget();const scheduler=new SyncScheduler(accounts,events,60_000);schedulers.push(scheduler);scheduler.start();
  const repo=accounts.getSnapshot().repository;const capture=await repo.capture('Pending offline');
  await vi.waitFor(async()=>expect((await db.outbox.toArray())[0].attempts).toBe(1));
  expect(remote.size).toBe(0);
  // Persisted retry becomes due; the online event wakes the scheduler.
  await db.outbox.toCollection().modify(op=>{op.nextAttemptAt=0;});online=true;events.dispatchEvent(new Event('online'));
  await vi.waitFor(()=>expect(remote.has(capture.id)).toBe(true));
  await repo.update(capture.id,r=>r.kind==='capture'?{...r,data:{...r.data,body:'Edited locally'}}:r);
  await vi.waitFor(()=>expect(JSON.parse(remote.get(capture.id)!).data.body).toBe('Edited locally'));
  await vi.waitFor(async()=>expect(await db.outbox.count()).toBe(0));
});
test('local-only accounts never call the transport and scheduler stop prevents further work',async()=>{
  const db=new NookDatabase(crypto.randomUUID());databases.push(db);const push=vi.fn(async record=>record);
  const accounts=new AccountSession(new Repository(db,'local:test','client'),()=>({push,pull:async()=>[]}));
  const events=new EventTarget();const scheduler=new SyncScheduler(accounts,events,60_000);schedulers.push(scheduler);scheduler.start();
  await accounts.getSnapshot().repository.capture('Local only');events.dispatchEvent(new Event('online'));
  expect(push).not.toHaveBeenCalled();await scheduler.stop();await accounts.activate('alice',true);
  events.dispatchEvent(new Event('online'));expect(push).not.toHaveBeenCalled();
  expect(await db.outbox.where('accountId').equals('alice').count()).toBe(1);
});
