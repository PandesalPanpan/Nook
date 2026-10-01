import type { Entity } from '../../../../packages/schemas/src/index';
export interface SearchDocument { accountId: string; id: string; terms: string[]; title: string }
export function tokenize(text: string): string[] { return [...new Set(text.normalize('NFKC').toLocaleLowerCase('en').match(/[\p{L}\p{N}]+/gu) ?? [])]; }
export function termPrefix(accountId: string, term: string): string { return `${encodeURIComponent(accountId)}:${term}`; }
export function searchDocument(record: Entity): SearchDocument | undefined {
  if (record.deleted || !['capture','note','task','project','area','resource','dailyNote'].includes(record.kind)) return undefined;
  const values = Object.entries(record.data).filter(([key, value]) => ['title','body','outcome','responsibility','standards','description','url','date'].includes(key) && typeof value === 'string').map(([, value]) => value as string);
  const data = record.data as unknown as {title?: string; body?: string; date?: string};
  return {accountId: record.accountId, id: record.id, title: data.title || data.date || data.body?.slice(0, 100) || 'Untitled', terms: tokenize(values.join(' ')).map(word => termPrefix(record.accountId, word))};
}
