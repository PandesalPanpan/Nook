import 'fake-indexeddb/auto';
import { afterEach, expect, test } from 'vitest';
import { NookDatabase, Repository } from './repository';
const databases: NookDatabase[] = [];
function setup() {
  const db = new NookDatabase(crypto.randomUUID()); databases.push(db);
  return new Repository(db, 'local', 'client-a');
}
afterEach(async () => { await Promise.all(databases.splice(0).map(db => db.delete())); });
test('capture and outbox survive reopening the local database', async () => {
  const repo = setup();
  const capture = await repo.create('capture', {body: 'A thought', captureType: 'text', attachmentIds: []});
  repo.db.close(); await repo.db.open();
  expect((await repo.list('capture'))[0].id).toBe(capture.id);
  expect(await repo.db.outbox.count()).toBe(1);
});
test('clarification keeps processed history, preserves attachments, and reuses an idempotent result', async () => {
  const repo = setup();
  const capture = await repo.create('capture', {body: 'Note', captureType: 'image', attachmentIds: ['photo']});
  const attachment = await repo.create('attachment', {filename:'photo.png',mimeType:'image/png',size:4,ownerId:capture.id}, 'photo');
  await repo.db.files.put({id:attachment.id,accountId:repo.accountId,bytes:new Uint8Array([1,2,3,4])});
  const processedNote = await repo.process(capture.id, 'note');
  if(processedNote.kind!=='note') throw new Error('Expected a Note result');
  const note=processedNote;
  expect(note.kind).toBe('note');
  expect(note.data.attachmentIds).toEqual(['photo']);
  expect((await repo.db.records.get([repo.accountId,'photo']))?.data).toMatchObject({ownerId:note.id});
  expect(await repo.db.files.get([repo.accountId,'photo'])).toMatchObject({bytes:new Uint8Array([1,2,3,4])});
  expect(await repo.list('capture')).toMatchObject([{id:capture.id,schemaVersion:2,data:{originalBody:'Note',processedIds:[note.id]}}]);
  expect(await repo.db.outbox.count()).toBe(3);
  await expect(repo.process(capture.id, 'task')).resolves.toMatchObject({id:note.id,kind:'note'});
  expect(await repo.list('task')).toEqual([]);
});

test('split creates separately linked Task and Note in one Resource and keeps draft edits after reopen', async () => {
  const repo=setup();
  const resource=await repo.create('resource',{title:'Bedroom',description:''});
  const capture=await repo.create('capture',{body:'Original wording',captureType:'text',attachmentIds:[]});
  await repo.update(capture.id,record=>record.kind==='capture'?{...record,schemaVersion:2,data:{...record.data,body:'Edited thought',originalBody:'Original wording',clarificationDraft:{mode:'split',action:'Replace the bulb',noteTitle:'Lighting plan',noteBody:'Warm lights by the bed',homeId:resource.id,relatedIds:[]}}}:record);
  const results=await repo.clarify(capture.id,{mode:'split',body:'Edited thought',action:'Replace the bulb',noteTitle:'Lighting plan',noteBody:'Warm lights by the bed',homeId:resource.id,relatedIds:[]});
  const noteResult=results.find((item):item is import('../../../../packages/schemas/src').Entity<'note'>=>item.kind==='note');
  const taskResult=results.find((item):item is import('../../../../packages/schemas/src').Entity<'task'>=>item.kind==='task');
  if(!noteResult||!taskResult) throw new Error('Expected linked Note and Task results');
  const note=noteResult,task=taskResult;
  expect(note).toMatchObject({kind:'note',schemaVersion:2,data:{title:'Lighting plan',body:'Warm lights by the bed',resourceId:resource.id,relatedIds:[task.id]}});
  expect(task).toMatchObject({kind:'task',schemaVersion:2,data:{title:'Replace the bulb',resourceId:resource.id,relatedIds:[note.id]}});
  expect(task.data.doDate).toBeUndefined();
  expect((await repo.list('capture')).find(item=>item.id===capture.id)?.data).toMatchObject({body:'Edited thought',originalBody:'Original wording',processedIds:[note.id,task.id]});
  repo.db.close(); await repo.db.open();
  expect((await repo.list('task'))[0].data.resourceId).toBe(resource.id);
  const retry=await repo.clarify(capture.id,{mode:'task',body:'Changed after completion'});
  expect(retry.map(item=>item.id)).toEqual([note.id,task.id]);
  expect((await repo.list('note')).filter(item=>item.data.sourceCaptureId===capture.id)).toHaveLength(1);
});

