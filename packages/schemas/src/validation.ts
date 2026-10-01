import { z } from 'zod';
import type { Entity } from './index';
import { validDate } from './recurrence';

const id = z.string().min(1).max(128).regex(/^[A-Za-z0-9:_-]+$/);
const date = z.string().refine(validDate, 'Use a valid YYYY-MM-DD date');
const text = z.string().max(500_000);
const title = z.string().max(10_000);
const ids = z.array(id).max(10_000);
const base = {
  id, accountId: id, clientId: id, schemaVersion: z.literal(1),
  createdAt: z.number().int().nonnegative().safe(), updatedAt: z.number().int().nonnegative().safe(),
  deleted: z.boolean(), archived: z.boolean(),
};
const context = {projectId: id.optional(), areaId: id.optional()};
const payloads = {
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
export function parseEntity(value: unknown): Entity {
  const kind = z.object({kind: z.enum(['capture','note','task','project','area','resource','attachment','dailyNote','reminder','recurrence','noteLink','settings','profile'])}).parse(value).kind;
  const result = z.strictObject({...base, kind: z.literal(kind), data: payloads[kind]}).parse(value);
  if (result.updatedAt < result.createdAt) throw new Error('Invalid record timestamps');
  return result as Entity;
}
