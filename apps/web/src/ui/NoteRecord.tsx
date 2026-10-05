import {useMemo} from 'react';
import type {Entity} from '../../../../packages/schemas/src';
import {NoteEditor,outgoingNotes} from './NoteEditor';
import {AttachmentList} from './AttachmentList';
import {AiAssist} from './AiControls';
import {ConnectedItemsControl, PrimaryHomeControl} from './RecordControls';

export function noteContext(note:Entity<'note'>,records:Entity[]) {
  const context=([['resource',note.data.resourceId],['project',note.data.projectId],['area',note.data.areaId]] as const)
    .map(([kind,id])=>records.find(record=>record.accountId===note.accountId && !record.deleted && record.kind===kind && record.id===id)).find(Boolean);
  const label=context && 'title' in context.data?context.data.title:'';
  const kind=context?context.kind[0].toUpperCase()+context.kind.slice(1):'Note';
  return {label,description:label?`${kind} · ${label}`:'Saved locally'};
}

export function NoteRecord({record,draft,records,field,open,saving,save,archive,remove}:{
  record:Entity<'note'>;draft:Entity<'note'>;records:Entity[];field:(key:string,value:unknown)=>void;
  open:(record:Entity)=>void;saving:boolean;save:()=>void;archive:()=>void;remove:()=>void;
}) {
  const notes=useMemo(()=>records.filter((item):item is Entity<'note'>=>item.kind==='note' && item.accountId===record.accountId && !item.deleted),[records,record.accountId]);
  const backlinks=useMemo(()=>records.filter((item):item is Entity<'note'>|Entity<'dailyNote'>=>item.accountId===record.accountId && !item.deleted &&
    (item.kind==='note' || item.kind==='dailyNote') && item.id!==record.id && outgoingNotes(item.data.body,notes).has(record.id))
    .sort((a,b)=>a.createdAt-b.createdAt || a.id.localeCompare(b.id)),[records,notes,record.accountId,record.id]);
  const context=noteContext(draft,records);
  return <div className="record-grid note-record-grid">
    <section className="panel note-record-main" aria-label="Note content">
      <form onSubmit={e=>{e.preventDefault();save();}}>
        <label className="note-title"><span className="sr-only">Title</span><input value={draft.data.title} placeholder="Untitled note" onChange={e=>field('title',e.target.value)}/></label>
        {context.label&&<span className="pill orange note-context">{context.label}</span>}
        <hr/>
        <AttachmentList ownerId={record.id}>{({attach,busy})=><NoteEditor body={draft.data.body} change={value=>field('body',value)} notes={notes.filter(note=>note.id!==record.id)} open={open} initialPreview={!!record.data.body.trim()} onAttach={attach} attachmentBusy={busy}/>}</AttachmentList>
        <div className="row form-actions"><button className="primary" disabled={saving}>{saving?'Saving…':'Save'}</button><button type="button" onClick={archive}>{record.archived?'Restore':'Archive'}</button><button type="button" className="danger" onClick={remove}>Delete</button></div>
      </form>
      <AiAssist record={record}/>
    </section>
    <aside className="record-side note-record-side">
      <PrimaryHomeControl record={draft} records={records} change={values=>Object.entries(values).forEach(([key,value])=>field(key,value))}/>
      <ConnectedItemsControl record={record} data={draft.data} records={records} open={open} change={ids=>field('relatedIds',ids)}/>
      <section className="panel note-backlinks" aria-labelledby="note-backlinks-heading"><h3 id="note-backlinks-heading">Backlinks</h3><p>{backlinks.length} {backlinks.length===1?'note references':'notes reference'} this</p>
        {backlinks.map(note=><button key={note.id} className="linked-record" onClick={()=>open(note)}><strong>{note.kind==='dailyNote'?`Daily note · ${note.data.date}`:note.data.title || 'Untitled note'}</strong><span>Linked note</span></button>)}
      </section>
      <section className="panel note-details"><h3>Details</h3><dl><dt>Created</dt><dd>{new Date(record.createdAt).toLocaleString()}</dd><dt>Updated</dt><dd>{new Date(record.updatedAt).toLocaleString()}</dd><dt>Location</dt><dd>{context.description==='Saved locally'?'Notes':context.description}</dd><dt>Sync</dt><dd>Saved locally</dd></dl></section>
    </aside>
  </div>;
}
