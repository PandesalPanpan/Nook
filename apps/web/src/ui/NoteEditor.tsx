import {useEffect, useRef, useState} from 'react';
import Markdown, {defaultUrlTransform} from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {Entity} from '../../../../packages/schemas/src';
import {fromMarkdown} from 'mdast-util-from-markdown';
import {resolveNote} from '../data/note-links';
export {resolveNote} from '../data/note-links';

type Node = {type:string;value?:string;url?:string;children?:Node[]};
export function wikiLinks(notes:Entity<'note'>[]) {
  return () => (tree:Node) => {
    function visit(parent:Node) {
      if(!parent.children || ['link','image','code','inlineCode'].includes(parent.type)) return;
      parent.children=parent.children.flatMap(child=>{
        if(child.type!=='text' || !child.value) {visit(child);return [child];}
        const result:Node[]=[];let start=0;
        for(const match of child.value.matchAll(/\[\[([^\]\n]+)\]\]/g)) {
          const [reference,label]=match[1].split('|',2);
          const note=resolveNote(reference,notes);
          if(!note) continue;
          result.push({type:'text',value:child.value.slice(start,match.index)});
          result.push({type:'link',url:`nook-note:${note.id}`,children:[{type:'text',value:label?.trim() || note.data.title || 'Untitled note'}]});
          start=match.index+match[0].length;
        }
        result.push({type:'text',value:child.value.slice(start)});return result;
      });
    }
    visit(tree);
  };
}
export function outgoingNotes(body:string,notes:Entity<'note'>[]):Set<string> {
  const tree=fromMarkdown(body) as Node;wikiLinks(notes)()(tree);
  const ids=new Set<string>();
  function visit(node:Node) {
    if(node.type==='link' && node.url?.startsWith('nook-note:') && notes.some(note=>note.id===node.url!.slice(10))) ids.add(node.url.slice(10));
    node.children?.forEach(visit);
  }
  visit(tree);return ids;
}
export type Format='Heading'|'Bold'|'Italic'|'List'|'Checklist'|'Quote'|'Code'|'Link';
export function formatSelection(body:string,start:number,end:number,format:Format):{body:string;start:number;end:number} {
  const selected=body.slice(start,end);
  const wrappers:Partial<Record<Format,[string,string,string]>>={Bold:['**','**','bold text'],Italic:['*','*','italic text'],Code:['`','`','code'],Link:['[','](https://)','link text']};
  const wrapper=wrappers[format];
  if(wrapper) {
    const value=selected || wrapper[2];
    return {body:body.slice(0,start)+wrapper[0]+value+wrapper[1]+body.slice(end),start:start+wrapper[0].length,end:start+wrapper[0].length+value.length};
  }
  const lineStart=start===0?0:body.lastIndexOf('\n',start-1)+1;
  const lineEnd=body.indexOf('\n',end)<0?body.length:body.indexOf('\n',end);
  const prefix={Heading:'## ',List:'- ',Checklist:'- [ ] ',Quote:'> '}[format as 'Heading'|'List'|'Checklist'|'Quote'];
  const value=body.slice(lineStart,lineEnd).split('\n').map(line=>prefix+line).join('\n');
  return {body:body.slice(0,lineStart)+value+body.slice(lineEnd),start:lineStart,end:lineStart+value.length};
}
export function trailingNoteLink(body:string,notes:Entity<'note'>[]):{note:Entity<'note'>;label:string;body:string}|undefined {
  // Only a final top-level plain wiki paragraph becomes a reference pill.
  const tree=fromMarkdown(body);const paragraph=tree.children.at(-1);
  if(paragraph?.type!=='paragraph' || paragraph.children.length!==1 || paragraph.children[0].type!=='text') return;
  const raw=body.trimEnd().split('\n').at(-1)?.trim();
  const match=/^\[\[([^\]\n]+)\]\]$/.exec(paragraph.children[0].value.trim());
  if(!match || raw!==match[0]) return;
  const [reference,label]=match[1].split('|',2);const note=resolveNote(reference,notes);
  if(note) return {note,label:label?.trim() || note.data.title || 'Untitled note',body:body.slice(0,paragraph.position?.start.offset).trimEnd()};
}

