import {useEffect,useRef,useState} from 'react';
import type {Entity} from '../../../../packages/schemas/src/index';
import {useRepository} from './runtime';
import {AiSettingsStore,CompatibleProvider,defaults,providers,type AiConfiguration,type AiRequest} from '../ai/provider';
import {applyProposal,type Proposal} from '../ai/proposals';
const names={off:'Off',openai:'OpenAI',deepseek:'DeepSeek',custom:'Custom compatible API'};
export function AiSettings() {
  const repository=useRepository();const store=useRef(new AiSettingsStore(localStorage,repository.accountId));
  const [saved,setSaved]=useState(()=>store.current.load());const [config,setConfig]=useState(saved);const [key,setKey]=useState('');const [message,setMessage]=useState('');
  function save(){try{const next:AiConfiguration={...config,apiKey:key || (saved.provider===config.provider && saved.endpoint===config.endpoint?saved.apiKey:'')};store.current.save(next);const actual=store.current.load();setSaved(actual);setConfig(actual);setKey('');setMessage('AI settings saved on this browser');}catch(error){setMessage(error instanceof Error?error.message:'Could not save AI settings');}}
  return <section className="ai-settings" aria-label="AI settings"><h2>Nook AI</h2><p>AI suggests. You decide.</p><label htmlFor="nook-ai-provider">Provider</label><select id="nook-ai-provider" value={config.provider} onChange={e=>{setConfig(defaults(e.target.value as AiConfiguration['provider']));setKey('');setMessage('');}}>{providers.map(name=><option value={name} key={name}>{names[name]}</option>)}</select><label>Supported</label><div className="row"><span className="pill primary">OpenAI</span><span className="pill indigo">DeepSeek</span><span className="pill">Custom compatible API</span></div>
    {config.provider!=='off'&&<><label>Model<input value={config.model} onChange={e=>setConfig({...config,model:e.target.value})}/></label>{config.provider==='custom'&&<label>Chat completions endpoint<input type="url" placeholder="https://…/v1/chat/completions" value={config.endpoint} onChange={e=>setConfig({...config,endpoint:e.target.value})}/></label>}<label>API key<input type="password" autoComplete="off" value={key} placeholder={saved.provider===config.provider && saved.endpoint===config.endpoint && saved.apiKey?'Saved key · leave blank to keep':'Paste your API key'} onChange={e=>setKey(e.target.value)}/></label></>}
    <button type="button" className="primary" onClick={save}>Save AI settings</button><div className="account-info"><span className="pill teal">CONTROL</span><strong>AI actions always require confirmation.</strong><p>Only the saved text you choose and destination titles are sent to your provider. Keys stay in this browser, outside sync and exports. Turning AI off removes the saved key.</p></div>{message&&<p role="status">{message}</p>}
  </section>;
}
export function AiAssist({record}:{record:Entity}) {
  const repository=useRepository();const controller=useRef<AbortController|null>(null);const [proposal,setProposal]=useState<Proposal>();const [selected,setSelected]=useState<number[]>([]);const [busy,setBusy]=useState(false);const [message,setMessage]=useState('');
  const [destinationTitle,setDestinationTitle]=useState('');
  useEffect(()=>{setProposal(undefined);setSelected([]);setMessage('');return()=>controller.current?.abort();},[record.id,record.updatedAt,record.clientId]);
  const config=new AiSettingsStore(localStorage,repository.accountId).load();
  if(config.provider==='off' || !['capture','note','task','project'].includes(record.kind) || record.archived)return null;
  const operation:AiRequest['operation']=record.kind==='capture'?'organize':record.kind==='note'?'summary':'actions';
  async function request(){
    const signal=new AbortController();controller.current?.abort();controller.current=signal;setBusy(true);setMessage('');setProposal(undefined);
    try {
      const destinationRecords=operation==='organize'?(await repository.db.records.where('accountId').equals(repository.accountId).toArray()).filter((r):r is Entity<'project'>|Entity<'area'>|Entity<'resource'>=>!r.deleted && !r.archived && ['project','area','resource'].includes(r.kind)).slice(0,100):[];
      const destinations=destinationRecords.map(r=>({id:r.id,kind:r.kind,title:r.data.title}));
      if(operation==='organize'&&!destinations.length)throw new Error('Create a Project, Area or Resource first');
      const text='body' in record.data?record.data.body:'title' in record.data?`${record.data.title}\n${'outcome' in record.data?record.data.outcome:''}`:'';
      const suggestion=await new CompatibleProvider(config).suggest({operation,text,destinations},signal.signal);
      if(!signal.signal.aborted){setProposal({id:crypto.randomUUID(),source:structuredClone(record),suggestion,destination:suggestion.kind==='organize'?structuredClone(destinationRecords.find(d=>d.id===suggestion.destinationId)):undefined});setSelected([]);setDestinationTitle(suggestion.kind==='organize'?destinations.find(d=>d.id===suggestion.destinationId)?.title??'':'');}
    }catch(error){if(!signal.signal.aborted)setMessage(error instanceof Error?error.message:'AI unavailable');}
    finally{if(controller.current===signal)setBusy(false);}
  }
  async function apply(){if(!proposal)return;setBusy(true);try{await applyProposal(repository,proposal,selected);setProposal(undefined);setMessage('Accepted and saved locally');}catch(error){setMessage(error instanceof Error?error.message:'Could not apply');}finally{setBusy(false);}}
  return <section className="panel ai-assist" aria-label="AI suggestions"><span className={`pill ${operation==='organize'?'indigo':'teal'}`}>{operation==='actions'?'BREAK DOWN':operation.toUpperCase()}</span><h3>AI suggests. You decide.</h3><p className="muted small">Request sends this item’s saved text to {names[config.provider]}. Nothing changes until you confirm.</p><button type="button" disabled={busy} onClick={()=>void request()}>{busy?'Working…':operation==='organize'?'Suggest destination':operation==='summary'?'Summarize note':'Suggest next actions'}</button>
    {proposal&&<div aria-label="Review suggestion">{proposal.suggestion.kind==='organize'?<><p>Looks related to: <strong>{destinationTitle}</strong></p><p>{proposal.suggestion.reason}</p></>:proposal.suggestion.kind==='summary'?<p className="ai-summary">{proposal.suggestion.summary}</p>:proposal.suggestion.actions.map((action,index)=><label className="check-label" key={index}><input type="checkbox" checked={selected.includes(index)} onChange={e=>setSelected(e.target.checked?[...selected,index]:selected.filter(i=>i!==index))}/>{action}</label>)}<div className="row"><button type="button" className="primary" disabled={busy || proposal.suggestion.kind==='actions'&&!selected.length} onClick={()=>void apply()}>{proposal.suggestion.kind==='organize'?'Move as note':proposal.suggestion.kind==='summary'?'Save summary as note':'Add selected actions'}</button><button type="button" disabled={busy} onClick={()=>setProposal(undefined)}>Dismiss</button></div></div>}
    {message&&<p role="status">{message}</p>}
  </section>;
}
