import 'fake-indexeddb/auto';
import { afterEach, expect, test } from 'vitest';
import type { Entity } from '../../../../packages/schemas/src/index';
import { AccountSession } from './account-session';
import { NookDatabase, Repository } from './repository';
import type { SyncTransport } from './sync';

const databases: NookDatabase[] = [];
function setup(factory: (accountId: string) => SyncTransport = () => ({push: async record => record, pull: async () => []})) {
  const db = new NookDatabase(crypto.randomUUID());databases.push(db);
  const guest = new Repository(db,'local:device','client');
  return {db,guest,session:new AccountSession(guest,factory)};
}
afterEach(async()=>{await Promise.all(databases.splice(0).map(db=>db.delete()));});

test('explicit sign-in merges guest data once and returning offline keeps cloud data isolated',async()=>{
  const {guest,session,db}=setup();
  const thought=await guest.capture('Guest thought');
  await session.activate('alice',true);
  expect((await session.getSnapshot().repository.list('capture'))[0].id).toBe(thought.id);
  await session.prepareSignOut();await session.activate(null);
  expect(await session.getSnapshot().repository.list('capture')).toEqual([]);
  expect(await db.outbox.where('accountId').equals('alice').count()).toBe(1);
  const later=await guest.capture('New local thought');
  await session.activate('alice');
  expect((await session.getSnapshot().repository.list('capture')).map(r=>r.id)).toEqual([thought.id]);
  expect((await guest.list('capture'))[0].id).toBe(later.id);
});

test('switching cloud accounts never imports the previous account or unrelated guest data',async()=>{
  const {guest,session}=setup();await guest.capture('Private guest');
  await session.activate('alice');await session.getSnapshot().repository.capture('Private Alice');
  await session.activate('bob',true);
  expect(await session.getSnapshot().repository.list('capture')).toEqual([]);
  expect((await guest.list('capture'))[0].data.body).toBe('Private guest');
  await session.activate('alice');
  expect((await session.getSnapshot().repository.list('capture'))[0].data.body).toBe('Private Alice');
});

test('sign-out waits for the old worker and rejects late results before changing namespace',async()=>{
  let release!: (record:Entity)=>void;let started!:()=>void;
  const ready=new Promise<void>(resolve=>{started=resolve;});
  const pending=new Promise<Entity>(resolve=>{release=resolve;});
  const {session}=setup(()=>({push:async()=>{started();return pending;},pull:async()=>[]}));
  await session.activate('alice');const alice=session.getSnapshot().repository;
  const thought=await alice.capture('Local');const syncing=session.sync();await ready;
  const switching=session.activate(null);
  await new Promise(resolve=>setTimeout(resolve,0));
  expect(session.getSnapshot().repository.accountId).toBe('local:device');
  release({...thought,updatedAt:thought.updatedAt+100,data:{...thought.data,body:'Late remote'}});
  await switching;await syncing;
  expect(session.getSnapshot().repository.accountId).toBe('local:device');
  expect((await alice.list('capture'))[0].data.body).toBe('Local');
  expect(await alice.db.outbox.where('accountId').equals('alice').count()).toBe(1);
});

test('failed sync does not block sign-out and account transitions remain serialized',async()=>{
  const {session}=setup(()=>({push:async record=>record,pull:async()=>{throw new Error('Offline');}}));
  await session.activate('alice');await expect(session.sync()).rejects.toThrow('Offline');
  await session.prepareSignOut();
  await Promise.all([session.activate(null),session.activate('bob'),session.activate(null)]);
  expect(session.getSnapshot()).toMatchObject({changing:false,repository:{accountId:'local:device'}});
});

test('failed account setup reports the error and a later transition can recover',async()=>{
  const {session,guest}=setup(account=>{if(account==='bad')throw new Error('Configuration missing');return {push:async record=>record,pull:async()=>[]};});
  await guest.capture('Still local');
  await expect(session.activate('bad',true)).rejects.toThrow('Configuration missing');
  expect((await guest.list('capture'))[0].data.body).toBe('Still local');
  expect(session.getSnapshot()).toMatchObject({changing:false,error:'Configuration missing',repository:{accountId:'local:device'}});
  await session.activate('alice');expect(session.getSnapshot().error).toBeUndefined();
});

test('a failed credential revocation can resume the same account worker safely',async()=>{
  let pushes=0;
  const {session}=setup(()=>({push:async record=>{pushes++;return record;},pull:async()=>[]}));
  await session.activate('alice');await session.getSnapshot().repository.capture('Pending');
  await session.prepareSignOut();await session.sync();expect(pushes).toBe(0);
  await session.activate('alice');await session.sync();expect(pushes).toBe(1);
});


test('original failures remain visible after metadata ACK and clear on retry or account change',async()=>{
  let remote:Entity[]=[];let fail=true;
  const {session,db}=setup(()=>({push:async record=>record,pull:async()=>remote,originals:{
    upload:async()=>{if(fail)throw new Error('private transport detail');},
    download:async()=>new Uint8Array([1]),remove:async()=>{},
  }}));
  await session.activate('alice');const repo=session.getSnapshot().repository;
  const capture=await repo.create('capture',{body:'Photo',captureType:'image',attachmentIds:[]});
  const file=await repo.create('attachment',{filename:'photo.png',mimeType:'image/png',size:1,ownerId:capture.id});
  await db.files.put({accountId:'alice',id:file.id,bytes:new Uint8Array([1])});remote=[capture,file];
  const states:boolean[]=[];const unsubscribe=session.subscribe(()=>states.push(!!session.getSnapshot().syncing));
  await expect(session.sync()).rejects.toThrow('private transport detail');
  expect(await db.outbox.where('accountId').equals('alice').count()).toBe(0);
  expect(session.getSnapshot().syncError).toContain('files could not sync');
  expect(session.getSnapshot().syncError).not.toContain('private transport detail');
  expect(session.getSnapshot().files).toMatchObject({total:1,checked:1,failed:1});
  expect(states).toContain(true);expect(session.getSnapshot().syncing).toBe(false);
  fail=false;await session.sync();
  expect(session.getSnapshot().syncError).toBeUndefined();expect(session.getSnapshot().lastSyncAt).toBeGreaterThan(0);
  expect(session.getSnapshot().files).toMatchObject({total:1,checked:1,failed:0});
  await session.activate('bob');expect(session.getSnapshot().lastSyncAt).toBeUndefined();
  expect(session.getSnapshot().files).toBeUndefined();
  expect(session.getSnapshot().syncError).toBeUndefined();unsubscribe();
});
