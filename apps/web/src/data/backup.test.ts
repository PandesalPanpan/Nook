import 'fake-indexeddb/auto';
import { afterEach, expect, test } from 'vitest';
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { NookDatabase, Repository } from './repository';
import { exportBackup, importMarkdown, restoreBackup } from './backup';
const databases: NookDatabase[] = [];
function repo(account = 'local:guest') {
  const db = new NookDatabase(crypto.randomUUID()); databases.push(db);
  return new Repository(db, account, 'client');
}
afterEach(async () => { await Promise.all(databases.splice(0).map(db => db.delete())); });
test('offline photo capture processes and roundtrips original bytes and Markdown', async () => {
  const source = repo();
  const capture = await source.capture('', 'image', [new File([new Uint8Array([1,2,3])], 'original.png', {type: 'image/png'})]);
  const note = await source.process(capture.id, 'note');
  await source.update(note.id, r => r.kind === 'note' ? {...r, data: {...r.data, title: 'My note', body: '**Markdown**'}} : r);
  const attachment = (await source.list('attachment'))[0];
  expect(attachment.data).toMatchObject({ownerId: note.id});
  const zip = await exportBackup(source);
  expect(strFromU8(unzipSync(zip)[`notes/${note.id}.md`])).toBe('# My note\n\n**Markdown**');
  const destination = repo('local:other'); await restoreBackup(destination, zip);
  expect((await destination.list('note'))[0].data).toMatchObject({body: '**Markdown**'});
  expect((await destination.db.files.get(['local:other', attachment.id]))?.bytes).toEqual(new Uint8Array([1,2,3]));
  expect(await destination.list('capture')).toMatchObject([{id:capture.id,schemaVersion:2,data:{body:'',originalBody:'',processedIds:[note.id]}}]);
  expect(await restoreBackup(destination, zip)).toBe(0);
});
test('invalid manifest cannot partially restore a valid record', async () => {
  const source = repo(); await source.capture('Thought');
  const entries = unzipSync(await exportBackup(source));
  const manifest = JSON.parse(strFromU8(entries['nook.json'])); manifest.records.push({id: 'bad'});
  entries['nook.json'] = strToU8(JSON.stringify(manifest));
  const destination = repo(); await expect(restoreBackup(destination, zipSync(entries))).rejects.toThrow();
  expect(await destination.db.records.count()).toBe(0); expect(await destination.db.outbox.count()).toBe(0);
});
test('restore cannot undo a newer deletion', async () => {
  const source = repo(); const capture = await source.capture('Keep safe'); const zip = await exportBackup(source);
  await source.remove(capture.id); await restoreBackup(source, zip);
  expect(await source.list('capture')).toEqual([]);
});
test('deleting an owner deletes original files and backup restore cannot bring them back', async () => {
  const source = repo();
  const capture = await source.capture('', 'image', [new File([new Uint8Array([1,2,3])], 'photo.png', {type: 'image/png'})]);
  const note = await source.process(capture.id, 'note');
  const attachment = (await source.list('attachment'))[0];
  const backup = await exportBackup(source);
  await source.remove(note.id);
  expect(await source.db.files.get([source.accountId, attachment.id])).toBeUndefined();
  expect(await source.list('attachment')).toEqual([]);
  await restoreBackup(source, backup);
  expect(await source.list('note')).toEqual([]);
  expect(await source.list('attachment')).toEqual([]);
  expect(await source.list('capture')).toMatchObject([{id:capture.id,data:{processedIds:[note.id],processedAt:expect.any(Number)}}]);
  expect(await source.db.files.get([source.accountId, attachment.id])).toBeUndefined();
});
test('capture stores no empty thought and file data migrates with guest account', async () => {
  const source = repo(); await expect(source.capture(' ')).rejects.toThrow('Add a thought');
  await source.capture('File', 'image', [new File(['hello'], 'hello.txt')]);
  await source.mergeIntoAccount('alice');
  expect(await source.db.files.where('accountId').equals('local:guest').count()).toBe(0);
  expect(await source.db.files.where('accountId').equals('alice').count()).toBe(1);
});
test('Markdown import preserves editor syntax and is atomic', async () => {
  const repository = repo();
  const [note] = await importMarkdown(repository, [{name: 'Journal.md', body: '# Heading\n[[Other note]]\n- [ ] Task'}]);
  expect(note.data.title).toBe('Journal'); expect(note.data.body).toContain('[[Other note]]');
  await expect(importMarkdown(repository, [{name: 'Fine.md', body: 'Fine'}, {name: 'x'.repeat(10_001), body: ''}])).rejects.toThrow();
  expect(await repository.list('note')).toHaveLength(1);
});
test('attaching to an existing note commits metadata, originals and IDs together',async()=>{
  const db=new NookDatabase(crypto.randomUUID()); const repo=new Repository(db,'local:attach','test');
  try {
    const note=await repo.create('note',{title:'Attachments',body:'Text',attachmentIds:[]});
    const file=new File([new Uint8Array([1,2,3])],'proof.bin',{type:'application/octet-stream'});
    await repo.attach(note.id,[file]);
    const saved=await db.records.get([repo.accountId,note.id]);
    expect(saved?.kind==='note'&&saved.data.attachmentIds.length).toBe(1);
    const attachment=(await repo.list('attachment'))[0];
    expect(attachment.data.ownerId).toBe(note.id);
    expect(Array.from((await db.files.get([repo.accountId,attachment.id]))!.bytes)).toEqual([1,2,3]);
    await repo.remove(note.id);
    await expect(repo.attach(note.id,[file])).rejects.toThrow('Record unavailable');
    expect(await repo.db.files.count()).toBe(0);
    expect((await repo.list('attachment')).length).toBe(0);
  } finally {await db.delete();}
});
