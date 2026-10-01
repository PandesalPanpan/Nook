import {fromMarkdown} from 'mdast-util-from-markdown';
import type {Entity} from '../../../../packages/schemas/src';

type Node = {type: string; children?: Node[]; position?: {start: {offset?: number}; end: {offset?: number}}};
export function resolveNote(reference: string, notes: Entity<'note'>[]): Entity<'note'> | undefined {
  const key = reference.trim();
  const byId = notes.find(note => note.id === key);
  if (byId) return byId;
  const matching = notes.filter(note => note.data.title.trim().toLocaleLowerCase() === key.toLocaleLowerCase());
  return matching.length === 1 ? matching[0] : undefined;
}

/** Bind resolved title references on save, editing source spans rather than reserializing Markdown. */
export function stabilizeNoteLinks(body: string, notes: Entity<'note'>[]): string {
  const edits: {start: number; end: number; text: string}[] = [];
  function visit(node: Node) {
    if (['link', 'image', 'code', 'inlineCode'].includes(node.type)) return;
    const start = node.position?.start.offset, end = node.position?.end.offset;
    if (node.type === 'text' && start !== undefined && end !== undefined) {
      const source = body.slice(start, end);
      for (const match of source.matchAll(/\[\[([^\]\n]+)\]\]/g)) {
        const slashCount = source.slice(0, match.index).match(/\\+$/)?.[0].length ?? 0;
        if (slashCount % 2) continue;
        const [reference, label] = match[1].split('|', 2);
        const target = resolveNote(reference, notes);
        if (!target || target.id === reference.trim()) continue;
        edits.push({start: start + match.index, end: start + match.index + match[0].length,
          text: `[[${target.id}|${label?.trim() || reference.trim()}]]`});
      }
    } else node.children?.forEach(visit);
  }
  visit(fromMarkdown(body) as Node);
  for (const edit of edits.sort((a,b)=>b.start-a.start)) body = body.slice(0,edit.start)+edit.text+body.slice(edit.end);
  return body;
}