function NoteLinkPicker({notes,choose,close}:{notes:Entity<'note'>[];choose:(id:string)=>void;close:()=>void}) {
  const dialog=useRef<HTMLDialogElement>(null);const [query,setQuery]=useState('');
  useEffect(()=>{dialog.current?.showModal();dialog.current?.querySelector<HTMLInputElement>('input')?.focus();},[]);
  return <dialog ref={dialog} className="note-link-picker" aria-label="Link note" onCancel={close}>
    <div className="row between"><h2>Link note</h2><button type="button" aria-label="Close note picker" onClick={close}>×</button></div>
    <label>Find a note<input value={query} onChange={e=>setQuery(e.target.value)}/></label>
    {notes.filter(note=>(note.data.title || 'Untitled note').toLocaleLowerCase().includes(query.toLocaleLowerCase())).map(note=><button type="button" className="linked-record" key={note.id} onClick={()=>choose(note.id)}>{note.data.title || 'Untitled note'}</button>)}
  </dialog>;
}

export function NoteEditor({body,change,notes,open,initialPreview=false,onAttach,attachmentBusy=false}:{
  body:string;change:(value:string)=>void;notes:Entity<'note'>[];open:(note:Entity<'note'>)=>void;
  initialPreview?:boolean;onAttach?:()=>void;attachmentBusy?:boolean;
}) {
  const [preview,setPreview]=useState(initialPreview);const [picker,setPicker]=useState(false);
  const input=useRef<HTMLTextAreaElement>(null);const linkButton=useRef<HTMLButtonElement>(null);
  const selection=useRef({start:body.length,end:body.length,body});
  const trailing=preview?trailingNoteLink(body,notes):undefined;
  function rememberSelection() {selection.current={start:input.current?.selectionStart ?? body.length,end:input.current?.selectionEnd ?? body.length,body};}
  function focus(start:number,end=start) {requestAnimationFrame(()=>{input.current?.focus();input.current?.setSelectionRange(start,end);});}
  function format(command:Format) {
    const result=formatSelection(body,input.current?.selectionStart ?? body.length,input.current?.selectionEnd ?? body.length,command);
    change(result.body);setPreview(false);focus(result.start,result.end);
  }
  function insertLink(id:string) {
    const {start,end}=selection.current.body===body?selection.current:{start:body.length,end:body.length};
    const link=`[[${id}]]`;change(body.slice(0,start)+link+body.slice(end));setPreview(false);setPicker(false);focus(start+link.length);
  }
  return <div className="note-editor">
    {preview?<div className="markdown"><Markdown remarkPlugins={[remarkGfm,wikiLinks(notes)]} urlTransform={url=>url.startsWith('nook-note:')?url:defaultUrlTransform(url)} components={{a:({href,children})=>href?.startsWith('nook-note:')?<button type="button" className="note-link" onClick={()=>{const note=notes.find(n=>n.id===href.slice(10));if(note)open(note);}}>{children}</button>:<a href={href} rel="noreferrer">{children}</a>,img:({alt})=><span>{alt || 'Image attachment'}</span>}}>{trailing?.body ?? body}</Markdown>{trailing&&<button type="button" className="note-reference" onClick={()=>open(trailing.note)}>↗ {trailing.label}</button>}</div>
      :<label>Note body<textarea ref={input} className="note-body" value={body} onChange={e=>change(e.target.value)} placeholder="Start writing. Markdown and [[note links]] are welcome." onKeyDown={e=>{if((e.ctrlKey||e.metaKey)&&['b','i'].includes(e.key.toLowerCase())){e.preventDefault();format(e.key.toLowerCase()==='b'?'Bold':'Italic');}}}/></label>}
    <p className="note-link-hint">Type [[ to link another note, or use the Link note button.</p>
    <div className="note-toolbar" role="group" aria-label="Note formatting">
      {(['Bold','Italic','List','Checklist'] as Format[]).map(command=><button key={command} type="button" aria-label={command} onMouseDown={e=>e.preventDefault()} onClick={()=>format(command)}>{({Bold:'B',Italic:'I',List:'List',Checklist:'Check'} as Record<string,string>)[command]}</button>)}
      <button ref={linkButton} type="button" className="primary" onClick={()=>{rememberSelection();setPicker(true);}}>Link note</button>
      {onAttach&&<button type="button" disabled={attachmentBusy} onClick={onAttach}>{attachmentBusy?'Adding…':'Attach'}</button>}
    </div>
    <div className="row note-editor-secondary"><button type="button" onClick={()=>setPreview(!preview)}>{preview?'Edit note':'Preview'}</button><details><summary>More formatting</summary><div className="row">{(['Heading','Quote','Code','Link'] as Format[]).map(command=><button key={command} type="button" onMouseDown={e=>e.preventDefault()} onClick={()=>format(command)}>{command}</button>)}</div></details></div>
    {picker&&<NoteLinkPicker notes={notes} choose={insertLink} close={()=>{setPicker(false);if(preview)requestAnimationFrame(()=>linkButton.current?.focus());else focus(selection.current.start,selection.current.end);}}/>}
  </div>;
}
