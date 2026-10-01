import {useEffect,useRef,useState} from 'react';
import {useLiveQuery} from 'dexie-react-hooks';
import {useRepository} from './runtime';
import {currentSettings,defaultSettings} from '../data/settings';

const tips = {
  inbox: ['When unsure, leave it in Inbox.','Capture first. Choose a useful next step when you’re ready.'],
  project: ['Projects have finish lines.','“Lose 5 kg” = Project · “Stay healthy” = Area'],
  area: ["Areas don’t have finish lines.",'“Stay healthy” = Area · “Lose 5 kg” = Project'],
  resource: ['Keep what interests you.','Resources hold reference material for topics you care about.'],
  archive: ['Archive aggressively. Search is your safety net.','Inactive things stay searchable. Restore them whenever useful.'],
} as const;
export type TipTopic = keyof typeof tips;
function useSettings() {
  const repository=useRepository();
  const records=useLiveQuery(()=>repository.list('settings'),[repository]);
  return {repository,settings:records?currentSettings(records)?.data??defaultSettings:undefined};
}
export function ParaTip({topic}: {topic:TipTopic}) {
  const {repository,settings}=useSettings();const [guide,setGuide]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  if(!settings?.tipsEnabled||settings.dismissedTips.includes(`para:${topic}`))return null;
  return <section className="para-tip" aria-label="PARA tip"><span className="pill yellow">PARA TIP</span><h3>{tips[topic][0]}</h3><p>{tips[topic][1]}</p><div className="row para-tip-actions"><button disabled={busy} onClick={()=>{setBusy(true);void repository.updateSettings(s=>({...s,dismissedTips:[...new Set([...s.dismissedTips,`para:${topic}`])]})).catch(()=>setError('Could not save. Try again.')).finally(()=>setBusy(false));}}>Got it</button><button className="primary" onClick={()=>setGuide(true)}>Learn more</button></div>{error&&<p role="alert">{error}</p>}{guide&&<ParaGuide close={()=>setGuide(false)}/>}</section>;
}
const places=[['Projects','Outcomes with a finish line','Finish capstone prototype'],['Areas','Responsibilities to maintain','University · Health'],['Resources','Topics worth keeping','ESP32 · Design'],['Archive','Inactive things','Finished projects']] as const;
export function ParaGuide({close}: {close:()=>void}) {
  const dialog=useRef<HTMLDialogElement>(null);
  useEffect(()=>{dialog.current?.showModal();},[]);
  return <dialog ref={dialog} className="para-guide" aria-labelledby="para-heading" onCancel={close}><div className="row between"><div className="para-brand"><img alt="" src="/figma/4-138-imgEllipse.svg"/><strong>nook</strong></div><button aria-label="Close PARA guide" onClick={close}>×</button></div><h2 id="para-heading">How Nook organizes</h2><p className="para-subtitle">Four places. You don’t need to memorize them.</p><div className="para-places">{places.map(([name,description,example],index)=><section className="para-place" key={name}><img alt="" src={`/figma/4-138-imgEllipse${index+1}.svg`}/><div><h3>{name.toUpperCase()}</h3><strong>{description}</strong><p>{example}</p></div></section>)}</div><div className="para-guide-tip"><span className="pill yellow">TIP</span><p>When unsure, leave it in Inbox.</p></div><button className="primary full" onClick={close}>Continue</button></dialog>;
}
export function ParaPreferences() {
  const {repository,settings}=useSettings();const [guide,setGuide]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  const [enabled,setEnabled]=useState(true);
  useEffect(()=>{if(settings)setEnabled(settings.tipsEnabled);},[settings?.tipsEnabled]);
  function save(change: Parameters<typeof repository.updateSettings>[0]) {setBusy(true);setError('');void repository.updateSettings(change).catch(()=>{setEnabled(settings?.tipsEnabled??true);setError('Could not save learning preferences.');}).finally(()=>setBusy(false));}
  return <section className="para-preferences"><h2>Learn PARA</h2><label className="check-label"><input type="checkbox" disabled={!settings||busy} checked={enabled} onChange={e=>{const next=e.target.checked;setEnabled(next);save(s=>({...s,tipsEnabled:next}));}}/>Show contextual PARA tips</label><div className="row"><button onClick={()=>setGuide(true)}>How Nook organizes</button><button disabled={!settings||busy||!settings.dismissedTips.some(id=>id.startsWith('para:'))} onClick={()=>save(s=>({...s,dismissedTips:s.dismissedTips.filter(id=>!id.startsWith('para:'))}))}>Reset dismissed tips</button></div>{error&&<p role="alert">{error}</p>}{guide&&<ParaGuide close={()=>setGuide(false)}/>}</section>;
}
