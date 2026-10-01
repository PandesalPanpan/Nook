import {useState,type ReactNode} from 'react';
import type {Entity} from '../../../../packages/schemas/src';
import {displayDate} from './runtime';
import {AttachmentList} from './AttachmentList';
import {AiAssist} from './AiControls';

export function projectContext(project:Entity<'project'>,records:Entity[]) {
  const area=records.find(item=>item.kind==='area' && item.id===project.data.areaId && item.accountId===project.accountId && !item.deleted && !item.archived);
  return [area?.kind==='area'?area.data.title:undefined,project.data.targetDate?`target ${displayDate(project.data.targetDate)}`:undefined,`${project.data.progress}% complete`].filter(Boolean).join(' · ');
}
const label=(item:Entity)=>'title' in item.data?item.data.title||'Untitled note':item.kind;
const order=(a:Entity,b:Entity)=>a.createdAt-b.createdAt||a.id.localeCompare(b.id);

export function ProjectRecord({record,draft,records,tasks,linked,field,open,taskRows,addTask,saving,save,archive,remove,addNote}:{
  record:Entity<'project'>;draft:Entity<'project'>;records:Entity[];tasks:Entity<'task'>[];linked:Entity[];
  field:(key:string,value:unknown)=>void;open:(item:Entity)=>void;taskRows:ReactNode;addTask:ReactNode;
  saving:boolean;save:()=>void;archive:()=>void;remove:()=>void;addNote:()=>void;
}) {
  const [adding,setAdding]=useState(tasks.length===0);
  const [detailsOpen,setDetailsOpen]=useState(!record.data.outcome.trim());
  const next=tasks.find(item=>item.id===draft.data.nextActionId && !item.data.completed);
  const related=linked.filter(item=>item.accountId===record.accountId && !item.deleted && !item.archived).sort(order);
  const activity=[record,...tasks,...related].sort((a,b)=>b.updatedAt-a.updatedAt||a.id.localeCompare(b.id)).slice(0,5);
  return <div className="project-workspace">
    <div className="project-summary">
      <section className="panel project-outcome" aria-label="Project outcome"><small>OUTCOME</small><h2>{draft.data.outcome||'What will be true when this is finished?'}</h2><progress aria-label="Project progress" value={draft.data.progress} max={100}/></section>
      <section className="panel elevated project-next" aria-label="Project next action"><span className="pill yellow">NEXT ACTION</span><h3>{next?.data.title||'Choose one useful next step.'}</h3>{next&&<button className="primary" onClick={()=>open(next)}>Start</button>}</section>
    </div>
    <div className="project-content">
      <section className="panel project-tasks" aria-label="Project tasks"><div className="row between"><h2>Tasks</h2><button aria-expanded={adding} aria-controls={`project-add-${record.id}`} onClick={()=>setAdding(value=>!value)}>{adding?'Close add task':'+ Add'}</button></div>
        <div className="project-task-list">{taskRows}{!tasks.length&&<p className="muted">A small next step is enough.</p>}</div>
        <div id={`project-add-${record.id}`} hidden={!adding}>{addTask}</div>
      </section>
      <aside className="project-side">
        <section className="panel project-related" aria-label="Project notes and resources"><div className="row between"><h2>Notes &amp; resources</h2><button className="project-add-note" onClick={addNote}>+ Add note</button></div>{related.map(item=><button className="linked-record" key={item.id} onClick={()=>open(item)}>{label(item)}</button>)}</section>
        <section className="panel project-activity" aria-label="Project activity"><h2>Activity</h2><ul>{activity.map(item=><li key={item.id}><time dateTime={new Date(item.updatedAt).toISOString()}>{new Date(item.updatedAt).toLocaleDateString(undefined,{month:'short',day:'numeric'})}</time> · {item.id===record.id?'Project saved':item.kind==='task'&&item.data.completed?`Completed ${label(item)}`:`Updated ${label(item)}`}</li>)}</ul><p className="small muted">Latest saved changes on this device.</p></section>
      </aside>
    </div>
    <section className="panel project-details"><details open={detailsOpen} onToggle={event=>setDetailsOpen(event.currentTarget.open)}><summary>Project details</summary><form onSubmit={e=>{e.preventDefault();save();}}>
      <label>Title<input value={draft.data.title} onChange={e=>field('title',e.target.value)}/></label>
      <label>Outcome<textarea value={draft.data.outcome} onChange={e=>field('outcome',e.target.value)}/></label>
      <div className="two-fields"><label>Target date<input type="date" value={draft.data.targetDate??''} onChange={e=>field('targetDate',e.target.value||undefined)}/></label><label>Progress<input type="range" min="0" max="100" value={draft.data.progress} onChange={e=>field('progress',Number(e.target.value))}/></label></div>
      <label>Area<select value={draft.data.areaId??''} onChange={e=>field('areaId',e.target.value||undefined)}><option value="">No area</option>{records.filter((item):item is Entity<'area'>=>item.kind==='area' && item.accountId===record.accountId && !item.deleted && !item.archived).sort(order).map(item=><option key={item.id} value={item.id}>{item.data.title}</option>)}</select></label>
      <label>Next action<select value={draft.data.nextActionId??''} onChange={e=>field('nextActionId',e.target.value||undefined)}><option value="">No next action</option>{tasks.filter(item=>!item.data.completed).map(item=><option key={item.id} value={item.id}>{item.data.title}</option>)}</select></label>
      <div className="row form-actions"><button className="primary" disabled={saving}>{saving?'Saving…':'Save'}</button><button type="button" onClick={archive}>{record.archived?'Restore':'Archive'}</button><button type="button" className="danger" onClick={remove}>Delete</button></div>
    </form><AttachmentList ownerId={record.id}/><AiAssist record={record}/></details></section>
  </div>;
}
