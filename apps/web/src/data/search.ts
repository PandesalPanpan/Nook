import type { Entity, EntityKind } from '../../../../packages/schemas/src/index';
import { Repository } from './repository';
import { termPrefix, tokenize } from './search-index';
import {createAreaResolver,createProjectResolver} from '../../../../packages/schemas/src/context';

export interface SearchOptions { kind?: EntityKind; archived?: boolean; limit?: number }
export async function search(repository: Repository, input: string, options: SearchOptions = {}): Promise<Entity[]> {
  const {db, accountId} = repository;
  const filters: Record<string, string> = {};
  const text = input.replace(/\b(project|area|before):(?:"([^"]+)"|(\S+))/g, (_, key: string, quoted: string, unquoted: string) => { filters[key] = quoted || unquoted; return ''; });
  const words = tokenize(text);
  let candidates: string[];
  if (words.length) {
    const hits = await Promise.all(words.map(word => db.search.where('terms').startsWith(termPrefix(accountId, word)).toArray()));
    const sets = hits.map(documents => new Set(documents.map(document => document.id)));
    candidates = [...sets[0]].filter(id => sets.every(set => set.has(id)));
  } else candidates = (await db.search.where('accountId').equals(accountId).toArray()).map(document => document.id);
  const references: Record<string, Set<string>> = {};
  for (const kind of ['project','area'] as const) {
    if (!filters[kind]) continue;
    const match = filters[kind].toLocaleLowerCase('en');
    references[kind] = new Set((await repository.list(kind)).filter(record => record.id === filters[kind] || ('title' in record.data && record.data.title.toLocaleLowerCase('en') === match)).map(record => record.id));
  }
  const records = await db.records.bulkGet(candidates.map(id => [accountId, id] as [string, string]));
  const parents=references.area||references.project?await db.records.where('[accountId+kind]').anyOf([[accountId,'project'],[accountId,'task']]).toArray():[];
  const areaFor=createAreaResolver(parents,accountId);
  const projectFor=createProjectResolver(parents,accountId);
  return records.filter((record): record is Entity => {
    if (!record || record.deleted || options.kind && record.kind !== options.kind || options.archived !== undefined && record.archived !== options.archived) return false;
    for (const kind of ['project','area'] as const) {
      const related=kind==='area'?areaFor(record):projectFor(record);
      if (references[kind] && !(references[kind].has(record.id) || related && references[kind].has(related))) return false;
    }
    if (filters.before) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(filters.before)) return false;
      const data = record.data as unknown as {doDate?: string; deadline?: string; targetDate?: string; date?: string};
      const date = data.doDate || data.deadline || data.targetDate || data.date || new Date(record.createdAt).toISOString().slice(0,10);
      if (date >= filters.before) return false;
    }
    return true;
  }).sort((a,b) => b.updatedAt - a.updatedAt || a.id.localeCompare(b.id)).slice(0, options.limit ?? 100);
}
