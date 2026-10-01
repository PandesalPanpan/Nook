import {expect,test} from 'vitest';
import type {Entity} from '../../../../packages/schemas/src/index';
import {upcomingTasks} from './today';

test('future deadlines stay visible independently of Today actions and omit completed or past work',()=>{
  const task=(id:string,deadline:string,completed=false):Entity<'task'>=>({id,accountId:'local:test',clientId:'test',schemaVersion:1,kind:'task',createdAt:1,updatedAt:1,deleted:false,archived:false,data:{title:id,completed,deadline}});
  const visible=task('do-today','2026-10-03'), next=task('project-next','2026-10-04'), third=task('third','2026-10-05'), fourth=task('outside-preview','2026-10-06');
  const future=task('future','2026-10-07'), today=task('due-today','2026-10-01'), done=task('done','2026-10-08',true);
  expect(upcomingTasks([visible,next,third,fourth,future,today,done, {...future,id:'archived',archived:true}, {...future,id:'deleted',deleted:true}],'2026-10-01').map(r=>r.id)).toEqual(['do-today','project-next','third','outside-preview','future']);
});
