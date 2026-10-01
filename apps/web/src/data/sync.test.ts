import 'fake-indexeddb/auto';
import Dexie from 'dexie';
import { afterEach, expect, test, vi } from 'vitest';
import { compareVersions, type Entity } from '../../../../packages/schemas/src/index';
import { NookDatabase, Repository } from './repository';
import { SyncEngine, type SyncTransport } from './sync';
import {fileSyncProgressText,type FileSyncProgress} from './sync-progress';

const databases: NookDatabase[] = [];
function repository(accountId = 'alice', clientId = 'a') {
  const db = new NookDatabase(crypto.randomUUID()); databases.push(db);
  return new Repository(db, accountId, clientId);
}
afterEach(async () => { await Promise.all(databases.splice(0).map(db => db.delete())); });
test('file progress covers upload bytes, download, cleanup, failures and cached retry checks without stale callbacks',async()=>{
  const repo=repository(),server=new Server();let failing=true,uploads=0,downloads=0,removals=0;
  let late:((bytes:number)=>void)|undefined;
  const owner=await repo.create('capture',{body:'Files',captureType:'image',attachmentIds:[]});
  const upload=await repo.create('attachment',{filename:'upload.bin',mimeType:'application/octet-stream',size:100,ownerId:owner.id});
  await repo.db.files.put({accountId:repo.accountId,id:upload.id,bytes:new Uint8Array(100)});
  const download=await repo.create('attachment',{filename:'download.bin',mimeType:'application/octet-stream',size:4,ownerId:owner.id});
  const deleted=await repo.create('attachment',{filename:'deleted.bin',mimeType:'application/octet-stream',size:0,ownerId:owner.id});await repo.remove(deleted.id);
  server.originals={cacheKey:'progress-bucket',upload:async(_record,_bytes,_signal,progress)=>{
    uploads++;late=progress;for(let i=1;i<=100;i++)progress?.(i);if(failing)throw new Error('private failure');
  },download:async()=>{downloads++;return new Uint8Array([1,2,3,4]);},remove:async()=>{removals++;}};
  const states:FileSyncProgress[]=[];const engine=new SyncEngine(repo,server).observeProgress(progress=>states.push(progress));
  await expect(engine.sync()).rejects.toThrow('private failure');
  expect(states.at(-1)).toEqual({total:3,checked:3,failed:1,phase:'checking'});
  expect(states.some(state=>state.phase==='uploading' && state.transferred===50)).toBe(true);
  expect(states.filter(state=>state.phase==='uploading' && !!state.transferred)).toHaveLength(20);
  expect(states.some(state=>state.phase==='downloading' && state.filename==='download.bin')).toBe(true);
  expect(states.some(state=>state.phase==='removing' && state.filename==='deleted.bin')).toBe(true);
  expect((await repo.db.files.get([repo.accountId,download.id]))?.bytes).toEqual(new Uint8Array([1,2,3,4]));
  const count=states.length;late?.(90);expect(states).toHaveLength(count);
  expect(fileSyncProgressText(states.at(-1)!,false)).toBe('3 of 3 files checked · 1 file needs retry');
  failing=false;await engine.sync();expect(states.at(-1)).toEqual({total:3,checked:3,failed:0,phase:'checking'});
  expect([uploads,downloads,removals]).toEqual([2,1,1]);
  await engine.stop();const stopped=states.length;late?.(100);expect(states).toHaveLength(stopped);
});
class Server implements SyncTransport {
  originals?: SyncTransport['originals'];
  records = new Map<string, Entity>();
  online = true;
  async push(record: Entity): Promise<Entity> {
    if (!this.online) throw new Error('Offline');
    const key = `${record.accountId}:${record.id}`;
    const current = this.records.get(key);
    if (!current || compareVersions(record, current) > 0) this.records.set(key, structuredClone(record));
    return structuredClone(this.records.get(key)!);
  }
  async pull(accountId: string): Promise<Entity[]> {
    if (!this.online) throw new Error('Offline');
    return [...this.records.values()].filter(r => r.accountId === accountId).map(r => structuredClone(r));
  }
}
test('successful originals skip byte reads across reopen but recheck replacement, metadata, expiry and backend changes', async()=>{
  const repo=repository(), server=new Server(); let now=1_000, uploads=0;
  server.originals={cacheKey:'bucket-a',upload:async()=>{uploads++;},download:async()=>new Uint8Array(),remove:async()=>{}};
  const capture=await repo.capture('Cached','image',[new File(['bytes'],'a.txt')]);
  const id=capture.data.attachmentIds[0];
  await new SyncEngine(repo,server,()=>now).sync(); expect(uploads).toBe(1);
  repo.db.close();await repo.db.open();
  const reads=vi.spyOn(repo.db.files,'get');
  await new SyncEngine(repo,server,()=>now).sync();expect(uploads).toBe(1);expect(reads).not.toHaveBeenCalled();
  await repo.db.files.put({accountId:'alice',id,bytes:new TextEncoder().encode('bytes')});
  await new SyncEngine(repo,server,()=>now).sync();expect(uploads).toBe(2);
  await repo.update(id,r=>r.kind==='attachment'?{...r,data:{...r.data,filename:'renamed.txt'}}:r);
  await new SyncEngine(repo,server,()=>now).sync();expect(uploads).toBe(3);
  now+=15*60*1000;await new SyncEngine(repo,server,()=>now).sync();expect(uploads).toBe(4);
  server.originals.cacheKey='bucket-b';await new SyncEngine(repo,server,()=>now).sync();expect(uploads).toBe(5);
  expect(await repo.db.transfers.get(['bob',id])).toBeUndefined();
  await repo.remove(id);await new SyncEngine(repo,server,()=>now).sync();
  expect((await repo.db.transfers.get(['alice',id]))?.revision).toBe('');
});
test('file replacement during upload cannot inherit its acknowledgement and failures remain eligible',async()=>{
  const repo=repository(),server=new Server();let uploads=0,fail=true;
  const capture=await repo.capture('Race','image',[new File(['bytes'],'a.txt')]);const id=capture.data.attachmentIds[0];
  server.originals={cacheKey:'bucket',upload:async()=>{
    uploads++;if(fail)throw new Error('retry');
    if(uploads===2)await repo.db.files.put({accountId:'alice',id,bytes:new TextEncoder().encode('other')});
  },download:async()=>new Uint8Array(),remove:async()=>{}};
  await expect(new SyncEngine(repo,server).sync()).rejects.toThrow('retry');
  expect(await repo.db.transfers.get(['alice',id])).toBeUndefined();
  fail=false;await new SyncEngine(repo,server).sync();expect(await repo.db.transfers.get(['alice',id])).toBeUndefined();
  await new SyncEngine(repo,server).sync();expect(uploads).toBe(3);
  expect(await repo.db.transfers.get(['alice',id])).toBeDefined();
});
test('download acknowledgement survives reopen and a missing local copy is downloaded again',async()=>{
  const a=repository(),b=repository('alice','b'),server=new Server();let downloads=0;
  server.originals={cacheKey:'bucket',upload:async()=>{},download:async()=>{downloads++;return new TextEncoder().encode('bytes');},remove:async()=>{}};
  const capture=await a.capture('Download','image',[new File(['bytes'],'a.txt')]);const id=capture.data.attachmentIds[0];
  await new SyncEngine(a,server).sync();await new SyncEngine(b,server).sync();expect(downloads).toBe(1);
  b.db.close();await b.db.open();const reads=vi.spyOn(b.db.files,'get');
  await new SyncEngine(b,server).sync();expect(downloads).toBe(1);expect(reads).not.toHaveBeenCalled();
  await b.db.files.delete(['alice',id]);await new SyncEngine(b,server).sync();expect(downloads).toBe(2);
  expect((await b.db.files.get(['alice',id]))?.bytes).toEqual(new TextEncoder().encode('bytes'));
});
test('version six original files gain indexed revisions without changing their bytes',async()=>{
  const name=crypto.randomUUID(),legacy=new Dexie(name);
  legacy.version(6).stores({entities:'[accountId+id],[accountId+kind],accountId,updatedAt',outbox:'id,accountId,[accountId+nextAttemptAt],entityId',files:'[accountId+id],accountId',search:'[accountId+id],accountId,*terms'});
  await legacy.table('files').put({accountId:'alice',id:'legacy',bytes:new Uint8Array([0,255,1])});legacy.close();
  const upgraded=new NookDatabase(name);databases.push(upgraded);await upgraded.open();
  const file=await upgraded.files.get(['alice','legacy']);expect(file?.bytes).toEqual(new Uint8Array([0,255,1]));expect(file?.revision).toBeTruthy();
  expect(await upgraded.files.where('[accountId+id+revision]').equals(['alice','legacy',file!.revision!]).count()).toBe(1);
});
test('originals retry after metadata ACK and database reopen, then deletion removes both copies',async()=>{
  const a=repository(),b=repository('alice','b'),server=new Server();const files=new Map<string,Uint8Array>();let connected=false;
  server.originals={
    upload:async(record,bytes)=>{if(!connected)throw new Error('File offline');files.set(record.id,bytes.slice());},
    download:async(record)=>{if(!connected || !files.has(record.id))throw new Error('File unavailable');return files.get(record.id)!.slice();},
    remove:async(record)=>{if(!connected)throw new Error('File offline');files.delete(record.id);},
  };
  const capture=await a.capture('Original','image',[new File([new Uint8Array([0,255,1,2])],'../photo.bin')]);
  const id=capture.data.attachmentIds[0];
  await expect(new SyncEngine(a,server).sync()).rejects.toThrow('File offline');
  expect(await a.db.outbox.count()).toBe(0);a.db.close();await a.db.open();
  connected=true;await new SyncEngine(a,server).sync();await new SyncEngine(b,server).sync();
  expect((await b.db.files.get(['alice',id]))?.bytes).toEqual(new Uint8Array([0,255,1,2]));
  await a.remove(capture.id);await new SyncEngine(a,server).sync();await new SyncEngine(b,server).sync();
  expect(files.has(id)).toBe(false);expect(await b.db.files.get(['alice',id])).toBeUndefined();
});
test('a late original download cannot restore a locally deleted attachment',async()=>{
  const a=repository(),b=repository('alice','b'),server=new Server();
  const capture=await a.capture('Delete while downloading','text',[new File(['bytes'],'a.txt')]);await new SyncEngine(a,server).sync();
  const id=capture.data.attachmentIds[0];
  server.originals={cacheKey:'bucket',upload:async()=>{},remove:async()=>{},download:async()=>{await b.remove(id);return new TextEncoder().encode('bytes');}};
  await new SyncEngine(b,server).sync();expect(await b.db.files.get(['alice',id])).toBeUndefined();
  expect((await b.db.records.get(['alice',id]))?.deleted).toBe(true);
  expect(await b.db.transfers.get(['alice',id])).toBeUndefined();
});
test('capture committed during pull defers its original until metadata reaches the cloud',async()=>{
  const repo=repository(),server=new Server();let uploads=0,created=false;
  const transport:SyncTransport={push:record=>server.push(record),pull:async()=>{
    const snapshot=await server.pull('alice');
    if(!created){created=true;await repo.capture('During sync','image',[new File(['new'],'new.bin')]);}
    return snapshot;
  },originals:{upload:async()=>{uploads++;},download:async()=>new Uint8Array(),remove:async()=>{}}};
  const engine=new SyncEngine(repo,transport);await engine.sync();expect(uploads).toBe(0);expect(await repo.db.outbox.count()).toBe(2);
  await engine.sync();expect(uploads).toBe(1);expect(await repo.db.outbox.count()).toBe(0);
});
test('offline create and update retry durably and sync after reconnect', async () => {
  const repo = repository(); const server = new Server(); server.online = false;
  let now = 0; const engine = new SyncEngine(repo, server, () => now);
  const task = await repo.create('task', {title: 'First', completed: false});
  await expect(engine.sync()).rejects.toThrow('Offline');
  expect((await repo.db.outbox.toArray())[0]).toMatchObject({attempts: 1, nextAttemptAt: 1000});
  repo.db.close(); await repo.db.open();
  await repo.update(task.id, r => r.kind === 'task' ? {...r, data: {...r.data, title: 'Edited offline'}} : r);
  server.online = true; now = 1000; await engine.sync();
  expect(await repo.db.outbox.count()).toBe(0);
  expect((await server.pull('alice'))[0].data).toMatchObject({title: 'Edited offline'});
});
test('stale reconnecting client cannot resurrect offline deletion', async () => {
  const a = repository('alice', 'a'); const b = repository('alice', 'b'); const server = new Server();
  const first = await a.create('note', {title: 'Note', body: 'Body', attachmentIds: []});
  await new SyncEngine(a, server).sync(); await new SyncEngine(b, server).sync();
  await a.remove(first.id); await new SyncEngine(a, server).sync();
  // Replay an old queued version from a client that has been disconnected.
  await b.db.outbox.put({id: `alice:${first.id}`, accountId: 'alice', entityId: first.id, record: first, attempts: 0, nextAttemptAt: 0});
  await new SyncEngine(b, server).sync();
  expect(await a.list('note')).toEqual([]); expect(await b.list('note')).toEqual([]);
  expect((await server.pull('alice'))[0].deleted).toBe(true);
});
test('local guest sign-in preserves IDs and merges into populated account once', async () => {
  const guest = repository('local:device'); const cloud = new Repository(guest.db, 'alice', 'a');
  const capture = await guest.create('capture', {body: 'Guest thought', captureType: 'text', attachmentIds: []});
  const cloudNote = await cloud.create('note', {title: 'Cloud', body: '', attachmentIds: []});
  const migrated = await guest.mergeIntoAccount('alice'); await guest.mergeIntoAccount('alice');
  expect((await migrated.list('capture'))[0].id).toBe(capture.id);
  expect((await migrated.list('note'))[0].id).toBe(cloudNote.id);
  expect(await guest.list('capture')).toEqual([]);
  expect(await guest.db.outbox.count()).toBe(2);
  const server = new Server(); await new SyncEngine(migrated, server).sync();
  expect(await server.pull('alice')).toHaveLength(2);
});
test('same record IDs in different accounts cannot overwrite one another', async () => {
  const alice = repository(); const bob = new Repository(alice.db, 'bob', 'b');
  const note = await alice.create('note', {title: 'Alice', body: '', attachmentIds: []});
  await bob.receive({...note, accountId: 'bob', data: {...note.data, title: 'Bob'}});
  expect((await alice.list('note'))[0].data).toMatchObject({title: 'Alice'});
  expect((await bob.list('note'))[0].data).toMatchObject({title: 'Bob'});
});
test('deletion defeats even a later-clock disconnected edit', async () => {
  const repo = repository(); const server = new Server();
  const note = await repo.create('note', {title: 'Original', body: '', attachmentIds: []});
  await repo.remove(note.id); await new SyncEngine(repo, server).sync();
  const winner = await server.push({...note, clientId: 'other', updatedAt: note.updatedAt + 1_000_000});
  expect(winner.deleted).toBe(true);
  await repo.receive({...note, updatedAt: note.updatedAt + 1_000_000});
  expect(await repo.list('note')).toEqual([]);
  await expect(repo.update(note.id, r => ({...r, deleted: false}))).rejects.toThrow('Record deleted');
});
test('equal-time concurrent edits converge by client ID', async () => {
  const a = repository('alice', 'a'); const b = repository('alice', 'b'); const server = new Server();
  const note = await a.create('note', {title: 'a', body: '', attachmentIds: []});
  const other = {...note, clientId: 'b', data: {...note.data, title: 'b'}};
  await b.receive(other);
  await b.db.outbox.put({id: `alice:${note.id}`, accountId: 'alice', entityId: note.id, record: other, attempts: 0, nextAttemptAt: 0});
  await new SyncEngine(a, server).sync(); await new SyncEngine(b, server).sync(); await new SyncEngine(a, server).sync();
  expect((await a.list('note'))[0]).toEqual(other); expect((await b.list('note'))[0]).toEqual(other);
});
test('local edit during push stays queued for the next sync', async () => {
  const repo = repository(); const server = new Server();
  const note = await repo.create('note', {title: 'Old', body: '', attachmentIds: []});
  const transport: SyncTransport = {
    push: async record => {
      await repo.update(note.id, r => r.kind === 'note' ? {...r, data: {...r.data, title: 'New'}} : r);
      return server.push(record);
    }, pull: (...args) => server.pull(args[0]),
  };
  await new SyncEngine(repo, transport).sync();
  expect(await repo.db.outbox.count()).toBe(1);
  await new SyncEngine(repo, server).sync();
  expect((await server.pull('alice'))[0].data).toMatchObject({title: 'New'});
});
test('stopping a worker prevents late network results from entering local state', async () => {
  const repo = repository(); const note = await repo.create('note', {title: 'Local', body: '', attachmentIds: []});
  let resolve!: (record: Entity) => void; let started!: () => void;
  const pending = new Promise<Entity>(r => { resolve = r; });
  const ready = new Promise<void>(r => { started = r; });
  const engine = new SyncEngine(repo, {push: () => { started(); return pending; }, pull: async () => []});
  const sync = engine.sync(); await ready;
  const stopped = engine.stop(); resolve({...note, updatedAt: note.updatedAt + 100, data: {...note.data, title: 'Late'}});
  await stopped; await sync;
  expect((await repo.list('note'))[0].data).toMatchObject({title: 'Local'});
  expect(await repo.db.outbox.count()).toBe(1);
});
test('schema migration retains legacy local records', async () => {
  const name = crypto.randomUUID(); const legacy = new Dexie(name);
  legacy.version(1).stores({records: 'id,[accountId+kind],accountId,updatedAt', outbox: 'id,accountId,[accountId+nextAttemptAt],entityId'});
  const record: Entity<'task'> = {id: 'legacy', kind: 'task', accountId: 'local:legacy', clientId: 'old', schemaVersion: 1, createdAt: 1, updatedAt: 1, deleted: false, archived: false, data: {title: 'Saved', completed: false}};
  await legacy.table('records').put(record);
  await legacy.table('outbox').put({id: 'local:legacy:legacy', accountId: record.accountId, entityId: record.id, record, attempts: 2, nextAttemptAt: 1000});
  legacy.close();
  const db = new NookDatabase(name); databases.push(db);
  expect(await db.records.get(['local:legacy', 'legacy'])).toEqual(record);
  expect(await db.outbox.get('local%3Alegacy:legacy')).toMatchObject({record, attempts: 2, nextAttemptAt: 1000});
  expect(await db.search.get(['local:legacy', 'legacy'])).toMatchObject({title: 'Saved'});
});
