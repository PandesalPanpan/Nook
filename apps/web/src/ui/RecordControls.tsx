import {useState} from 'react';
import type {Entity} from '../../../../packages/schemas/src/index';
import {useRepository, localDate, displayDate} from './runtime';
import {DatePicker, HomePicker, RelatedPicker} from './Clarification';

function itemTitle(record:Entity) {
  if(record.kind==='dailyNote') return `Daily note · ${record.data.date}`;
  return 'title' in record.data ? record.data.title || '(Untitled)' : record.kind;
}

export function PrimaryHomeControl({record,records,change}:{record:Entity<'task'|'note'>;records:Entity[];change:(fields:Record<string,unknown>)=>void}) {
  const repository=useRepository();
  const [picker,setPicker]=useState(false);
  const ids=[record.data.projectId,record.data.areaId,record.data.resourceId].filter((id):id is string=>!!id);
  const selectedId=ids.length===1?ids[0]:undefined;
  const selected=records.find(item=>item.id===selectedId&&!item.deleted&&!item.archived);
  const homeLabel=selected?itemTitle(selected):ids.length>1?'Multiple existing associations':ids.length===1?'Unavailable existing collection':'Add to…';
  function choose(id?:string) {
    const target=records.find(item=>item.id===id);
    change({projectId:target?.kind==='project'?target.id:undefined,areaId:target?.kind==='area'?target.id:undefined,resourceId:target?.kind==='resource'?target.id:undefined});
    setPicker(false);
  }
  return <section className="record-home-control">
    <div><h3>Primary home</h3><p className="muted small">One Project, Area, or Resource. Other connections stay separate.</p></div>
    <button type="button" className="home-trigger" onClick={()=>setPicker(true)} aria-label={`Add to… ${homeLabel}`}>
      <span>{homeLabel}</span><span aria-hidden="true">›</span>
    </button>
    {(ids.length>1||ids.length===1&&!selected)&&<p className="muted small">Legacy associations remain until you choose a single primary home.</p>}
    {picker&&<HomePicker records={records} selected={selectedId} close={()=>setPicker(false)} choose={choose} repository={repository}/>}
  </section>;
}

export function ConnectedItemsControl({record,data,records,open,change}:{record:Entity;data:Entity['data'];records:Entity[];open:(item:Entity)=>void;change?:(ids:string[])=>void}) {
  const repository=useRepository();
  const [picker,setPicker]=useState(false);
  const active=records.filter(item=>item.accountId===record.accountId&&!item.deleted&&!item.archived);
  const selectedIds=('relatedIds' in data?data.relatedIds:undefined)??[];
  const selected=selectedIds.map(id=>active.find(item=>item.id===id)).filter((item):item is Entity=>!!item);
  const incoming=active.filter(item=>item.id!==record.id&&'relatedIds' in item.data&&(item.data.relatedIds?.includes(record.id)??false));
  const sourceCaptureId='sourceCaptureId' in data?data.sourceCaptureId:undefined;
  const siblings=sourceCaptureId?active.filter(item=>item.id!==record.id&&(item.kind==='task'||item.kind==='note')&&'sourceCaptureId' in item.data&&item.data.sourceCaptureId===sourceCaptureId):[];
  const source=sourceCaptureId?active.find((item):item is Entity<'capture'>=>item.id===sourceCaptureId&&item.kind==='capture'):undefined;
  const visible=[...new Map([...selected,...incoming,...siblings].map(item=>[item.id,item])).values()];
  const missing=selectedIds.filter(id=>!active.some(item=>item.id===id));
  return <section className="panel record-connected-items" aria-label="Connected items">
    <div className="row between"><h3>Connected items</h3>{change&&<button type="button" onClick={()=>setPicker(true)}>{selectedIds.length?'Edit links':'Connect'}</button>}</div>
    {source&&<button type="button" className="linked-record" onClick={()=>open(source)}><strong>Original thought</strong><span>Open processed history</span></button>}
    {visible.map(item=><button type="button" className="linked-record" key={item.id} onClick={()=>open(item)}><strong>{itemTitle(item)}</strong><span>{item.kind==='dailyNote'?'Daily note':item.kind[0].toUpperCase()+item.kind.slice(1)}{selectedIds.includes(item.id)?' · Connected':''}{siblings.some(sibling=>sibling.id===item.id)?' · Same thought':''}</span></button>)}
    {missing.map(id=><div className="row between missing-link" key={id}><span className="muted small">Unavailable connected item</span>{change&&<button type="button" aria-label="Remove unavailable connection" onClick={()=>change(selectedIds.filter(value=>value!==id))}>Remove</button>}</div>)}
    {!visible.length&&!missing.length&&<p className="muted small">No connections yet.</p>}
    {picker&&change&&<RelatedPicker records={active} selected={selectedIds} selfId={record.id} accountId={repository.accountId} close={()=>setPicker(false)} save={ids=>{change(ids);setPicker(false);}}/>}
  </section>;
}

export function ScheduleControl({value,change}:{value?:string;change:(value?:string)=>void}) {
  const [picker,setPicker]=useState(false);
  function preset(days:number) {const date=new Date();date.setDate(date.getDate()+days);change(localDate(date));}
  return <div className="record-date-control"><h3>Schedule</h3><p className="muted small">When you intend to work on it.</p><div className="date-presets">
    {[['Today',0],['Tomorrow',1],['Next week',7]].map(([label,days])=><button type="button" key={label} aria-pressed={value===localDate(new Date(new Date().setDate(new Date().getDate()+Number(days))))} onClick={()=>preset(Number(days))}>{label}</button>)}
    <button type="button" className="calendar-button" onClick={()=>setPicker(true)}>{value?displayDate(value):'Choose date'}</button>
  </div>{value&&<button type="button" className="quiet-action" onClick={()=>change(undefined)}>Clear schedule</button>}
  {picker&&<DatePicker title="Schedule" value={value} close={()=>setPicker(false)} choose={date=>{change(date);setPicker(false);}}/>}</div>;
}

export function DeadlineControl({value,change}:{value?:string;change:(value?:string)=>void}) {
  const [picker,setPicker]=useState(false);
  return <details className="record-deadline" open={!!value}><summary>Deadline · Optional{value?` · ${displayDate(value)}`:''}</summary><div className="record-date-control">
    <button type="button" className="calendar-button" onClick={()=>setPicker(true)}>{value?displayDate(value):'Add a deadline'}</button>
    {value&&<button type="button" className="quiet-action" onClick={()=>change(undefined)}>Clear deadline</button>}
    {picker&&<DatePicker title="Deadline" value={value} close={()=>setPicker(false)} choose={date=>{change(date);setPicker(false);}}/>}
  </div></details>;
}

export function OptionalDateControl({title,value,change}:{title:string;value?:string;change:(value?:string)=>void}) {
  const [picker,setPicker]=useState(false);
  return <div className="record-date-control"><h3>{title}</h3><button type="button" className="calendar-button" onClick={()=>setPicker(true)}>{value?displayDate(value):`Choose ${title.toLocaleLowerCase()}`}</button>
    {value&&<button type="button" className="quiet-action" onClick={()=>change(undefined)}>Clear date</button>}
    {picker&&<DatePicker title={title} value={value} close={()=>setPicker(false)} choose={date=>{change(date);setPicker(false);}}/>}
  </div>;
}