test('clarification rejects unavailable homes and rolls back a failed payload write', async () => {
  const repo=setup();
  const capture=await repo.create('capture',{body:'Plan the trip',captureType:'text',attachmentIds:[]});
  const archived=await repo.create('area',{title:'Old',responsibility:'',standards:''});
  await repo.update(archived.id,record=>({...record,archived:true}));
  await expect(repo.clarify(capture.id,{mode:'task',body:'Plan the trip',homeId:'missing-resource'})).rejects.toThrow('active Project, Area, or Resource');
  await expect(repo.clarify(capture.id,{mode:'task',body:'Plan the trip',homeId:archived.id})).rejects.toThrow('active Project, Area, or Resource');
  await expect(repo.clarify(capture.id,{mode:'task',body:'Plan the trip',deadline:'2026-02-30'})).rejects.toThrow();
  expect(await repo.list('task')).toEqual([]);
  expect((await repo.list('capture')).find(item=>item.id===capture.id)?.data.processedAt).toBeUndefined();
});

test('retry after result deletion never recreates the tombstoned item', async () => {
  const repo=setup();
  const capture=await repo.create('capture',{body:'Call Pat',captureType:'task',attachmentIds:[]});
  const [task]=await repo.clarify(capture.id,{mode:'task',body:'Call Pat'});
  await repo.remove(task.id);
  await expect(repo.clarify(capture.id,{mode:'task',body:'Call Pat'})).rejects.toThrow('results are unavailable');
  expect(await repo.list('task')).toEqual([]);
  expect((await repo.db.records.get([repo.accountId,task.id]))?.deleted).toBe(true);
});
test('offline deletion rejects stale remote resurrection and isolates accounts', async () => {
  const repo = setup();
  const task = await repo.create('task', {title: 'Do it', completed: false});
  await repo.remove(task.id); await repo.receive(task);
  expect(await repo.list('task')).toEqual([]);
  await expect(repo.receive({...task, accountId: 'someone-else'})).rejects.toThrow('Account mismatch');
});
test('equal-clock deletion wins across clients', async () => {
  const repo = setup();
  const note = await repo.create('note', {title: '', body: 'text', attachmentIds: []});
  await repo.receive({...note, deleted: true, clientId: 'client-0'});
  expect(await repo.list('note')).toEqual([]);
  expect(await repo.db.outbox.count()).toBe(0);
});


test('typed title links bind to IDs on save and survive rename and database reopen', async () => {
  const repo = setup();
  const target = await repo.create('note', {title:'Garden',body:'',attachmentIds:[]});
  const body = '😀 [[Garden]] and [[Garden|Plants]]\n\n`[[Garden]]`\n\n```text\n[[Garden]]\n```\n\n[existing [[Garden]]](https://example.com)';
  const source = await repo.create('note', {title:'Source',body,attachmentIds:[]});
  const expected = body.replace('[[Garden]] and [[Garden|Plants]]', `[[${target.id}|Garden]] and [[${target.id}|Plants]]`);
  expect(source.data.body).toBe(expected);
  await repo.update(target.id, r=>r.kind==='note'?{...r,data:{...r.data,title:'Renamed'}}:r);
  repo.db.close(); await repo.db.open();
  expect((await repo.list('note')).find(r=>r.id===source.id)?.data.body).toBe(expected);
  const queued = await repo.db.outbox.where('entityId').equals(source.id).first();
  expect(queued?.record.data).toMatchObject({body:expected});
});

test('save preserves unresolved ambiguous escaped and foreign-account title links', async () => {
  const repo = setup();
  await repo.create('note', {title:'Duplicate',body:'',attachmentIds:[]});
  await repo.create('note', {title:'Duplicate',body:'',attachmentIds:[]});
  const foreign = new Repository(repo.db,'other','client');
  await foreign.create('note', {title:'Foreign',body:'',attachmentIds:[]});
  await repo.create('note', {title:'Garden',body:'',attachmentIds:[]});
  const body = String.raw`[[Duplicate]] [[Missing]] [[Foreign]] \[\[Garden]]`;
  const source = await repo.create('note',{title:'Source',body,attachmentIds:[]});
  expect(source.data.body).toBe(body);
  await repo.update(source.id,r=>r.kind==='note'?{...r,data:{...r.data,body:'[[Garden]]'}}:r);
  expect((await repo.list('note')).find(r=>r.id===source.id)?.data.body).toMatch(/^\[\[[A-Za-z0-9-]+\|Garden\]\]$/);
});
