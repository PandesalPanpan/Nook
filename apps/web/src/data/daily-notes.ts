import type {Entity} from '../../../../packages/schemas/src';
import {validDate} from '../../../../packages/schemas/src/recurrence';
import {Repository} from './repository';

/** One dated scratch note, with permanent tombstones for earlier deleted generations. */
export async function dailyNote(repository: Repository, date: string): Promise<Entity<'dailyNote'>> {
  if(!validDate(date)) throw new Error('Choose a valid date');
  return repository.db.transaction('rw',repository.db.records,repository.db.outbox,repository.db.search,async()=>{
    const existing=(await repository.list('dailyNote')).filter(note=>note.data.date===date).sort((a,b)=>b.createdAt-a.createdAt || a.id.localeCompare(b.id))[0];
    if(existing) return existing;
    let generation=1, id=`daily-${date}`;
    while(await repository.db.records.get([repository.accountId,id])) id=`daily-${date}-${++generation}`;
    return repository.create('dailyNote',{date,body:'## Wins\n\n## Loose thoughts\n',relatedIds:[]},id);
  });
}
