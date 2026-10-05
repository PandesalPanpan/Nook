import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { ClarificationDraft, Entity } from '../../../../packages/schemas/src/index';
import { useRepository, localDate } from './runtime';
import { formatCaptureTime } from './captureTime';

type Props = { capture: Entity<'capture'>; captures: Entity<'capture'>[]; records: Entity[]; next: (id?: string) => void };
type DateField = 'doDate' | 'deadline';
type Draft = ClarificationDraft & {body:string};
const types: ClarificationDraft['mode'][] = ['task', 'note', 'split'];
const labels = { project: 'Project', area: 'Area', resource: 'Resource' } as const;

function firstLine(value: string) { return value.split(/\r?\n/, 1)[0]?.trim().slice(0, 10_000) || value.trim().slice(0, 10_000) || 'Photo capture'; }
function suggestedAction(value: string) {
  return value.match(/^\s*(?:i need to|i should|need to|remember to|don't forget to|todo:?|to do:)\s+(.+)$/im)?.[1]?.trim() ?? '';
}
function dateLabel(value?: string) { return value ? new Date(`${value}T12:00:00`).toLocaleDateString(undefined, {month:'short',day:'numeric',year:'numeric'}) : 'Not scheduled'; }
function recordTitle(record: Entity) {
  if (record.kind === 'dailyNote') return `Daily note · ${record.data.date}`;
  return 'title' in record.data ? record.data.title || '(Untitled)' : record.kind;
}
function initialDraft(capture: Entity<'capture'>): Draft {
  const saved = capture.data.clarificationDraft;
  if (saved) return {...saved, body:capture.data.body, relatedIds:[...saved.relatedIds]};
  const body = capture.data.body;
  return {
    mode:capture.data.captureType === 'task' ? 'task' : 'note', action:suggestedAction(body), noteTitle:firstLine(body), noteBody:body, body,
    relatedIds:[],
  };
}
function Icon({name}:{name:string}) {
  const shape: Record<string, string> = {
    task:'<rect x="3" y="4" width="18" height="17" rx="3"/><path d="m7 12 3 3 7-7"/>',
    note:'<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 12h7M9 16h7"/>',
    project:'<path d="M3 7h7l2 2h9v10H3z"/><path d="M3 7V5h7l2 2"/>',
    area:'<rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/>',
    resource:'<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22z"/><path d="M4 5.5V22M8 7h8M8 11h8"/>',
    calendar:'<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M7 3v4M17 3v4M3 10h18"/>',
    archive:'<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v11h14V9M10 13h4"/>',
    delete:'<path d="M4 7h16M10 11v6M14 11v6M6 7l1 14h10l1-14M9 7V4h6v3"/>',
    link:'<path d="M10 13a5 5 0 0 0 7.1 0l2-2A5 5 0 0 0 12 4l-1 1"/><path d="M14 11a5 5 0 0 0-7.1 0l-2 2A5 5 0 0 0 12 20l1-1"/>',
  };
  return <svg className={`clarify-icon icon-${name}`} viewBox="0 0 24 24" aria-hidden="true" focusable="false" dangerouslySetInnerHTML={{__html:shape[name]??shape.link}}/>;
}

export function HomePicker({records, selected, close, choose, repository}:{records:Entity[];selected?:string;close:()=>void;choose:(id?:string)=>void;repository:ReturnType<typeof useRepository>}) {
  const dialog = useRef<HTMLDialogElement>(null); const [query,setQuery]=useState(''); const [newKind,setNewKind]=useState<'project'|'area'|'resource'>('project'); const [newTitle,setNewTitle]=useState(''); const [error,setError]=useState(''); const [busy,setBusy]=useState(false);
  useEffect(()=>{dialog.current?.showModal();},[]);
  const homes=records.filter((r):r is Entity<'project'|'area'|'resource'>=>['project','area','resource'].includes(r.kind)&&!r.deleted&&!r.archived&&r.accountId===repository.accountId&&recordTitle(r).toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  async function createHome(event:FormEvent) {
    event.preventDefault(); if(!newTitle.trim()||busy)return;setBusy(true);setError('');
    try { const data=newKind==='project'?{title:newTitle.trim(),outcome:'',progress:0}:newKind==='area'?{title:newTitle.trim(),responsibility:'',standards:''}:{title:newTitle.trim(),description:''}; const result=await repository.create(newKind,data as never); choose(result.id); }
    catch(e){setError(e instanceof Error?e.message:'Could not create this collection');setBusy(false);}
  }
  return <dialog className="clarify-dialog home-picker" ref={dialog} aria-labelledby="home-picker-title" onCancel={close} onClose={close}>
    <div className="row between"><h2 id="home-picker-title">Add to a Project, Area, or Resource</h2><button type="button" aria-label="Close home picker" onClick={close}>×</button></div>
    <button type="button" className="picker-clear" onClick={()=>choose(undefined)}>No primary home</button>
    <label className="sr-only" htmlFor="home-search">Search collections</label><input id="home-search" autoFocus value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search Projects, Areas, and Resources"/>
    {(['project','area','resource'] as const).map(kind=><section className="home-choice-group" key={kind}><h3>{labels[kind]}s</h3>{homes.filter(home=>home.kind===kind).map(home=><button type="button" className={`home-choice ${selected===home.id?'chosen':''}`} key={home.id} aria-pressed={selected===home.id} onClick={()=>choose(home.id)}><Icon name={kind}/><span><strong>{recordTitle(home)}</strong><small>{labels[kind]}</small></span><span className="home-choice-mark">{selected===home.id?'✓':'›'}</span></button>)}{!homes.some(home=>home.kind===kind)&&<p className="muted small">No {labels[kind].toLocaleLowerCase()}s yet.</p>}</section>)}
    <details className="create-home"><summary>Create a Project, Area, or Resource</summary><form onSubmit={createHome}><label>Type<select value={newKind} onChange={e=>setNewKind(e.target.value as typeof newKind)}><option value="project">Project</option><option value="area">Area</option><option value="resource">Resource</option></select></label><label>Name<input value={newTitle} onChange={e=>setNewTitle(e.target.value)} autoComplete="off"/></label>{error&&<p role="alert" className="danger">{error}</p>}<button className="primary" disabled={!newTitle.trim()||busy}>{busy?'Creating…':`Create ${labels[newKind]}`}</button></form></details>
  </dialog>;
}

export function RelatedPicker({records, selected, close, save, selfId, accountId}:{records:Entity[];selected:string[];close:()=>void;save:(ids:string[])=>void;selfId:string;accountId:string}) {
  const dialog=useRef<HTMLDialogElement>(null);const [query,setQuery]=useState('');const [ids,setIds]=useState(selected);useEffect(()=>{dialog.current?.showModal();},[]);
  const options=records.filter(r=>r.id!==selfId&&r.accountId===accountId&&!r.deleted&&!r.archived&&['task','note','dailyNote','project','area','resource'].includes(r.kind)&&recordTitle(r).toLocaleLowerCase().includes(query.toLocaleLowerCase())).sort((a,b)=>recordTitle(a).localeCompare(recordTitle(b)));
  return <dialog className="clarify-dialog related-picker" ref={dialog} aria-labelledby="related-picker-title" onCancel={close} onClose={close}>
    <div className="row between"><h2 id="related-picker-title">Connect related items</h2><button type="button" aria-label="Close related item picker" onClick={close}>×</button></div>
    <label className="sr-only" htmlFor="related-search">Search related items</label><input id="related-search" autoFocus value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search Notes, Tasks, or collections"/>
    <div className="related-choice-list">{options.map(item=><button type="button" className="home-choice" key={item.id} aria-pressed={ids.includes(item.id)} onClick={()=>setIds(current=>current.includes(item.id)?current.filter(id=>id!==item.id):[...current,item.id])}><Icon name={item.kind}/><span><strong>{recordTitle(item)}</strong><small>{item.kind==='dailyNote'?'Daily note':item.kind[0].toUpperCase()+item.kind.slice(1)}</small></span><span className="home-choice-mark">{ids.includes(item.id)?'✓':'+'}</span></button>)}{!options.length&&<p className="muted">No matching active items.</p>}</div>
    <button className="primary full" onClick={()=>save(ids)}>Save connections</button>
  </dialog>;
}

export function DatePicker({value,title,close,choose}:{value?:string;title:string;close:()=>void;choose:(value?:string)=>void}) {
  const dialog=useRef<HTMLDialogElement>(null);const [selected,setSelected]=useState(value);const [month,setMonth]=useState(()=>{const date=value?new Date(`${value}T12:00:00`):new Date();return new Date(date.getFullYear(),date.getMonth(),1);});
  useEffect(()=>{dialog.current?.showModal();},[]);
  const today=localDate();const count=new Date(month.getFullYear(),month.getMonth()+1,0).getDate();const offset=new Date(month.getFullYear(),month.getMonth(),1).getDay();
  function preset(days:number){const date=new Date();date.setDate(date.getDate()+days);const next=localDate(date);setSelected(next);setMonth(new Date(date.getFullYear(),date.getMonth(),1));}
  return <dialog className="clarify-dialog date-picker" ref={dialog} aria-labelledby="date-picker-title" onCancel={close} onClose={close}>
    <div className="row between"><h2 id="date-picker-title">{title}</h2><button type="button" aria-label="Close date picker" onClick={close}>×</button></div><p>{title==='Schedule'?'When do you plan to work on it?':title==='Deadline'?'When must it be finished?':`Choose a date for ${title.toLocaleLowerCase()}.`}</p>
    {title==='Schedule'&&<div className="date-presets">{[['Today',0],['Tomorrow',1],['Next week',7]].map(([label,days])=><button key={label} type="button" aria-pressed={selected===localDate(new Date(new Date().setDate(new Date().getDate()+Number(days))))} onClick={()=>preset(Number(days))}>{label}</button>)}</div>}
    <div className="calendar-heading"><button type="button" aria-label="Previous month" onClick={()=>setMonth(new Date(month.getFullYear(),month.getMonth()-1,1))}>‹</button><strong>{month.toLocaleDateString(undefined,{month:'long',year:'numeric'})}</strong><button type="button" aria-label="Next month" onClick={()=>setMonth(new Date(month.getFullYear(),month.getMonth()+1,1))}>›</button></div>
    <div className="clarify-calendar" role="group" aria-label="Choose a date">{['S','M','T','W','T','F','S'].map((day,index)=><span className="calendar-weekday" key={`${day}${index}`}>{day}</span>)}{Array.from({length:offset},(_,index)=><span aria-hidden="true" key={`empty${index}`}/>)}{Array.from({length:count},(_,index)=>{const date=new Date(month.getFullYear(),month.getMonth(),index+1);const iso=localDate(date);return <button type="button" key={iso} aria-label={date.toLocaleDateString(undefined,{weekday:'long',year:'numeric',month:'long',day:'numeric'})} aria-pressed={selected===iso} className={`${selected===iso?'selected':''} ${iso===today?'today':''}`} onClick={()=>setSelected(iso)}>{index+1}</button>;})}</div>
    <p className="selected-date">{selected?new Date(`${selected}T12:00:00`).toLocaleDateString(undefined,{weekday:'long',year:'numeric',month:'long',day:'numeric'}):'No date selected'}</p>
    <div className="date-picker-actions"><button type="button" onClick={()=>choose(undefined)}>Clear date</button><button type="button" className="primary" onClick={()=>choose(selected)}>Set date</button></div>
  </dialog>;
}

function DeleteConfirm({cancel,confirm,busy}:{cancel:()=>void;confirm:()=>void;busy:boolean}) {
  const dialog=useRef<HTMLDialogElement>(null);
  useEffect(()=>{dialog.current?.showModal();dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();},[]);
  return <dialog ref={dialog} className="clarify-dialog delete-confirm" aria-labelledby="delete-thought-title" aria-describedby="delete-thought-description" onCancel={event=>{event.preventDefault();cancel();}}>
    <h2 id="delete-thought-title">Delete this thought?</h2><p id="delete-thought-description">This permanently removes the Inbox thought and its linked capture files.</p>
    <div className="date-picker-actions"><button type="button" disabled={busy} onClick={cancel}>Cancel</button><button type="button" className="danger-button" disabled={busy} onClick={confirm}>{busy?'Deleting…':'Delete thought'}</button></div>
  </dialog>;
}

export function Clarification({capture,captures,records,next}:Props) {
  const repository=useRepository();const stored=initialDraft(capture);const [draft,setDraft]=useState(stored);const [more,setMore]=useState(false);const [preview,setPreview]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [homeOpen,setHomeOpen]=useState(false);const [linksOpen,setLinksOpen]=useState(false);const [dateField,setDateField]=useState<DateField>();const [deleteOpen,setDeleteOpen]=useState(false);const saveQueue=useRef<Promise<void>>(Promise.resolve());
  const home=records.find(record=>record.id===draft.homeId&&!record.deleted&&!record.archived);const projects=records.filter((record):record is Entity<'project'>=>record.kind==='project'&&!record.deleted&&!record.archived&&record.accountId===repository.accountId);
  const matches=projects.filter(project=>draft.body.trim().length>1&&project.data.title.toLocaleLowerCase().includes(draft.body.trim().toLocaleLowerCase()));
  function persist(nextDraft:Draft, body= draft.body) {
    setDraft(nextDraft);setError('');
    saveQueue.current=saveQueue.current.catch(()=>{}).then(()=>repository.update(capture.id,current=>{
      if(current.kind!=='capture')throw new Error('Capture unavailable');
      const originalBody=current.data.originalBody??(body!==current.data.body?current.data.body:undefined);
      const clarificationDraft:ClarificationDraft={mode:nextDraft.mode,action:nextDraft.action,noteTitle:nextDraft.noteTitle,noteBody:nextDraft.noteBody,...(nextDraft.homeId?{homeId:nextDraft.homeId}:{}),...(nextDraft.doDate?{doDate:nextDraft.doDate}:{}),...(nextDraft.deadline?{deadline:nextDraft.deadline}:{}),relatedIds:[...nextDraft.relatedIds]};
      return {...current,schemaVersion:2,data:{...current.data,body,...(originalBody!==undefined?{originalBody}:{}),clarificationDraft}};
    })).catch(cause=>setError(cause instanceof Error?cause.message:'Draft could not be saved'));
  }
  function changeBody(body:string) {
    const wasDerivedTitle=draft.noteTitle===firstLine(draft.body);const wasDerivedNote=draft.noteBody===draft.body;
    persist({...draft,body,noteTitle:wasDerivedTitle?firstLine(body):draft.noteTitle,noteBody:wasDerivedNote?body:draft.noteBody},body);
  }
  function change(patch:Partial<Draft>) { persist({...draft,...patch}); }
  function chooseMode(mode:ClarificationDraft['mode']) { change({mode,...(mode==='split'?{action:draft.action||suggestedAction(draft.body)}:{})}); }
  function selectHome(id?:string) { change({homeId:id});setHomeOpen(false); }
  const index=captures.findIndex(item=>item.id===capture.id);const nextCapture=captures.slice(index+1).find(item=>item.id!==capture.id)??captures.find(item=>item.id!==capture.id);
  async function save() {
    if(busy||!draft.body.trim()&&!capture.data.attachmentIds.length||(draft.mode==='split'&&!draft.action.trim()))return;
    setBusy(true);setError('');
    try {
      await saveQueue.current;
      await repository.clarify(capture.id,{mode:draft.mode,body:draft.body,action:draft.action,noteTitle:draft.noteTitle,noteBody:draft.noteBody,homeId:draft.homeId,doDate:draft.doDate,deadline:draft.deadline,relatedIds:draft.relatedIds});
      next(nextCapture?.id);
    } catch(cause) { setError(cause instanceof Error?cause.message:'Could not save this clarification. Your thought is still in Inbox.');setBusy(false); }
  }
  async function archive() {if(busy)return;setBusy(true);try{await saveQueue.current;await repository.update(capture.id,record=>({...record,archived:true}));next(nextCapture?.id);}catch(cause){setError(cause instanceof Error?cause.message:'Could not archive this thought');setBusy(false);}}
  async function remove() {if(busy)return;setBusy(true);try{await saveQueue.current;await repository.remove(capture.id);setDeleteOpen(false);next(nextCapture?.id);}catch(cause){setError(cause instanceof Error?cause.message:'Could not delete this thought');setBusy(false);}}

  return <div className="clarification-flow">
    <section className="panel clarification-card" aria-label="Clarify capture" tabIndex={0} onKeyDown={event=>{if(event.target===event.currentTarget&&event.key.toLowerCase()==='t'){event.preventDefault();chooseMode('task');}}}>
      <div className="clarification-meta"><span className="pill orange">{capture.data.captureType==='task'?'TASK HINT':capture.data.captureType==='image'?'PHOTO':capture.data.captureType==='link'?'LINK':'THOUGHT'}</span><span className="capture-time">Captured {formatCaptureTime(capture.createdAt)}</span></div>
      <label htmlFor="clarification-thought">Your thought</label><textarea id="clarification-thought" className="thought-editor" value={draft.body} onChange={event=>changeBody(event.target.value)} placeholder="What’s on your mind?" autoFocus/>
      {capture.data.attachmentIds.length>0&&<p className="muted small">{capture.data.attachmentIds.length} original attachment{capture.data.attachmentIds.length===1?'':'s'} will stay with the Note.</p>}
      <div className="clarification-options">
        <h2 id="clarification-title">What should this become?</h2>
        <div className="clarification-types" role="group" aria-label="Choose the result type">{types.map(type=><button type="button" key={type} className={`type-choice ${draft.mode===type?`active ${type}`:''}`} aria-pressed={draft.mode===type} onClick={()=>chooseMode(type)}><Icon name={type==='split'?'link':type}/>{type==='split'?'Split':type[0].toUpperCase()+type.slice(1)}</button>)}</div>
        {draft.mode==='split'&&<label htmlFor="clarification-action" className="split-action">Task action<input id="clarification-action" value={draft.action} onChange={event=>change({action:event.target.value})} placeholder="What action should be done?"/></label>}
        <div className="home-control"><div><h3>Add to…</h3><p className="muted small">Optional. Choose one primary home.</p></div><button type="button" className="home-trigger" onClick={()=>setHomeOpen(true)}><Icon name={home?.kind??'project'}/><span>{home?recordTitle(home):'Add to…'}</span><span aria-hidden="true">›</span></button></div>
        {matches.length>0&&!draft.homeId&&<div className="project-suggestions" aria-label="Optional matching Project suggestions"><span>Suggested Projects</span>{matches.slice(0,3).map(project=><button type="button" key={project.id} onClick={()=>selectHome(project.id)}><Icon name="project"/>{project.data.title}</button>)}</div>}
        {(draft.mode==='task'||draft.mode==='split')&&<div className="schedule-section"><h3>Schedule</h3><div className="date-presets">{[['Today',0],['Tomorrow',1],['Next week',7]].map(([label,days])=>{const date=new Date();date.setDate(date.getDate()+Number(days));const iso=localDate(date);return <button type="button" key={label} aria-pressed={draft.doDate===iso} onClick={()=>change({doDate:iso})}>{label}</button>;})}<button type="button" className="calendar-button" onClick={()=>setDateField('doDate')}><Icon name="calendar"/>{draft.doDate?dateLabel(draft.doDate):'Choose date'}</button></div>{draft.doDate&&<button type="button" className="quiet-action" onClick={()=>change({doDate:undefined})}>Clear schedule</button>}</div>}
        <div className="clarification-links"><div><h3>Connected items</h3><p className="muted small">Optional links to related Notes, Tasks, or collections.</p></div><button type="button" onClick={()=>setLinksOpen(true)}><Icon name="link"/>{draft.relatedIds.length?`${draft.relatedIds.length} connected`:'Connect'}</button></div>
        {draft.relatedIds.length>0&&<div className="connected-preview">{draft.relatedIds.map(id=>{const item=records.find(record=>record.id===id);return <span className="connected-chip" key={id}><Icon name={item?.kind??'link'}/>{item?recordTitle(item):'Unavailable item'}</span>;})}</div>}
        <details className="clarification-more" open={more} onToggle={event=>setMore((event.currentTarget as HTMLDetailsElement).open)}><summary aria-label="More options">More options</summary><div className="more-content">
          {(draft.mode==='note'||draft.mode==='split')&&<><label>Note title<input value={draft.noteTitle} onChange={event=>change({noteTitle:event.target.value})}/></label><label>Note content<textarea value={draft.noteBody} onChange={event=>change({noteBody:event.target.value})}/></label></>}
          {(draft.mode==='task'||draft.mode==='split')&&<div className="schedule-section"><h3>Deadline</h3><button type="button" className="calendar-button" onClick={()=>setDateField('deadline')}><Icon name="calendar"/>{draft.deadline?dateLabel(draft.deadline):'Add an optional deadline'}</button>{draft.deadline&&<button type="button" className="quiet-action" onClick={()=>change({deadline:undefined})}>Clear deadline</button>}</div>}
          <button type="button" className="preview-trigger" aria-expanded={preview} onClick={()=>setPreview(value=>!value)}>{preview?'Hide preview':'Preview results'}</button>
          {preview&&<div className="clarification-preview"><h3>Preview</h3>{(draft.mode==='note'||draft.mode==='split')&&<article><strong>{draft.noteTitle||'Untitled Note'}</strong><p>{draft.noteBody}</p><small>Note{home?` · ${labels[home.kind as keyof typeof labels]??'Home'}: ${recordTitle(home)}`:''}</small></article>}{(draft.mode==='task'||draft.mode==='split')&&<article><strong>{draft.mode==='split'?draft.action||'Task action':draft.body}</strong><small>Task · {draft.doDate?`Schedule: ${dateLabel(draft.doDate)}`:'Unscheduled'}{draft.deadline?` · Deadline: ${dateLabel(draft.deadline)}`:''}</small></article>}</div>}
          <div className="clarification-menu"><h3>More</h3><button type="button" className="quiet-action" onClick={()=>void archive()}><Icon name="archive"/><span><strong>Archive thought</strong><small>Move it out of Inbox. Restore it from Archive any time.</small></span></button><button type="button" className="quiet-action danger" onClick={()=>setDeleteOpen(true)}><Icon name="delete"/><span><strong>Delete thought</strong><small>Delete this thought permanently after confirmation.</small></span></button></div>
        </div></details>
      </div>
      {error&&<p className="clarification-error" role="alert">{error}</p>}
      <div className="clarification-save"><span className="muted small">Saved locally as you edit</span><button type="button" className="primary" disabled={busy||!draft.body.trim()&&!capture.data.attachmentIds.length||(draft.mode==='split'&&!draft.action.trim())} onClick={()=>void save()}>{busy?'Saving…':nextCapture?'Save & next':'Save'}</button></div>
    </section>
    {homeOpen&&<HomePicker records={records} selected={draft.homeId} close={()=>setHomeOpen(false)} choose={selectHome} repository={repository}/>}
    {linksOpen&&<RelatedPicker records={records} selected={draft.relatedIds} close={()=>setLinksOpen(false)} selfId={capture.id} accountId={repository.accountId} save={ids=>{change({relatedIds:ids});setLinksOpen(false);}}/>}
    {dateField&&<DatePicker title={dateField==='doDate'?'Schedule':'Deadline'} value={draft[dateField]} close={()=>setDateField(undefined)} choose={value=>{change({[dateField]:value});setDateField(undefined);}}/>}
    {deleteOpen&&<DeleteConfirm cancel={()=>setDeleteOpen(false)} confirm={()=>void remove()} busy={busy}/>}
  </div>;
}

export function ProcessedCapture({capture,records,open}:{capture:Entity<'capture'>;records:Entity[];open:(record:Entity)=>void}) {
  const resultIds=[...new Set([...(capture.data.processedIds??[]),...records.filter(record=>(record.kind==='task'||record.kind==='note')&&record.data.sourceCaptureId===capture.id).map(record=>record.id)])];
  const results=resultIds.map(id=>records.find(record=>record.id===id&&!record.deleted));
  return <section className="panel processed-capture"><span className="pill teal">PROCESSED THOUGHT</span><p className="muted small">Captured {formatCaptureTime(capture.createdAt)} · Processed {capture.data.processedAt?new Date(capture.data.processedAt).toLocaleString():''}</p><h2>Original wording</h2><blockquote>{capture.data.originalBody??capture.data.body}</blockquote>{capture.data.body!==(capture.data.originalBody??capture.data.body)&&<><h2>Edited thought</h2><blockquote>{capture.data.body}</blockquote></>}<h2>Resulting items</h2><div className="processed-results">{results.map((record,index)=>record?<button type="button" key={record.id} className="home-choice" onClick={()=>open(record)}><Icon name={record.kind}/><span><strong>{recordTitle(record)}</strong><small>{record.kind[0].toUpperCase()+record.kind.slice(1)}</small></span><span>Open ›</span></button>:<p className="muted" key={`missing${index}`}>This result is no longer available.</p>)}</div></section>;
}
