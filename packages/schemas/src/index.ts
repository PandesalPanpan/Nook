export type EntityKind = 'capture' | 'note' | 'task' | 'project' | 'area' | 'resource' | 'attachment' | 'dailyNote' | 'reminder' | 'recurrence' | 'noteLink' | 'settings' | 'profile';
export interface BaseRecord {
  id: string; accountId: string; schemaVersion: 1 | 2; createdAt: number; updatedAt: number;
  clientId: string; deleted: boolean; archived: boolean;
}
export interface ClarificationDraft { mode: 'task' | 'note' | 'split'; action: string; noteTitle: string; noteBody: string; homeId?: string; doDate?: string; deadline?: string; relatedIds: string[] }
export interface Capture { body: string; captureType: 'text' | 'task' | 'link' | 'image'; attachmentIds: string[]; originalBody?: string; processedAt?: number; processedIds?: string[]; clarificationDraft?: ClarificationDraft }
export interface Note { title: string; body: string; projectId?: string; areaId?: string; resourceId?: string; attachmentIds: string[]; relatedIds?: string[]; sourceCaptureId?: string }
export interface Task { title: string; completed: boolean; doDate?: string; deadline?: string; reminderId?: string; recurrenceId?: string; projectId?: string; areaId?: string; resourceId?: string; parentTaskId?: string; relatedIds?: string[]; sourceCaptureId?: string }
export interface Project { title: string; outcome: string; targetDate?: string; progress: number; areaId?: string; nextActionId?: string }
export interface Area { title: string; responsibility: string; standards: string }
export interface Resource { title: string; description: string; url?: string; projectId?: string; areaId?: string }
export interface Attachment { filename: string; mimeType: string; size: number; ownerId: string; storagePath?: string }
export interface DailyNote { date: string; body: string; relatedIds: string[] }
export interface Reminder { targetId?: string; scheduledAt: number; type: 'task' | 'deadline' | 'inbox'; skipIfInboxEmpty: boolean }
export interface Recurrence { frequency: 'daily' | 'weekly' | 'monthly'; interval: number; anchorDate: string }
export interface NoteLink { sourceId: string; targetId: string }
export interface AppSettings { tipsEnabled: boolean; dismissedTips: string[]; dailyNotesEnabled: boolean; inboxReviewEnabled: boolean }
export interface Profile { displayName: string }
export interface Payloads { capture: Capture; note: Note; task: Task; project: Project; area: Area; resource: Resource; attachment: Attachment; dailyNote: DailyNote; reminder: Reminder; recurrence: Recurrence; noteLink: NoteLink; settings: AppSettings; profile: Profile }
export type Entity<K extends EntityKind = EntityKind> = K extends EntityKind ? BaseRecord & {kind: K; data: Payloads[K]} : never;
export interface SyncOperation { id: string; accountId: string; entityId: string; record: Entity; attempts: number; nextAttemptAt: number }
export function syncOperationId(accountId: string, entityId: string): string {
  return `${encodeURIComponent(accountId)}:${encodeURIComponent(entityId)}`;
}
/** Deletion is permanent for a record ID. Live records use timestamp/client LWW. */
export function compareVersions(a: Entity, b: Entity): number {
  return Number(a.deleted) - Number(b.deleted) || a.updatedAt - b.updatedAt || (a.clientId < b.clientId ? -1 : a.clientId > b.clientId ? 1 : 0);
}
