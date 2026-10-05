import { z } from 'zod';
import type { Entity } from './index';
import { validDate } from './recurrence';

const id = z.string().min(1).max(128).regex(/^[A-Za-z0-9:_-]+$/);
const date = z.string().refine(validDate, 'Use a valid YYYY-MM-DD date');
const text = z.string().max(500_000);
const title = z.string().max(10_000);
const ids = z.array(id).max(10_000);
const base = {
  id, accountId: id, clientId: id, schemaVersion: z.union([z.literal(1), z.literal(2)]),
  createdAt: z.number().int().nonnegative().safe(), updatedAt: z.number().int().nonnegative().safe(),
  deleted: z.boolean(), archived: z.boolean(),
};
const context = {projectId: id.optional(), areaId: id.optional()};
const legacyPayloads = {
  capture: z.strictObject({body: text, captureType: z.enum(['text','task','link','image']), attachmentIds: ids}),
  note: z.strictObject({title, body: text, ...context, resourceId: id.optional(), attachmentIds: ids}),
  task: z.strictObject({title, completed: z.boolean(), doDate: date.optional(), deadline: date.optional(), reminderId: id.optional(), recurrenceId: id.optional(), ...context, parentTaskId: id.optional()}),
  project: z.strictObject({title, outcome: text, targetDate: date.optional(), progress: z.number().min(0).max(100), areaId: id.optional(), nextActionId: id.optional()}),
  area: z.strictObject({title, responsibility: text, standards: text}),
  resource: z.strictObject({title, description: text, url: z.string().max(10_000).optional(), ...context}),
  attachment: z.strictObject({filename: title, mimeType: z.string().max(256), size: z.number().int().nonnegative().safe(), ownerId: id, storagePath: z.string().max(1024).optional()}),
  dailyNote: z.strictObject({date, body: text, relatedIds: ids}),
  reminder: z.strictObject({targetId: id.optional(), scheduledAt: z.number().int().nonnegative().safe(), type: z.enum(['task','deadline','inbox']), skipIfInboxEmpty: z.boolean()}),
  recurrence: z.strictObject({frequency: z.enum(['daily','weekly','monthly']), interval: z.number().int().min(1).max(1000), anchorDate: date}),
  noteLink: z.strictObject({sourceId: id, targetId: id}),
  settings: z.strictObject({tipsEnabled: z.boolean(), dismissedTips: z.array(z.string().max(256)).max(1000), dailyNotesEnabled: z.boolean(), inboxReviewEnabled: z.boolean()}),
  profile: z.strictObject({displayName: z.string().max(256)}),
};
const v2Payloads = {
  ...legacyPayloads,
  capture: z.strictObject({body: text, captureType: z.enum(['text','task','link','image']), attachmentIds: ids, originalBody: text.optional(), processedAt: z.number().int().nonnegative().safe().optional(), processedIds: ids.optional(), clarificationDraft: z.strictObject({mode:z.enum(['task','note','split']),action:text,noteTitle:title,noteBody:text,homeId:id.optional(),doDate:date.optional(),deadline:date.optional(),relatedIds:ids}).optional()}),
  note: z.strictObject({title, body: text, ...context, resourceId: id.optional(), attachmentIds: ids, relatedIds: ids.optional(), sourceCaptureId: id.optional()}),
  task: z.strictObject({title, completed: z.boolean(), doDate: date.optional(), deadline: date.optional(), reminderId: id.optional(), recurrenceId: id.optional(), ...context, resourceId: id.optional(), parentTaskId: id.optional(), relatedIds: ids.optional(), sourceCaptureId: id.optional()}),
};
export function parseEntity(value: unknown): Entity {
  const header = z.object({schemaVersion: z.union([z.literal(1), z.literal(2)]), kind: z.enum(['capture','note','task','project','area','resource','attachment','dailyNote','reminder','recurrence','noteLink','settings','profile'])}).parse(value);
  const kind = header.kind;
  const result = z.strictObject({...base, kind: z.literal(kind), data: (header.schemaVersion === 1 ? legacyPayloads : v2Payloads)[kind]}).parse(value);
  if (result.updatedAt < result.createdAt) throw new Error('Invalid record timestamps');
  return result as Entity;
}
