import Dexie, { type Table } from 'dexie';
import { compareVersions, syncOperationId, type Entity, type EntityKind, type Payloads, type SyncOperation } from '../../../../packages/schemas/src/index';
import { parseEntity } from '../../../../packages/schemas/src/validation';
import { searchDocument, type SearchDocument } from './search-index';
import { recurringTask, occurrenceId } from '../../../../packages/schemas/src/recurrence';
import { currentSettings, defaultSettings } from './settings';
import {stabilizeNoteLinks} from './note-links';

export class NookDatabase extends Dexie {
  records: Table<Entity, [string, string]>;
  outbox!: Table<SyncOperation, string>;
  files!: Table<LocalFile, [string, string]>;
  search!: Table<SearchDocument, [string, string]>;
  transfers!: Table<OriginalTransfer, [string, string]>;
  constructor(name = 'nook') {
    super(name);
    this.version(1).stores({ records: 'id,[accountId+kind],accountId,updatedAt', outbox: 'id,accountId,[accountId+nextAttemptAt],entityId' });
    this.version(2).stores({ entities: '[accountId+id],[accountId+kind],accountId,updatedAt' }).upgrade(async transaction => {
      await transaction.table('entities').bulkPut(await transaction.table('records').toArray());
    });
    this.version(3).stores({ records: null });
    this.version(4).stores({ files: '[accountId+id],accountId' });
    this.version(5).stores({}).upgrade(async transaction => {
      const table = transaction.table<SyncOperation, string>('outbox');
      const entries = await table.toArray();
      await table.clear();
      await table.bulkPut(entries.map(entry => ({...entry, id: syncOperationId(entry.accountId, entry.entityId)})));
    });
    this.version(6).stores({search: '[accountId+id],accountId,*terms'}).upgrade(async transaction => {
      const records = await transaction.table<Entity>('entities').toArray();
      const documents = records.map(searchDocument).filter((document): document is SearchDocument => !!document);
      await transaction.table('search').bulkPut(documents);
    });
    this.version(7).stores({files: '[accountId+id],accountId,[accountId+id+revision]', transfers: '[accountId+id],accountId'}).upgrade(async transaction => {
      await transaction.table<LocalFile>('files').toCollection().modify(file => { file.revision = crypto.randomUUID(); });
    });
    this.records = this.table('entities');
    // Every bytes replacement gets a new identity, including restore and direct writes.
    this.files.hook('creating', (_key, file) => { file.revision = crypto.randomUUID(); });
    this.files.hook('updating', () => ({revision: crypto.randomUUID()}));
  }
}
export interface OriginalTransfer { accountId: string; id: string; scope: string; version: string; revision: string; checkedAt: number; }
export class Repository {
  constructor(readonly db: NookDatabase, readonly accountId: string, readonly clientId: string) {}
  async updateSettings(change: (settings: Payloads['settings']) => Payloads['settings']): Promise<void> {
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.search, async () => {
      const previous = currentSettings(await this.list('settings'));
      if(previous) await this.update(previous.id, r=>r.kind==='settings'?{...r,data:change(r.data)}:r);
      else await this.create('settings',change(structuredClone(defaultSettings)));
    });
  }
  async list<K extends EntityKind>(kind: K): Promise<Entity<K>[]> {
    return (await this.db.records.where('[accountId+kind]').equals([this.accountId, kind]).toArray()).filter((r): r is Entity<K> => !r.deleted && r.kind === kind);
  }
  private async write(record: Entity): Promise<Entity> {
    parseEntity(record);
    if (record.kind === 'note' && !record.deleted && record.accountId === this.accountId) {
      const notes = [...(await this.list('note')).filter(note=>note.id!==record.id), record];
      record = {...record, data: {...record.data, body: stabilizeNoteLinks(record.data.body, notes)}};
      parseEntity(record);
    }
    await this.db.records.put(record);
    await this.index(record);
    await this.db.outbox.put({id: syncOperationId(record.accountId, record.id), accountId: record.accountId, entityId: record.id, record, attempts: 0, nextAttemptAt: 0});
    return record;
  }
  async index(record: Entity): Promise<void> {
    const document = searchDocument(record);
    if (document) await this.db.search.put(document);
    else await this.db.search.delete([record.accountId, record.id]);
  }
  async create<K extends EntityKind>(kind: K, data: Payloads[K], id: string = crypto.randomUUID()): Promise<Entity<K>> {
    const now = Date.now();
    let record = {id, accountId: this.accountId, clientId: this.clientId, schemaVersion: 1, kind, data, createdAt: now, updatedAt: now, deleted: false, archived: false} as Entity<K>;
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.search, async () => {
      if(await this.db.records.get([this.accountId,id])) throw new Error('Record identity already exists');
      record = await this.write(record) as Entity<K>;
    });
    return record;
  }
  async update(id: string, change: (record: Entity) => Entity): Promise<void> {
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.search, async () => {
      const previous = await this.db.records.get([this.accountId, id]);
      if (!previous || previous.accountId !== this.accountId) throw new Error('Record unavailable');
      if (previous.deleted) throw new Error('Record deleted');
      const proposed = change(structuredClone(previous));
      const result = {...proposed, id: previous.id, kind: previous.kind, schemaVersion: previous.schemaVersion, accountId: previous.accountId, createdAt: previous.createdAt, clientId: this.clientId, updatedAt: Math.max(Date.now(), previous.updatedAt + 1)} as Entity;
      await this.write(result);
      if (previous.kind === 'task' && result.kind === 'task' && !previous.data.completed && result.data.completed && !result.archived && !result.deleted && result.data.recurrenceId) {
        const recurrence = await this.db.records.get([this.accountId, result.data.recurrenceId]);
        if (recurrence?.kind === 'recurrence' && !recurrence.deleted && !recurrence.archived) {
          const data = recurringTask(result.data, recurrence.data);
          const id = await Dexie.waitFor(occurrenceId(recurrence.id, data.doDate ?? data.deadline!));
          if (!await this.db.records.get([this.accountId, id])) {
            const reminder = result.data.reminderId ? await this.db.records.get([this.accountId, result.data.reminderId]) : undefined;
            if (reminder?.kind === 'reminder' && !reminder.deleted && reminder.data.type !== 'inbox') {
              const reminderId = await Dexie.waitFor(occurrenceId(recurrence.id + '-reminder', data.doDate ?? data.deadline!));
              const scheduled = new Date(reminder.data.scheduledAt);
              const previousDate = result.data.doDate ?? result.data.deadline ?? recurrence.data.anchorDate;
              scheduled.setDate(scheduled.getDate() + (Date.parse(data.doDate ?? data.deadline!) - Date.parse(previousDate)) / 86400000);
              if (!await this.db.records.get([this.accountId, reminderId])) await this.write({...reminder, id: reminderId, archived: false, createdAt: result.updatedAt, updatedAt: result.updatedAt, clientId: this.clientId, data: {...reminder.data, targetId: id, scheduledAt: scheduled.getTime()}});
              data.reminderId = reminderId;
            }
            await this.write({...result, id, data, createdAt: result.updatedAt});
          }
          if (result.data.projectId) {
            const project = await this.db.records.get([this.accountId, result.data.projectId]);
            if (project?.kind === 'project' && !project.deleted && project.data.nextActionId === result.id) await this.update(project.id, r => r.kind === 'project' ? {...r, data: {...r.data, nextActionId: id}} : r);
          }
        }
      }
    });
  }
  async setRecurrence(taskId: string, rule?: Payloads['recurrence']): Promise<void> {
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.search, async () => {
      const task = await this.db.records.get([this.accountId, taskId]);
      if (!task || task.kind !== 'task' || task.deleted || task.archived || task.data.completed) throw new Error('Choose an active task');
      const existing = task.data.recurrenceId ? await this.db.records.get([this.accountId, task.data.recurrenceId]) : undefined;
      let recurrenceId: string | undefined;
      if (rule) {
        if (existing?.kind === 'recurrence' && !existing.deleted) { await this.update(existing.id, r => ({...r, archived: false, data: rule} as Entity)); recurrenceId = existing.id; }
        else recurrenceId = (await this.create('recurrence', rule)).id;
      }
      await this.update(taskId, r => r.kind === 'task' ? {...r, data: {...r.data, recurrenceId}} : r);
    });
  }
  async remove(id: string): Promise<void> {
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.files, this.db.search, async () => {
      await this.update(id, r => ({...r, deleted: true}));
      const record = await this.db.records.get([this.accountId, id]);
      if (record?.kind === 'attachment') await this.db.files.delete([this.accountId, id]);
      const attachments = (await this.list('attachment')).filter(r => r.data.ownerId === id);
      const reminders = (await this.list('reminder')).filter(r => r.data.targetId === id);
      for (const child of [...attachments, ...reminders]) {
        await this.update(child.id, r => ({...r, deleted: true}));
        if (child.kind === 'attachment') await this.db.files.delete([this.accountId, child.id]);
      }
    });
  }
  async process(id: string, kind: 'task' | 'note' | 'project' | 'resource'): Promise<Entity> {
    return this.db.transaction('rw', this.db.records, this.db.outbox, this.db.files, this.db.search, async () => {
      const capture = await this.db.records.get([this.accountId, id]);
      if (!capture || capture.accountId !== this.accountId || capture.kind !== 'capture' || capture.deleted) throw new Error('Capture unavailable');
      const body = capture.data.body;
      let result: Entity;
      if (kind === 'task') result = await this.create('task', {title: body, completed: false});
      else if (kind === 'note') result = await this.create('note', {title: '', body, attachmentIds: capture.data.attachmentIds});
      else if (kind === 'project') result = await this.create('project', {title: body, outcome: '', progress: 0});
      else result = await this.create('resource', {title: body, description: '', ...(capture.data.captureType === 'link' ? {url: body} : {})});
      for (const attachmentId of capture.data.attachmentIds) {
        const attachment = await this.db.records.get([this.accountId, attachmentId]);
        if (attachment?.kind === 'attachment' && !attachment.deleted) {
          await this.update(attachmentId, r => r.kind === 'attachment' ? {...r, data: {...r.data, ownerId: result.id}} : r);
        }
      }
      await this.remove(id);
      return result;
    });
  }
  async receive(remote: Entity): Promise<void> {
    parseEntity(remote);
    if (remote.accountId !== this.accountId) throw new Error('Account mismatch');
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.files, this.db.search, async () => {
      const local = await this.db.records.get([this.accountId, remote.id]);
      if (!local || compareVersions(remote, local) > 0) {
        await this.db.records.put(remote);
        await this.index(remote);
        await this.db.outbox.delete(syncOperationId(this.accountId, remote.id));
      }
      if (remote.kind === 'attachment' && remote.deleted) await this.db.files.delete([this.accountId, remote.id]);
    });
  }
  /** Move guest data once, preserving IDs. Returning to this method is idempotent. */
  async mergeIntoAccount(targetAccountId: string): Promise<Repository> {
    if (!this.accountId.startsWith('local:')) throw new Error('Only guest data can migrate');
    if (!targetAccountId || targetAccountId.startsWith('local:')) throw new Error('Cloud account required');
    await this.db.transaction('rw', this.db.records, this.db.outbox, this.db.files, this.db.search, async () => {
      const records = await this.db.records.where('accountId').equals(this.accountId).toArray();
      for (const source of records) {
        const migrated = {...source, accountId: targetAccountId};
        const existing = await this.db.records.get([targetAccountId, source.id]);
        if (!existing || compareVersions(migrated, existing) > 0) {
          await this.write(migrated);
        }
        await this.db.records.delete([this.accountId, source.id]);
        await this.db.search.delete([this.accountId, source.id]);
        await this.db.outbox.delete(syncOperationId(this.accountId, source.id));
      }
      const files = await this.db.files.where('accountId').equals(this.accountId).toArray();
      for (const file of files) {
        if (!await this.db.files.get([targetAccountId, file.id])) await this.db.files.put({...file, accountId: targetAccountId});
        await this.db.files.delete([this.accountId, file.id]);
      }
    });
    return new Repository(this.db, targetAccountId, this.clientId);
  }
  async capture(body: string, type: Payloads['capture']['captureType'] = 'text', files: File[] = []): Promise<Entity<'capture'>> {
    if (!body.trim() && !files.length) throw new Error('Add a thought or attachment');
    if (files.length > 20 || files.reduce((total,file)=>total+file.size,0) > 50 * 1024 * 1024) throw new Error('Choose at most 20 attachments, up to 50 MB in total');
    // Read file bytes before the database transaction to avoid IndexedDB auto-commit.
    const originals = await Promise.all(files.map(async file => ({filename: file.name, mimeType: file.type || 'application/octet-stream', bytes: new Uint8Array(await file.arrayBuffer())})));
    return this.db.transaction('rw', this.db.records, this.db.outbox, this.db.files, this.db.search, async () => {
      const capture = await this.create('capture', {body, captureType: type, attachmentIds: []});
      const attachmentIds: string[] = [];
      for (const file of originals) {
        const attachment = await this.create('attachment', {filename: file.filename, mimeType: file.mimeType, size: file.bytes.length, ownerId: capture.id});
        await this.db.files.put({id: attachment.id, accountId: this.accountId, bytes: file.bytes});
        attachmentIds.push(attachment.id);
      }
      if (attachmentIds.length) await this.update(capture.id, r => r.kind === 'capture' ? {...r, data: {...r.data, attachmentIds}} : r);
      return (await this.db.records.get([this.accountId, capture.id])) as Entity<'capture'>;
    });
  }
  async attach(ownerId: string, files: File[]): Promise<void> {
    if (files.length > 20 || files.reduce((total,file)=>total+file.size,0) > 50 * 1024 * 1024) throw new Error('Choose at most 20 attachments, up to 50 MB in total');
    const originals = await Promise.all(files.map(async file=>({filename:file.name,mimeType:file.type || 'application/octet-stream',bytes:new Uint8Array(await file.arrayBuffer())})));
    await this.db.transaction('rw',this.db.records,this.db.outbox,this.db.files,this.db.search,async()=>{
      const owner = await this.db.records.get([this.accountId,ownerId]);
      if(!owner || owner.deleted || !['note','dailyNote','project','area','resource','task','capture'].includes(owner.kind)) throw new Error('Record unavailable');
      const ids: string[] = [];
      for(const original of originals) {
        const attachment=await this.create('attachment',{filename:original.filename,mimeType:original.mimeType,size:original.bytes.length,ownerId});
        await this.db.files.put({id:attachment.id,accountId:this.accountId,bytes:original.bytes});ids.push(attachment.id);
      }
      if(ids.length && (owner.kind === 'note' || owner.kind === 'capture')) await this.update(ownerId,r=>{
        if(r.kind === 'note') return {...r,data:{...r.data,attachmentIds:[...r.data.attachmentIds,...ids]}};
        if(r.kind === 'capture') return {...r,data:{...r.data,attachmentIds:[...r.data.attachmentIds,...ids]}};
        return r;
      });
    });
  }
}
export interface LocalFile { id: string; accountId: string; bytes: Uint8Array; revision?: string }
