import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { z } from 'zod';
import { compareVersions, syncOperationId, type Entity } from '../../../../packages/schemas/src/index';
import { parseEntity } from '../../../../packages/schemas/src/validation';
import { Repository } from './repository';

const MAX_BACKUP_BYTES = 250 * 1024 * 1024;
const manifestSchema = z.strictObject({format: z.literal('nook'), version: z.literal(1), exportedAt: z.number(), records: z.array(z.unknown()).max(100_000)});

export async function exportBackup(repository: Repository): Promise<Uint8Array> {
  const {db, accountId} = repository;
  const snapshot = await db.transaction('r', db.records, db.files, async () => ({
    records: await db.records.where('accountId').equals(accountId).toArray(),
    files: await db.files.where('accountId').equals(accountId).toArray(),
  }));
  const entries: Record<string, Uint8Array> = {};
  entries['nook.json'] = strToU8(JSON.stringify({format: 'nook', version: 1, exportedAt: Date.now(), records: snapshot.records}, null, 2));
  for (const record of snapshot.records) {
    if (record.deleted) continue;
    if (record.kind === 'note') entries[`notes/${record.id}.md`] = strToU8(`${record.data.title ? `# ${record.data.title}\n\n` : ''}${record.data.body}`);
    if (record.kind === 'dailyNote') entries[`daily/${record.data.date}-${record.id}.md`] = strToU8(record.data.body);
    if (record.kind === 'attachment') {
      const original = snapshot.files.find(file => file.id === record.id);
      if (!original) throw new Error(`Original attachment unavailable: ${record.data.filename}. Download it before exporting.`);
      entries[`attachments/${record.id}`] = original.bytes;
    }
  }
  return zipSync(entries, {level: 6});
}

/** Full validation happens before the transaction, so malformed backups never partially import. */
export async function restoreBackup(repository: Repository, zip: Uint8Array): Promise<number> {
  if (zip.length > MAX_BACKUP_BYTES) throw new Error('Backup exceeds 250 MB');
  let totalSize = 0; const names = new Set<string>();
  const entries = unzipSync(zip, {filter: file => {
    if (file.name.startsWith('/') || file.name.includes('\\') || file.name.split('/').includes('..') || names.has(file.name)) throw new Error('Unsafe backup paths');
    names.add(file.name); totalSize += file.originalSize;
    if (names.size > 100_001 || totalSize > MAX_BACKUP_BYTES) throw new Error('Backup exceeds import limits');
    return true;
  }});
  if (!entries['nook.json']) throw new Error('Nook manifest missing');
  const manifest = manifestSchema.parse(JSON.parse(strFromU8(entries['nook.json'])));
  const records = manifest.records.map(parseEntity);
  const ids = new Set<string>();
  for (const record of records) {
    if (ids.has(record.id)) throw new Error('Duplicate record IDs');
    ids.add(record.id);
    if (record.kind === 'attachment' && !record.deleted) {
      const bytes = entries[`attachments/${record.id}`];
      if (!bytes || bytes.length !== record.data.size) throw new Error('Missing or invalid original attachment');
    }
  }
  const {db, accountId} = repository;
  return db.transaction('rw', db.records, db.files, db.outbox, db.search, async () => {
    let imported = 0;
    for (const source of records) {
      const record = {...source, accountId} as Entity;
      const existing = await db.records.get([accountId, record.id]);
      if (!existing || compareVersions(record, existing) > 0) {
        await db.records.put(record);
        await repository.index(record);
        await db.outbox.put({id: syncOperationId(accountId, record.id), accountId, entityId: record.id, record, attempts: 0, nextAttemptAt: 0});
        imported++;
      }
      if (record.kind === 'attachment' && !record.deleted && !existing?.deleted && !await db.files.get([accountId, record.id])) {
        await db.files.put({id: record.id, accountId, bytes: entries[`attachments/${record.id}`]});
      }
    }
    return imported;
  });
}

/** Adapter entry point for future folder pickers; Markdown is preserved verbatim. */
export async function importMarkdown(repository: Repository, files: {name: string; body: string}[]): Promise<Entity<'note'>[]> {
  return repository.db.transaction('rw', repository.db.records, repository.db.outbox, repository.db.search, async () => {
    const results: Entity<'note'>[] = [];
    for (const file of files) results.push(await repository.create('note', {title: file.name.replace(/\.md$/i, ''), body: file.body, attachmentIds: []}));
    return results;
  });
}
