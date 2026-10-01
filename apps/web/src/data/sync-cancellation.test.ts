import 'fake-indexeddb/auto';
import {afterEach,expect,test} from 'vitest';
import {NookDatabase,Repository} from './repository';
import {SyncEngine} from './sync';
import type {Entity} from '../../../../packages/schemas/src';
const databases:NookDatabase[]=[];
afterEach(async()=>{await Promise.all(databases.splice(0).map(db=>db.delete()));});
function repository(){const db=new NookDatabase(crypto.randomUUID());databases.push(db);return new Repository(db,'alice','client');}
test('stop returns even when a push never settles and pending edits remain durable',async()=>{
  const repo=repository();await repo.capture('Keep offline');
  let started!:()=>void;const ready=new Promise<void>(resolve=>{started=resolve;});
  const engine=new SyncEngine(repo,{push:()=>{started();return new Promise(()=>{});},pull:async()=>[]});
  const syncing=engine.sync();await ready;await engine.stop();await syncing;
  expect(await repo.db.outbox.count()).toBe(1);
  expect((await repo.list('capture'))[0].data.body).toBe('Keep offline');
});
test('stop cancels an unresolved pull and late remote data cannot enter the database',async()=>{
  const repo=repository();let release!:(records:Entity[])=>void;let started!:()=>void;
  const ready=new Promise<void>(resolve=>{started=resolve;});
  const pending=new Promise<Entity[]>(resolve=>{release=resolve;});
  const engine=new SyncEngine(repo,{push:async record=>record,pull:()=>{started();return pending;}});
  const syncing=engine.sync();await ready;await engine.stop();await syncing;
  release([{id:'remote',accountId:'alice',clientId:'other',kind:'capture',schemaVersion:1,createdAt:1,updatedAt:1,deleted:false,archived:false,data:{body:'Late',captureType:'text',attachmentIds:[]}}]);
  await Promise.resolve();expect(await repo.list('capture')).toEqual([]);
});
