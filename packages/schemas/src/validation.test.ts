import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { parseEntity } from './validation';
test('shared wire fixture preserves independent task dates and relationships', () => {
  const record = parseEntity(JSON.parse(readFileSync('packages/schemas/fixtures/task.json', 'utf8')));
  expect(record.kind).toBe('task');
  expect(record.data).toMatchObject({doDate: '2026-09-30', deadline: '2026-10-02', projectId: 'shared-project', parentTaskId: 'parent-task'});
});
test('settings cannot contain provider credentials', () => {
  const record = JSON.parse(readFileSync('packages/schemas/fixtures/task.json', 'utf8'));
  expect(() => parseEntity({...record, kind: 'settings', data: {tipsEnabled: true, dismissedTips: [], dailyNotesEnabled: false, inboxReviewEnabled: false, apiKey: 'local-only'}})).toThrow();
});

test('V1 stays strict while V2 adds clarification history, Resource homes, and explicit links', () => {
  const base = JSON.parse(readFileSync('packages/schemas/fixtures/task.json', 'utf8'));
  const task = {...base, schemaVersion:2, data:{...base.data, resourceId:'resource-1', relatedIds:['note-1'], sourceCaptureId:'capture-1'}};
  expect(parseEntity(task)).toMatchObject({schemaVersion:2,data:{resourceId:'resource-1',relatedIds:['note-1'],sourceCaptureId:'capture-1'}});
  expect(() => parseEntity({...task,schemaVersion:1})).toThrow();
  const capture = {...base, kind:'capture', schemaVersion:2, data:{body:'Edited thought',captureType:'text',attachmentIds:[],originalBody:'Original wording',processedAt:100,processedIds:['note-1','task-1'],clarificationDraft:{mode:'split',action:'Replace the bulb',noteTitle:'Lighting',noteBody:'Warm light',homeId:'resource-1',doDate:'2026-10-06',relatedIds:['note-1']}}};
  expect(parseEntity(capture)).toMatchObject({kind:'capture',data:{originalBody:'Original wording',processedIds:['note-1','task-1'],clarificationDraft:{mode:'split'}}});
  expect(() => parseEntity({...capture,schemaVersion:1})).toThrow();
});


test('wire dates share Android calendar bounds across all five date fields', () => {
  const base = JSON.parse(readFileSync('packages/schemas/fixtures/task.json', 'utf8'));
  const records = (date: string) => [
    {...base, kind: 'task', data: {title: 'Do', completed: false, doDate: date}},
    {...base, kind: 'task', data: {title: 'Due', completed: false, deadline: date}},
    {...base, kind: 'project', data: {title: 'Outcome', outcome: '', progress: 0, targetDate: date}},
    {...base, kind: 'dailyNote', data: {date, body: '', relatedIds: []}},
    {...base, kind: 'recurrence', data: {frequency: 'monthly', interval: 1, anchorDate: date}},
  ];
  for (const date of ['0000-01-01', '0099-12-31', '2024-02-29', '2026-09-30', '9999-12-31']) {
    for (const record of records(date)) expect(parseEntity(record)).toEqual(record);
  }
  for (const date of ['', '2026-02-29', '2024-02-30', '2026-13-01', '2026-00-01', '2026-01-00',
    '2026-1-01', '2026-01-1', '+2026-01-01', '+10000-01-01', '-0001-01-01',
    '2026-09-30T00:00:00Z', ' 2026-09-30']) {
    for (const record of records(date)) expect(() => parseEntity(record)).toThrow();
  }
});
