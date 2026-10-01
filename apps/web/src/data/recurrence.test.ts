import 'fake-indexeddb/auto';
import {afterEach, expect, test} from 'vitest';
import {NookDatabase, Repository} from './repository';
import {nextOccurrence, occurrenceId} from '../../../../packages/schemas/src/recurrence';
import {parseEntity} from '../../../../packages/schemas/src/validation';
const databases: NookDatabase[] = [];
function setup() {const db = new NookDatabase(crypto.randomUUID()); databases.push(db); return new Repository(db, 'local:test', 'web');}
afterEach(async () => {await Promise.all(databases.splice(0).map(db => db.delete()));});

test('monthly cadence returns to the original day after February and uses leap years', () => {
  const rule = {frequency: 'monthly', interval: 1, anchorDate: '2028-01-31'} as const;
  expect(nextOccurrence(rule, '2028-01-31')).toBe('2028-02-29');
  expect(nextOccurrence(rule, '2028-02-29')).toBe('2028-03-31');
  expect(nextOccurrence({...rule, interval: 3}, '2028-01-31')).toBe('2028-04-30');
  expect(nextOccurrence({frequency: 'weekly', interval: 2, anchorDate: '2026-09-30'}, '2026-10-01')).toBe('2026-10-14');
});
test('completion atomically keeps history, advances both dates, carries reminders and project next action', async () => {
  const repo = setup();
  const project = await repo.create('project', {title: 'Garden', outcome: '', progress: 0});
  const task = await repo.create('task', {title: 'Water plants', completed: false, projectId: project.id, doDate: '2026-09-30', deadline: '2026-10-02'});
  await repo.update(project.id, r => r.kind === 'project' ? {...r, data: {...r.data, nextActionId: task.id}} : r);
  const reminder = await repo.create('reminder', {targetId: task.id, scheduledAt: Date.parse('2026-09-30T09:00:00'), type: 'task', skipIfInboxEmpty: true});
  await repo.update(task.id, r => r.kind === 'task' ? {...r, data: {...r.data, reminderId: reminder.id}} : r);
  await repo.setRecurrence(task.id, {frequency: 'weekly', interval: 1, anchorDate: '2026-09-30'});
  const complete = () => repo.update(task.id, r => r.kind === 'task' ? {...r, data: {...r.data, completed: true}} : r);
  await complete();
  const tasks = await repo.list('task');
  expect(tasks).toHaveLength(2);
  const next = tasks.find(t => t.id !== task.id)!;
  expect(next.data).toMatchObject({completed: false, doDate: '2026-10-07', deadline: '2026-10-09', projectId: project.id});
  expect((await repo.list('project'))[0].data.nextActionId).toBe(next.id);
  const carried = (await repo.list('reminder')).find(r => r.id === next.data.reminderId)!;
  expect(carried.data.targetId).toBe(next.id);
  expect(new Date(carried.data.scheduledAt).getHours()).toBe(9);
  expect(new Date(carried.data.scheduledAt).getDate()).toBe(7);
  await repo.update(task.id, r => r.kind === 'task' ? {...r, data: {...r.data, completed: false}} : r);
  await complete();
  expect(await repo.list('task')).toHaveLength(2);
  await repo.remove(next.id);
  await repo.update(task.id, r => r.kind === 'task' ? {...r, data: {...r.data, completed: false}} : r);
  await complete();
  expect((await repo.db.records.get([repo.accountId, next.id]))?.deleted).toBe(true);
});
test('two offline clients generate one occurrence when their completed records merge', async () => {
  const first = setup(); const second = setup();
  const task = await first.create('task', {title: 'Monthly check', completed: false, doDate: '2026-01-31'});
  await first.setRecurrence(task.id, {frequency: 'monthly', interval: 1, anchorDate: '2026-01-31'});
  for (const record of await first.db.records.toArray()) await second.receive(record);
  for (const repo of [first, second]) await repo.update(task.id, r => r.kind === 'task' ? {...r, data: {...r.data, completed: true}} : r);
  for (const record of await second.db.records.toArray()) await first.receive(record);
  expect((await first.list('task')).filter(r => !r.data.completed)).toHaveLength(1);
  expect((await first.list('task')).find(r => !r.data.completed)?.data.doDate).toBe('2026-02-28');
});
test('invalid dates and intervals reject repeat configuration without partial records', async () => {
  const repo = setup(); const task = await repo.create('task', {title: 'Walk', completed: false});
  await expect(repo.setRecurrence(task.id, {frequency: 'daily', interval: 0, anchorDate: '2026-09-30'})).rejects.toThrow();
  await expect(repo.setRecurrence(task.id, {frequency: 'daily', interval: 1, anchorDate: '2026-02-30'})).rejects.toThrow();
  expect(await repo.list('recurrence')).toHaveLength(0);
  expect(await repo.db.outbox.count()).toBe(1);
  expect(() => parseEntity({...task, data: {...task.data, doDate: '2026-02-30'}})).toThrow();
});
test('occurrence identity is a shared SHA-256 format', async () => {
  expect(await occurrenceId('schedule-1', '2026-10-07')).toBe('repeat-' + 'e1dd1daa7bac880817b9a67a8caed605667163a147e08c6bcfc4c924f2559b8d');
});
