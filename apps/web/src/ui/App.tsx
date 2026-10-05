import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { NoteEditor, outgoingNotes } from './NoteEditor';
import {AttachmentList} from './AttachmentList';
import {NoteRecord,noteContext} from './NoteRecord';
import {ProjectRecord,projectContext} from './ProjectRecord';

import type { Entity, EntityKind, Payloads } from '../../../../packages/schemas/src/index';
import { useRepository, localDate, displayDate } from './runtime';
import { search } from '../data/search';
import {upcomingTasks} from '../data/today';
import {createAreaResolver,createProjectResolver} from '../../../../packages/schemas/src/context';
import { exportBackup, importMarkdown, restoreBackup } from '../data/backup';
import { TaskRecurrence } from './TaskRecurrence';
import { dailyNote } from '../data/daily-notes';
import { WeeklyReview } from './WeeklyReview';
import { AccountControls } from './AccountControls';
import { AiSettings, AiAssist } from './AiControls';
import {ParaTip,ParaGuide,ParaPreferences} from './ParaLearning';
import { Clarification, DatePicker, ProcessedCapture } from './Clarification';
import { formatCaptureTime } from './captureTime';
import {ConnectedItemsControl, DeadlineControl, OptionalDateControl, PrimaryHomeControl, ScheduleControl} from './RecordControls';

type Page = 'today' | 'inbox' | 'projects' | 'areas' | 'resources' | 'calendar' | 'search' | 'archive' | 'history' | 'settings' | 'review';
type Route = {page: Page; id?: string};
const navigation: [Page,string][] = [['today','Today'],['inbox','Inbox'],['projects','Projects'],['areas','Areas'],['resources','Resources'],['calendar','Calendar'],['search','Search'],['archive','Archive'],['settings','Settings']];
const labels = {text:'Thought', task:'Task', link:'Link', image:'Photo'};
function title(record: Entity): string { if(record.kind === 'dailyNote') return `Daily note · ${record.data.date}`; return 'title' in record.data && record.data.title || 'body' in record.data && record.data.body.slice(0,100) || record.kind; }
function Marker({node = '3-154', name = 'imgEllipse', className = ''}: {node?: string; name?: string; className?: string}) { return <img alt="" src={`/figma/${node}-${name}.svg`} className={`marker ${className}`} />; }
function Pill({children,tone = ''}: {children: ReactNode; tone?: string}) { return <span className={`pill ${tone}`}>{children}</span>; }

