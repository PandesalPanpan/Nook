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
test('processing is atomic and preserves note attachments', async () => {
  const repo = setup();
  const capture = await repo.create('capture', {body: 'Note', captureType: 'image', attachmentIds: ['photo']});
  const note = await repo.process(capture.id, 'note');
  expect(note.kind).toBe('note');
  expect(await repo.list('capture')).toEqual([]);
  expect(await repo.db.outbox.count()).toBe(2);
  await expect(repo.process(capture.id, 'task')).rejects.toThrow('Capture unavailable');
  expect(await repo.list('task')).toEqual([]);
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
