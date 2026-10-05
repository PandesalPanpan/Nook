import { useState } from 'react';
import type { Entity, Recurrence } from '../../../../packages/schemas/src';
import { useRepository, localDate } from './runtime';
import {DatePicker} from './Clarification';

export function TaskRecurrence({task, records, act}: {task: Entity<'task'>; records: Entity[]; act: (work: () => Promise<unknown>, message?: string) => Promise<void>}) { const repository=useRepository();
  const current = records.find(r => r.kind === 'recurrence' && r.id === task.data.recurrenceId);
  const rule = current?.kind === 'recurrence' ? current.data : undefined;
  const [frequency, setFrequency] = useState<Recurrence['frequency'] | ''>(rule?.frequency ?? '');
  const [interval, setInterval] = useState(String(rule?.interval ?? 1));
  const [anchorDate, setAnchor] = useState(rule?.anchorDate ?? task.data.doDate ?? task.data.deadline ?? localDate());
  const [datePicker,setDatePicker]=useState(false);
  return <section className="panel"><h3>Repeat</h3><label>Frequency<select value={frequency} onChange={e => setFrequency(e.target.value as typeof frequency)}><option value="">Does not repeat</option><option value="daily">Daily</option><option value="weekly">Weekly</option><option value="monthly">Monthly</option></select></label>{frequency && <><label>Every<input type="number" min="1" max="1000" value={interval} onChange={e => setInterval(e.target.value)}/></label><label>First scheduled date<button type="button" className="calendar-button" onClick={()=>setDatePicker(true)}>{anchorDate}</button></label><p className="small muted">Completion keeps this entry and creates the next scheduled task. Month-end dates stay anchored to the original day.</p></>}<button disabled={task.data.completed || task.archived} onClick={() => void act(() => repository.setRecurrence(task.id, frequency ? {frequency, interval: Number(interval), anchorDate} : undefined), 'Repeat schedule saved')}>Save repeat schedule</button>{datePicker&&<DatePicker title="First scheduled date" value={anchorDate} close={()=>setDatePicker(false)} choose={value=>{if(value)setAnchor(value);setDatePicker(false);}}/>}</section>;
}