export function App() { const repository=useRepository();
  const [route,setRoute] = useState<Route>({page:'today'});
  const [started,setStarted] = useState(localStorage.getItem('nook-onboarded') === 'yes');
  const [learning,setLearning]=useState(false);
  const [captureOpen,setCaptureOpen] = useState(false);
  const [message,setMessage] = useState('');
  const [lastCapture,setLastCapture] = useState<string>();
  const records = useLiveQuery(() => repository.db.records.where('accountId').equals(repository.accountId).filter(r=>!r.deleted).toArray(), [], []);
  const active = records.filter(r=>!r.archived);
  const captureRecords = records.filter(record=>record.kind==='capture') as Entity<'capture'>[];
  const captures = captureRecords.filter(record=>!record.archived&&record.data.processedAt===undefined).sort((a,b)=>b.createdAt-a.createdAt);
  const processedCaptures = captureRecords.filter(record=>!record.deleted&&record.data.processedAt!==undefined).sort((a,b)=>(b.data.processedAt??0)-(a.data.processedAt??0));
  const projects = active.filter((r): r is Entity<'project'>=>r.kind==='project');
  const selected = records.find(r=>r.id===route.id);
  const today = localDate();
  const tasks = active.filter((r): r is Entity<'task'>=>r.kind==='task');
  const worthDoing = tasks.filter(r=>!r.data.completed && (r.data.doDate && r.data.doDate<=today || r.data.deadline && r.data.deadline<=today || projects.some(p=>p.data.nextActionId===r.id)));
  const upcoming = [...upcomingTasks(tasks,today).map(r=>({id:r.id,title:r.data.title,date:r.data.deadline!})),...projects.filter(r=>r.data.targetDate && r.data.targetDate>=today).map(r=>({id:r.id,title:r.data.title,date:r.data.targetDate!}))].sort((a,b)=>a.date.localeCompare(b.date));
  async function act(action: () => Promise<unknown>, success?: string) { try { await action(); if(success)setMessage(success); } catch(error) { setMessage(error instanceof Error ? error.message : 'Could not save. Please try again.'); } }
  function open(record: Entity) { setRoute({page: record.kind==='project' || record.kind==='task' ? 'projects' : record.kind==='capture' ? record.data.processedAt!==undefined?'history':record.archived?'archive':'inbox' : 'resources', id:record.id}); }
  async function process(id: string, destination: 'task'|'note'|'project'|'resource'|'archive') {
    await act(async()=> {
      if(destination==='archive') await repository.update(id,r=>({...r,archived:true}));
      else { const record = await repository.process(id,destination); open(record); }
      setLastCapture(undefined);
    }, 'Organized locally');
  }
  useEffect(()=> { const listener=(event: KeyboardEvent)=> {
    if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='k'){event.preventDefault();setRoute({page:'search'});}
    if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='j'){event.preventDefault();setCaptureOpen(true);}
  }; window.addEventListener('keydown',listener);return()=>window.removeEventListener('keydown',listener); },[]);
  if(!started) return <main className="welcome"><div className="brand"><img className="marker brand-mascot" src="/branding/nook-mascot-icon.png" alt=""/><strong>nook</strong></div><img className="welcome-mascot" src="/mascot/nook-mascot.png" alt="Nook's dormouse mascot"/><h1>Everything on your mind has somewhere to belong.</h1><p>Capture first. Nook helps you clarify, connect and act later—without slowing you down.</p><section className="panel feature"><Marker node="2-94" name="imgEllipse1"/><div><strong>Capture in seconds</strong><p>Text · tasks · links · screenshots</p></div></section><section className="panel feature"><Marker node="2-94" name="imgEllipse2"/><div><strong>Organize when ready</strong><p>Projects · Areas · Resources</p></div></section><button className="primary full" onClick={()=>{localStorage.setItem('nook-onboarded','yes');setStarted(true);}}>Use Nook without an account</button><p className="muted small center">Your data stays usable offline. You can enable sync later.</p><button onClick={()=>setLearning(true)}>How Nook organizes</button>{learning&&<ParaGuide close={()=>setLearning(false)}/>}</main>;
  return <div className="app-shell">
    <a className="skip" href="#main">Skip to content</a>
    <aside className="sidebar"><div className="brand"><img className="marker brand-mascot" src="/branding/nook-mascot-icon.png" alt=""/><strong>nook</strong></div><nav aria-label="Main navigation">{navigation.map(([page,label],index)=><button key={page} className={`${route.page===page?'active':''} ${index===6?'nav-divider':''}`} aria-current={route.page===page?'page':undefined} onClick={()=>setRoute({page})}>{index<6&&<Marker node={route.page==='inbox'?'3-235':route.page==='projects'?'3-322':route.page==='resources'?'3-402':'3-154'} name={route.page===page ? (page==='today'?'imgEllipse1':page==='inbox'?'imgEllipse2':page==='projects'?'imgEllipse2':page==='resources'?'imgEllipse2':'imgEllipse1') : (route.page==='today'?'imgEllipse2':'imgEllipse1')}/>}<span>{label}</span></button>)}</nav><div className="local-status"><Marker node={selected?.kind==='note'?'3-402':selected?.kind==='project'?'3-322':'3-154'} name="imgEllipse3"/><div><strong>On this device</strong><span>{repository.accountId.startsWith('local:')?'Local-only · saved locally':'Sync enabled · saved locally'}</span></div></div></aside>
    <main id="main" className="workspace"><header className="page-header"><div className="page-heading"><div><h1>{selected ? title(selected) : route.page==='today'?'Good morning.':route.page==='review'?'Weekly review':route.page==='history'?'Processed history':navigation.find(([page])=>page===route.page)?.[1]}</h1><p>{selected ? selected.kind==='note' ? noteContext(selected,records).description : selected.kind==='project' ? projectContext(selected,records) : 'Saved locally' : route.page==='today'?new Date().toLocaleDateString(undefined,{weekday:'long',month:'long',day:'numeric'}):route.page==='inbox'?`${captures.length} unprocessed captures`:route.page==='projects'?'Outcomes with a finish line.':route.page==='areas'?'Responsibilities you keep caring for.':route.page==='resources'?'Things worth keeping.':route.page==='history'?'Processed thoughts and the items they created.':''}</p></div>{route.page==='today'&&!selected&&<img className="page-mascot" src="/branding/nook-mascot-icon.png" alt="Nook's dormouse mascot"/>}</div><button className="capture-trigger" aria-label="Quick capture" onClick={()=>setCaptureOpen(true)}><Marker node={selected?.kind==='note'?'3-402':selected?.kind==='project'?'3-322':'3-154'} name="imgEllipse4"/><span>Quick capture</span><span className="capture-plus">+</span></button></header>
      {selected?.kind==='capture'&&selected.data.processedAt!==undefined ? <ProcessedCapture capture={selected} records={records} open={open}/> : selected?.kind==='capture'&&selected.archived ? <ArchivedThought capture={selected} restore={()=>act(()=>repository.update(selected.id,r=>({...r,archived:false})),'Restored from Archive')}/> : selected&&selected.kind!=='capture' ? <RecordEditor key={selected.id} record={selected} records={records} open={open} act={act}/> : route.page==='today' ? <><div className="today-grid"><section className="panel today-actions"><Pill tone="yellow">TODAY</Pill><h2>Things worth doing.</h2>{worthDoing.length ? worthDoing.slice(0,3).map(task=><TaskRow key={task.id} task={task} open={()=>open(task)} toggle={()=>act(()=>repository.update(task.id,r=>r.kind==='task'?{...r,data:{...r.data,completed:!r.data.completed}}:r))}/>) : <p>No scheduled actions. Capture a thought or choose a task’s do date.</p>}</section><section className="panel inbox-summary"><div className="row between"><h3>Inbox</h3><Pill tone="orange">{captures.length}</Pill></div><p>{captures.length?'A few things are waiting.':'A little room to think.'}</p><button className="primary" onClick={()=>setRoute({page:'inbox'})}>Process now</button><p className="muted small review-note">When you’re ready, one thought at a time.</p></section></div><h3 className="section-heading">Coming up</h3><div className="upcoming">{upcoming.slice(0,3).map((item,index)=><button key={item.id} className="panel deadline" onClick={()=>open(records.find(r=>r.id===item.id)!)}><strong className={index===0?'danger':index===1?'orange-text':'teal-text'}>{displayDate(item.date)}</strong><b>{item.title}</b><span>Open</span></button>)}{!upcoming.length&&<p className="muted">Your upcoming deadlines will appear here.</p>}</div><section className="panel elevated weekly"><Pill tone="teal">WEEKLY RHYTHM</Pill><h2>Your system is quiet enough to use.</h2><p>{projects.length} active projects · {captures.length} thoughts to clarify · {upcoming.length} upcoming deadlines</p><button onClick={()=>setRoute({page:'review'})}>Open weekly review</button></section></> : route.page==='inbox' ? <Inbox captures={captures} records={records} selectedId={route.id} select={id=>setRoute({page:'inbox',id})} next={id=>{setRoute(id?{page:'inbox',id}:{page:'inbox'});setMessage('Saved locally');}} history={()=>setRoute({page:'history'})}/> : route.page==='history' ? <HistoryList captures={processedCaptures} open={open}/> : route.page==='search' ? <SearchView open={open}/> : route.page==='settings' ? <Settings act={act}/> : route.page==='review' ? <WeeklyReview captures={captures} projects={projects} areas={active.filter(r=>r.kind==='area')} open={open} inbox={()=>setRoute({page:'inbox'})} act={act}/> : route.page==='calendar' ? <Calendar tasks={tasks} records={active} open={open} act={act}/> : <Collection page={route.page} records={route.page==='archive'?records.filter(r=>r.archived):active} open={open} act={act}/>}
      {(['projects','areas','resources','archive'] as string[]).includes(route.page)&&<ParaTip topic={route.page==='projects'?'project':route.page==='areas'?'area':route.page==='resources'?'resource':'archive'}/>}
    </main>
    {captureOpen&&<CaptureDialog close={()=>setCaptureOpen(false)} saved={id=>{setLastCapture(id);setMessage('Saved to Inbox');setCaptureOpen(false);}}/>}
    {message&&<div className="toast" role="status"><span>{message}</span>{lastCapture&&captures.some(r=>r.id===lastCapture)&&<><button onClick={()=>void process(lastCapture,'task')}>Turn into task</button><button onClick={()=>{setRoute({page:'inbox',id:lastCapture});setMessage('');}}>Organize</button></>}<button aria-label="Dismiss message" onClick={()=>setMessage('')}>×</button></div>}
  </div>;
}

