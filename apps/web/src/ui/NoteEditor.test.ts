import {expect,test} from 'vitest';
import {formatSelection,outgoingNotes,resolveNote,trailingNoteLink} from './NoteEditor';
import type {Entity} from '../../../../packages/schemas/src';
const note = (id:string,title:string):Entity<'note'>=>({id,accountId:'local:test',clientId:'test',schemaVersion:1,kind:'note',createdAt:1,updatedAt:1,deleted:false,archived:false,data:{title,body:'',attachmentIds:[]}});
test('formatting surrounds the selected text and keeps surrounding content',()=>{
  expect(formatSelection('hello world',6,11,'Bold')).toEqual({body:'hello **world**',start:8,end:13});
  expect(formatSelection('hello',5,5,'Italic')).toEqual({body:'hello*italic text*',start:6,end:17});
  expect(formatSelection('one\ntwo\nthree',1,6,'Checklist').body).toBe('- [ ] one\n- [ ] two\nthree');
  expect(formatSelection('\ntext',0,0,'Heading').body).toBe('## \ntext');
});
test('note resolution prefers stable IDs and refuses ambiguous titles',()=>{
  const notes=[note('first','Garden'),note('second','Garden')];
  expect(resolveNote('first',notes)?.id).toBe('first');
  expect(resolveNote('Garden',notes)).toBeUndefined();
  expect(resolveNote(' garden ',notes.slice(0,1))?.id).toBe('first');
});
test('backlinks exclude code blocks, inline code and existing links, preserving aliases',()=>{
  const notes=[note('target','Garden')];
  expect([...outgoingNotes('[[Garden]] and [[target|Plants]]',notes)]).toEqual(['target']);
  expect(outgoingNotes('`[[Garden]]`\n\n```text\n[[Garden]]\n```\n\n    [[Garden]]\n\n[existing [[Garden]]](https://example.com)',notes).size).toBe(0);
});
test('reference pill extracts only a resolved final standalone wiki paragraph',()=>{
  const notes=[note('target','Garden')];
  expect(trailingNoteLink('## Uses\n\n[[target|Plants]]\n',notes)).toEqual({note:notes[0],label:'Plants',body:'## Uses'});
  for(const body of ['[[Missing]]','Text [[Garden]]','- [[Garden]]','> [[Garden]]','`[[Garden]]`','**[[Garden]]**','\\[\\[Garden]]','```\n[[Garden]]\n```','[[Garden]]\n\nLater']) expect(trailingNoteLink(body,notes),body).toBeUndefined();
});
