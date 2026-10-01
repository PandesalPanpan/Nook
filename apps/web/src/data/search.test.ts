import 'fake-indexeddb/auto';
import { afterEach, expect, test } from 'vitest';
import { NookDatabase, Repository } from './repository';
import { search } from './search';
import { exportBackup, restoreBackup } from './backup';
const databases: NookDatabase[] = [];
function setup(accountId = 'local:test') { const db = new NookDatabase(crypto.randomUUID()); databases.push(db); return new Repository(db, accountId, 'web'); }
afterEach(async () => { await Promise.all(databases.splice(0).map(db => db.delete())); });
test('local search covers PARA and tasks, matches prefixes and intersects words', async () => {
  const repo = setup();
  const note = await repo.create('note', {title: 'Read', body: 'Garden planning café', attachmentIds: []});
  await repo.create('task', {title: 'Garden maintenance', completed: false});
  await repo.create('project', {title: 'Garden redesign', outcome: 'A calm space', progress: 0});
  await repo.create('area', {title: 'Garden', responsibility: 'Care', standards: 'Water weekly'});
  await repo.create('resource', {title: 'Garden reference', description: 'Useful books'});
  expect(await search(repo, 'gard')).toHaveLength(5);
  expect((await search(repo, 'garden plan'))[0].id).toBe(note.id);
  expect(await search(repo, 'CAFÉ')).toHaveLength(1);
  repo.db.close(); await repo.db.open();
  expect(await search(repo, 'gard')).toHaveLength(5);
});
test('index reflects edits, deletion, archival and incoming cloud versions atomically', async () => {
  const repo = setup(); const capture = await repo.capture('Original thought');
  await repo.update(capture.id, r => r.kind === 'capture' ? {...r, data: {...r.data, body: 'Revised'}} : r);
  expect(await search(repo, 'original')).toEqual([]); expect(await search(repo, 'revis')).toHaveLength(1);
  await repo.update(capture.id, r => ({...r, archived: true}));
  expect(await search(repo, 'revis', {archived: false})).toEqual([]);
  expect(await search(repo, 'revis', {archived: true})).toHaveLength(1);
  const latest = (await repo.list('capture'))[0];
  await repo.receive({...latest, updatedAt: latest.updatedAt + 1, data: {...capture.data, body: 'Remote'}});
  expect(await search(repo, 'remote')).toHaveLength(1);
  await repo.remove(capture.id); expect(await search(repo, 'remote')).toEqual([]);
});
test('project, area and before filters work without cloud access', async () => {
  const repo = setup();
  const area = await repo.create('area', {title: 'Health', responsibility: 'Wellbeing', standards: ''});
  const project = await repo.create('project', {title: 'Morning routine', outcome: '', progress: 0, areaId: area.id});
  const task = await repo.create('task', {title: 'Walk', completed: false, projectId: project.id, areaId: area.id, doDate: '2026-09-30', deadline: '2026-10-02'});
  expect((await search(repo, 'walk project:"Morning routine" area:Health before:2026-10-01'))[0].id).toBe(task.id);
  expect(await search(repo, 'walk before:2026-09-30')).toEqual([]);
  expect(await search(repo, 'walk area:Work')).toEqual([]);
});
test('migration and backup restore rebuild searchable account-scoped records', async () => {
  const repo = setup(); await repo.capture('Guest searchable');
  const zip = await exportBackup(repo); const restored = setup('local:other'); await restoreBackup(restored, zip);
  expect(await search(restored, 'search')).toHaveLength(1);
  const alice = await repo.mergeIntoAccount('alice');
  expect(await search(repo, 'guest')).toEqual([]); expect(await search(alice, 'guest')).toHaveLength(1);
  const bob = new Repository(repo.db, 'bob', 'web'); await bob.capture('Bob searchable');
  expect(await search(alice, 'bob')).toEqual([]); expect(await search(bob, 'search')).toHaveLength(1);
});