function TaskRow({task,open,toggle,unscheduledLabel='Next action'}: {task:Entity<'task'>;open:()=>void;toggle:()=>void;unscheduledLabel?:string}) {
  return <div className="task-row"><input aria-label={`Complete ${task.data.title}`} type="checkbox" checked={task.data.completed} onChange={toggle}/><button className="task-label" onClick={open}><strong>{task.data.title}</strong><span>{task.data.completed?'Done':`${task.data.doDate?`Do ${displayDate(task.data.doDate)}`:unscheduledLabel}${task.data.deadline?` · deadline ${displayDate(task.data.deadline)}`:''}`}</span></button></div>;
}
function CaptureDialog({close,saved}: {close:()=>void;saved:(id:string)=>void}) { const repository=useRepository();
  const dialog = useRef<HTMLDialogElement>(null); const photo = useRef<HTMLInputElement>(null);
  const [body,setBody]=useState('');const [type,setType]=useState<Payloads['capture']['captureType']>('text');const [files,setFiles]=useState<File[]>([]);const [error,setError]=useState('');const [saving,setSaving]=useState(false);
  useEffect(()=>{dialog.current?.showModal(); dialog.current?.querySelector<HTMLTextAreaElement>("textarea")?.focus();},[]);
  async function save() { if(saving)return;setSaving(true);try{const record=await repository.capture(body,type,files);saved(record.id);}catch(e){setError(e instanceof Error?e.message:'Could not save');setSaving(false);} }
  return <dialog ref={dialog} className="capture-dialog" onCancel={close}><form onSubmit={e=>{e.preventDefault();void save();}}><div className="row between"><h2>Quick capture</h2><button type="button" aria-label="Close quick capture" onClick={close}>×</button></div><label className="sr-only" htmlFor="capture-body">Thought</label><textarea autoFocus id="capture-body" value={body} onChange={e=>setBody(e.target.value)} placeholder="What’s on your mind?" onKeyDown={e=>{if((e.ctrlKey||e.metaKey)&&e.key==='Enter'){e.preventDefault();void save();}}}/><p className="muted small">Saved to Inbox by default</p><div className="row capture-options"><button type="button" aria-pressed={type==='task'} onClick={()=>setType(type==='task'?'text':'task')}>Task</button><button type="button" onClick={()=>photo.current?.click()}>Photo</button><button type="button" aria-pressed={type==='link'} onClick={()=>setType(type==='link'?'text':'link')}>Link</button></div><input ref={photo} className="sr-only" aria-label="Attach photo" type="file" multiple accept="image/*" onChange={e=>{setFiles(Array.from(e.target.files??[]));setType('image');}}/>{files.map(file=><p className="small" key={file.name}>{file.name}</p>)}{error&&<p role="alert" className="danger">{error}</p>}<button className="primary full save-capture" disabled={saving || !body.trim()&&!files.length}>{saving?'Saving…':'Save'}</button></form></dialog>;
}
function Inbox({captures,records,selectedId,select,next,history}:{captures:Entity<'capture'>[];records:Entity[];selectedId?:string;select:(id:string)=>void;next:(id?:string)=>void;history:()=>void}) {
  const [filter,setFilter]=useState('');const selected=captures.find(record=>record.id===selectedId)??captures[0];
  const visible=captures.filter(record=>record.data.body.toLocaleLowerCase().includes(filter.toLocaleLowerCase()));
  const processedCount=records.filter((record):record is Entity<'capture'>=>record.kind==='capture'&&record.data.processedAt!==undefined&&!record.deleted).length;
  return <div className="inbox-grid"><section className="panel capture-list"><div className="row between inbox-list-heading"><h2>Inbox</h2><button type="button" onClick={history}>History {processedCount?`(${processedCount})`:''}</button></div><label className="sr-only" htmlFor="inbox-search">Search Inbox</label><input id="inbox-search" placeholder="Search Inbox" value={filter} onChange={event=>setFilter(event.target.value)}/>{visible.map((capture,index)=><button key={capture.id} className={`capture-item ${capture.id===selected?.id?'selected':''}`} aria-current={capture.id===selected?.id?'true':undefined} onClick={()=>select(capture.id)}><Marker node="3-235" name={capture.data.captureType==='task'?'imgEllipse6':capture.data.captureType==='image'?'imgEllipse7':capture.data.captureType==='link'?'imgEllipse8':index%2?'imgEllipse9':'imgEllipse5'}/><span><small>{labels[capture.data.captureType].toUpperCase()}</small><strong>{capture.data.body||'Photo capture'}</strong><small>{formatCaptureTime(capture.createdAt)}</small></span></button>)}{captures.length>0&&!visible.length&&<p className="empty">No Inbox thoughts match that search.</p>}{!captures.length&&<p className="empty">Inbox clear. Capture whenever something comes to mind.</p>}</section>{selected?<Clarification key={selected.id} capture={selected} captures={captures} records={records} next={next}/>:<section className="panel clarification-empty"><h2>A little room to think.</h2><p>Your Inbox is clear. New captures will wait here until you’re ready.</p><button type="button" onClick={history}>Open processed history</button></section>}</div>;
}
function HistoryList({captures,open}:{captures:Entity<'capture'>[];open:(record:Entity)=>void}) {return <section className="panel history-list"><h2>Processed thoughts</h2><p>Original wording and links to the items created during clarification.</p>{captures.map(capture=><button type="button" className="capture-item" key={capture.id} onClick={()=>open(capture)}><Marker node="3-235" name="imgEllipse5"/><span><small>PROCESSED THOUGHT</small><strong>{(capture.data.originalBody??capture.data.body)||'Photo capture'}</strong><small>{capture.data.processedAt?new Date(capture.data.processedAt).toLocaleString(undefined,{month:'short',day:'numeric',year:'numeric',hour:'numeric',minute:'2-digit'}):''}</small></span></button>)}{!captures.length&&<p className="empty">Processed thoughts will be kept here.</p>}</section>;}
function ArchivedThought({capture,restore}:{capture:Entity<'capture'>;restore:()=>void}) {return <section className="panel archived-thought"><span className="pill">ARCHIVED THOUGHT</span><p className="muted small">Captured {formatCaptureTime(capture.createdAt)}</p><blockquote>{capture.data.body||'Photo capture'}</blockquote><button className="primary" onClick={restore}>Restore to Inbox</button></section>;}
type Action = (action:()=>Promise<unknown>, success?:string)=>Promise<void>;
function Collection({page,records,open,act}: {page:Page;records:Entity[];open:(r:Entity)=>void;act:Action}) { const repository=useRepository();
  const kind:EntityKind = page==='projects'?'project':page==='areas'?'area':'resource';
  const collection=page==='archive'?records:records.filter(r=>r.kind===kind || page==='resources'&&r.kind==='note');
  const [name,setName]=useState('');
  async function create() { await act(async()=>{const data=kind==='project'?{title:name,outcome:'',progress:0}:kind==='area'?{title:name,responsibility:'',standards:''}:{title:name,description:''};const record=await repository.create(kind,data as Payloads[typeof kind]);setName('');open(record);},'Created locally'); }
  return <><form className="create-row" onSubmit={e=>{e.preventDefault();void create();}}>{page!=='archive'&&<><label className="sr-only" htmlFor="new-collection">New {kind} title</label><input id="new-collection" placeholder={`New ${kind}`} value={name} onChange={e=>setName(e.target.value)}/><button className="primary" disabled={!name.trim()}>+ Add {kind}</button></>}</form><div className="collection-grid">{collection.map(record=><section key={record.id} className="panel collection-card"><button className="card-open" onClick={()=>open(record)}><small className="muted">{record.kind.toUpperCase()}</small><h2>{title(record)}</h2><p>{record.kind==='project'?record.data.outcome:record.kind==='area'?record.data.responsibility:record.kind==='resource'?record.data.description:''}</p>{record.kind==='project'&&<progress aria-label="Project progress" value={record.data.progress} max={100}/>}</button>{page==='archive'&&<button onClick={()=>void act(()=>repository.update(record.id,r=>({...r,archived:false})),'Restored')}>Restore</button>}</section>)}</div>{!collection.length&&<p className="empty">{page==='archive'?'Archived items will be here when you need them.':`Create a ${kind} when you’re ready.`}</p>}</>;
}
function RecordEditor({record,records,open,act}: {record:Entity;records:Entity[];open:(r:Entity)=>void;act:Action}) {
  const repository=useRepository();
  const projectFor=useMemo(()=>createProjectResolver(records,record.accountId),[records,record.accountId]);
  const areaFor=useMemo(()=>createAreaResolver(records,record.accountId),[records,record.accountId]);
  const [draft,setDraft]=useState(record);
  const [saving,setSaving]=useState(false);
  const baseline=useRef(record.data);
  useEffect(()=>{
    const previous=baseline.current;
    baseline.current=record.data;
    setDraft(current=>JSON.stringify(current.data)===JSON.stringify(previous)?record:current);
  },[record]);
  function field(key:string,value:unknown) {setDraft(current=>({...current,data:{...current.data,[key]:value}} as Entity));}
  async function persist() {
    await repository.update(record.id,current=>{
      if(current.kind==='task'&&draft.kind==='task')return {...current,data:{...draft.data,reminderId:current.data.reminderId,recurrenceId:current.data.recurrenceId}};
      if(current.kind==='note'&&draft.kind==='note')return {...current,data:{...draft.data,attachmentIds:current.data.attachmentIds}};
      return {...current,data:draft.data} as Entity;
    });
  }
  async function save() {if(saving)return;setSaving(true);try{await act(persist,'Saved locally');}finally{setSaving(false);}}
  function openLinked(item:Entity) {if(JSON.stringify(draft.data)===JSON.stringify(baseline.current)){open(item);return;}void act(async()=>{await persist();open(item);});}
  const taskRecords=records.filter((r):r is Entity<'task'>=>r.kind==='task'&&r.accountId===record.accountId&&!r.deleted&&!r.archived&&(
    record.kind==='project'?projectFor(r)===record.id:
    record.kind==='area'?areaFor(r)===record.id:
    record.kind==='resource'?r.data.resourceId===record.id:
    record.kind==='task'?r.data.parentTaskId===record.id:false
  )).sort((a,b)=>a.createdAt-b.createdAt||a.id.localeCompare(b.id));
  const linkedNotes=records.filter(r=>(r.kind==='note'||r.kind==='resource')&&(r.data.projectId===record.id||areaFor(r)===record.id||r.kind==='note'&&r.data.resourceId===record.id));
  if(draft.kind==='note'&&record.kind==='note')return <NoteRecord record={record} draft={draft} records={records} field={field} open={openLinked} saving={saving} save={()=>void save()}
    archive={()=>void act(async()=>{await persist();await repository.update(record.id,current=>({...current,archived:!current.archived}));},record.archived?'Restored':'Archived')}
    remove={()=>void act(()=>repository.remove(record.id),'Deleted locally')}/>;
  if(draft.kind==='project'&&record.kind==='project')return <ProjectRecord record={record} draft={draft} records={records} tasks={taskRecords} linked={linkedNotes} field={field} open={openLinked} saving={saving} save={()=>void save()}
    taskRows={taskRecords.map(task=><TaskRow key={task.id} task={task} unscheduledLabel={draft.data.nextActionId===task.id?'Next action':'Later'} open={()=>openLinked(task)} toggle={()=>void act(()=>repository.update(task.id,r=>r.kind==='task'?{...r,data:{...r.data,completed:!r.data.completed}}:r))}/>)}
    addTask={<AddTask projectId={record.id} areaId={draft.data.areaId} act={(work,success)=>act(async()=>{if(JSON.stringify(draft.data)!==JSON.stringify(baseline.current))await persist();await work();},success)}/>}
    archive={()=>void act(async()=>{await persist();await repository.update(record.id,current=>({...current,archived:!current.archived}));},record.archived?'Restored':'Archived')}
    remove={()=>void act(()=>repository.remove(record.id),'Deleted locally')}
    addNote={()=>void act(async()=>{if(JSON.stringify(draft.data)!==JSON.stringify(baseline.current))await persist();open(await repository.create('note',{title:'',body:'',attachmentIds:[],projectId:record.id}));})}/>;
  return <div className="record-grid">
    <section className="panel record-main">
      <form onSubmit={event=>{event.preventDefault();void save();}}>
        {'title' in draft.data&&<label>Title<input value={draft.data.title} onChange={event=>field('title',event.target.value)}/></label>}
        {draft.kind==='project'&&<>
          <label>Outcome<textarea value={draft.data.outcome} onChange={event=>field('outcome',event.target.value)}/></label>
          <OptionalDateControl title="Target date" value={draft.data.targetDate} change={value=>field('targetDate',value)}/>
          <label>Progress<input type="range" min="0" max="100" value={draft.data.progress} onChange={event=>field('progress',Number(event.target.value))}/></label>
        </>}
        {draft.kind==='task'&&<>
          <ScheduleControl value={draft.data.doDate} change={value=>field('doDate',value)}/>
          <DeadlineControl value={draft.data.deadline} change={value=>field('deadline',value)}/>
          <label className="check-label"><input type="checkbox" checked={draft.data.completed} onChange={event=>field('completed',event.target.checked)}/>Completed</label>
          <PrimaryHomeControl record={draft} records={records} change={values=>Object.entries(values).forEach(([key,value])=>field(key,value))}/>
        </>}
        {draft.kind==='project'&&<label>Next action<select value={draft.data.nextActionId??''} onChange={event=>field('nextActionId',event.target.value||undefined)}><option value="">No next action</option>{records.filter((r):r is Entity<'task'>=>r.kind==='task'&&!r.archived&&!r.data.completed&&projectFor(r)===record.id).map(r=><option key={r.id} value={r.id}>{r.data.title}</option>)}</select></label>}
        {(draft.kind==='project'||draft.kind==='resource')&&<label>Area<select value={draft.data.areaId??''} onChange={event=>field('areaId',event.target.value||undefined)}><option value="">No area</option>{records.filter(r=>r.kind==='area'&&!r.archived).map(r=><option key={r.id} value={r.id}>{title(r)}</option>)}</select></label>}
        {draft.kind==='area'&&<>
          <label>Ongoing responsibility<textarea value={draft.data.responsibility} onChange={event=>field('responsibility',event.target.value)}/></label>
          <label>Standards<textarea value={draft.data.standards} onChange={event=>field('standards',event.target.value)}/></label>
        </>}
        {draft.kind==='resource'&&<>
          <label>Project<select value={draft.data.projectId??''} onChange={event=>field('projectId',event.target.value||undefined)}><option value="">No project</option>{records.filter(r=>r.kind==='project'&&!r.archived).map(r=><option key={r.id} value={r.id}>{title(r)}</option>)}</select></label>
          <label>Description<textarea value={draft.data.description} onChange={event=>field('description',event.target.value)}/></label>
          <label>Link<input type="url" value={draft.data.url??''} onChange={event=>field('url',event.target.value||undefined)}/></label>
        </>}
        {draft.kind==='dailyNote'&&<><h2>{new Date(draft.data.date+'T12:00:00').toLocaleDateString(undefined,{weekday:'long'})}</h2><h3>What happened today?</h3><p className="muted small">Write freely. This note is optional and automatically linked to its date.</p></>}
        {draft.kind==='dailyNote'&&<NoteEditor body={draft.data.body} change={value=>field('body',value)} notes={records.filter((r):r is Entity<'note'>=>r.kind==='note'&&r.id!==record.id)} open={openLinked}/>}
        {draft.kind==='dailyNote'&&<><h3>Linked today</h3><label>Link related item<select aria-label="Link related item" value="" onChange={event=>{if(event.target.value)field('relatedIds',[...new Set([...draft.data.relatedIds,event.target.value])]);}}><option value="">Choose an item</option>{records.filter(r=>r.id!==record.id&&['note','task','project','area','resource'].includes(r.kind)).map(r=><option key={r.id} value={r.id}>{title(r)}</option>)}</select></label>{draft.data.relatedIds.map(id=>{const item=records.find(r=>r.id===id);return item?<div className="row" key={id}><button type="button" onClick={()=>openLinked(item)}>{title(item)}</button><button type="button" aria-label={"Unlink "+title(item)} onClick={()=>field('relatedIds',draft.data.relatedIds.filter(value=>value!==id))}>×</button></div>:null;})}</>}
        <div className="row form-actions"><button className="primary" disabled={saving}>{saving?'Saving…':'Save'}</button><button type="button" onClick={()=>void act(()=>repository.update(record.id,r=>({...r,archived:!r.archived})),record.archived?'Restored':'Archived')}>{record.archived?'Restore':'Archive'}</button><button className="danger" type="button" onClick={()=>void act(()=>repository.remove(record.id),'Deleted locally')}>Delete</button></div>
      </form>
      <AttachmentList ownerId={record.id}/><AiAssist record={record}/>
      {['project','area','resource','task'].includes(record.kind)&&<section className="related-tasks"><h3>{record.kind==='task'?'Subtasks':record.kind==='area'?'Responsibilities':'Tasks'}</h3>{taskRecords.map(task=><TaskRow key={task.id} task={task} open={()=>openLinked(task)} toggle={()=>void act(()=>repository.update(task.id,r=>r.kind==='task'?{...r,data:{...r.data,completed:!r.data.completed}}:r))}/>)}
        {record.kind!=='task'&&<AddTask projectId={record.kind==='project'?record.id:undefined} areaId={record.kind==='area'?record.id:'areaId' in draft.data?draft.data.areaId:undefined} resourceId={record.kind==='resource'?record.id:undefined} act={(work,success)=>act(async()=>{await persist();await work();},success)}/>}
      </section>}
    </section>
    <aside className="record-side">
      {record.kind==='area'&&<section className="panel"><h3>Active projects</h3>{records.filter((r):r is Entity<'project'>=>r.kind==='project'&&!r.archived&&r.data.areaId===record.id).map(project=><button key={project.id} className="linked-record" onClick={()=>openLinked(project)}>{project.data.title}</button>)}</section>}
      {record.kind==='task'&&<TaskRecurrence task={record} records={records} act={act}/>}
      <ConnectedItemsControl record={record} data={draft.data} records={records} open={openLinked} change={draft.kind==='task'?ids=>field('relatedIds',ids):undefined}/>
      <section className="panel"><h3>{record.kind==='note'?'Backlinks':'Notes & resources'}</h3>{(record.kind==='note'?records.filter(r=>(r.kind==='note'||r.kind==='dailyNote')&&r.id!==record.id&&outgoingNotes(r.data.body,records.filter((item):item is Entity<'note'>=>item.kind==='note')).has(record.id)):linkedNotes).map(note=><button key={note.id} className="linked-record" onClick={()=>openLinked(note)}>{title(note)}</button>)}{['project','area','resource'].includes(record.kind)&&<button onClick={()=>void act(async()=>{await persist();const context=record.kind==='project'?{projectId:record.id}:record.kind==='area'?{areaId:record.id}:record.kind==='resource'?{resourceId:record.id}:{};open(await repository.create('note',{title:'',body:'',attachmentIds:[],...context}));})}>+ Add note</button>}</section>
      <section className="panel"><h3>Details</h3><p className="small">Created<br/>{new Date(record.createdAt).toLocaleString()}</p><p className="small">Updated<br/>{new Date(record.updatedAt).toLocaleString()}</p><p className="small">Saved on this device</p></section>
    </aside>
  </div>;
}
function AddTask({projectId,areaId,resourceId,parentTaskId,act}: {projectId?:string;areaId?:string;resourceId?:string;parentTaskId?:string;act:Action}) { const repository=useRepository(); const [name,setName]=useState('');return <form className="create-row" onSubmit={e=>{e.preventDefault();void act(async()=>{await repository.create('task',{title:name,completed:false,...(projectId?{projectId}:{}),...(areaId?{areaId}:{}),...(resourceId?{resourceId}:{}),...(parentTaskId?{parentTaskId}:{})});setName('');});}}><label className="sr-only" htmlFor={`add-task-${projectId||parentTaskId}`}>Task title</label><input id={`add-task-${projectId||parentTaskId}`} value={name} onChange={e=>setName(e.target.value)} placeholder="Next action"/><button disabled={!name.trim()}>+ Add</button></form>; }
function SearchView({open}: {open:(r:Entity)=>void}) { const repository=useRepository();const [query,setQuery]=useState('');const matches=useLiveQuery(()=>search(repository,query),[query],[]);return <><label className="search-label">Search your Nook<input autoFocus value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search notes, tasks, projects…"/></label><p className="muted small">Try project:Title, area:Title, or before:2026-10-01. Search works offline.</p><section className="panel">{matches.map(record=><button className="search-result" key={record.id} onClick={()=>open(record)}><small>{record.kind}</small><strong>{title(record)}</strong></button>)}</section></>;}
function Settings({act}: {act:Action}) { const repository=useRepository();const backup=useRef<HTMLInputElement>(null);const markdown=useRef<HTMLInputElement>(null);return <section className="panel settings"><h2>Data & Export</h2><p>Everything is stored on this device. Your data belongs to you.</p><button onClick={()=>void act(async()=>{const bytes=await exportBackup(repository);const url=URL.createObjectURL(new Blob([new Uint8Array(bytes)],{type:'application/zip'}));const a=document.createElement('a');a.href=url;a.download=`nook-${localDate()}.zip`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);},'Backup exported')}>Export Nook backup</button><button onClick={()=>backup.current?.click()}>Restore Nook backup</button><button onClick={()=>markdown.current?.click()}>Import Markdown</button><input className="sr-only" ref={backup} aria-label="Nook backup file" type="file" accept=".zip" onChange={e=>{const file=e.target.files?.[0];if(file)void act(async()=>restoreBackup(repository,new Uint8Array(await file.arrayBuffer())),'Backup restored');e.target.value='';}}/><input className="sr-only" ref={markdown} aria-label="Markdown files" type="file" accept=".md" multiple onChange={e=>{const files=Array.from(e.target.files??[]);void act(async()=>importMarkdown(repository,await Promise.all(files.map(async file=>({name:file.name,body:await file.text()})))),'Markdown imported');e.target.value='';}}/><hr/><h2>Offline & Sync</h2><AccountControls/><hr/><ParaPreferences/><hr/><AiSettings/></section>;}
function Calendar({tasks,records,open,act}: {tasks:Entity<'task'>[];records:Entity[];open:(r:Entity)=>void;act:Action}) { const repository=useRepository();
  const [date,setDate]=useState(localDate()); const [datePicker,setDatePicker]=useState(false); const items=tasks.filter(r=>r.data.doDate===date||r.data.deadline===date);
  const reminders=records.filter((r):r is Entity<'reminder'>=>r.kind==='reminder' && localDate(new Date(r.data.scheduledAt))===date);
  return <section className="panel calendar"><label>Daily agenda</label><button type="button" className="calendar-button" onClick={()=>setDatePicker(true)}>{date?displayDate(date):"Choose a day"}</button>{datePicker&&<DatePicker title="Daily agenda" value={date||undefined} close={()=>setDatePicker(false)} choose={value=>{setDate(value??"");setDatePicker(false);}}/>}<h2>{date?displayDate(date):'Choose a day'}</h2><button onClick={()=>void act(async()=>open(await dailyNote(repository,date)),'Daily note opened')} disabled={!date}>Open daily note</button>{items.map(task=><button key={task.id} className="search-result" onClick={()=>open(task)}><strong>{task.data.title}</strong><span>{task.data.doDate===date?'Do date':''}{task.data.doDate===date&&task.data.deadline===date?' · ':''}{task.data.deadline===date?'Deadline':''}</span></button>)}{records.filter(r=>r.kind==='project'&&r.data.targetDate===date).map(r=><button className="linked-record" key={r.id} onClick={()=>open(r)}>{title(r)} · Target date</button>)}{reminders.map(reminder=>{const target=records.find(r=>r.id===reminder.data.targetId);return target?<button className="linked-record" key={reminder.id} onClick={()=>open(target)}>{title(target)} · Reminder {new Date(reminder.data.scheduledAt).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}</button>:reminder.data.type==='inbox'?<p key={reminder.id}>Inbox review · {new Date(reminder.data.scheduledAt).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}</p>:null;})}{!items.length&&<p className="muted">No tasks scheduled for this day.</p>}</section>;
}
