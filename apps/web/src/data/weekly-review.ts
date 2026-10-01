import type {Entity} from '../../../../packages/schemas/src';
import {validDate} from '../../../../packages/schemas/src/recurrence';
import {Repository} from './repository';

export function weekStart(date: string): string {
  if(!validDate(date)) throw new Error('Choose a valid review date');
  const day=new Date(date); day.setUTCDate(day.getUTCDate() - (day.getUTCDay()+6)%7);
  return day.toISOString().slice(0,10);
}
export async function saveWeeklyFocus(repo: Repository, date: string, body: string): Promise<Entity<'note'>> {
  if(!body.trim()) throw new Error('Choose one thing to focus on');
  const week=weekStart(date);
  return repo.db.transaction('rw',repo.db.records,repo.db.outbox,repo.db.search,async()=>{
    let id=`weekly-focus-${week}`,generation=1;
    for(;;) {
      const existing=await repo.db.records.get([repo.accountId,id]);
      if(!existing) return repo.create('note',{title:`Weekly focus · ${week}`,body,attachmentIds:[]},id);
      if(existing.kind==='note' && !existing.deleted) {
        await repo.update(id,r=>r.kind==='note'?{...r,data:{...r.data,body}}:r);
        return (await repo.db.records.get([repo.accountId,id])) as Entity<'note'>;
      }
      id=`weekly-focus-${week}-${++generation}`;
    }
  });
}
