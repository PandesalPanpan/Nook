import {compareVersions,type Entity} from '../../../../packages/schemas/src/index';
import type {Repository} from '../data/repository';
import type {Suggestion} from './provider';
export type Proposal={id:string;source:Entity;suggestion:Suggestion;destination?:Entity};
/** Only called by explicit confirmation. All changes and outbox writes commit together. */
export async function applyProposal(repository:Repository,proposal:Proposal,selected:number[]=[]) {
  const {db,accountId}=repository;
  return db.transaction('rw',db.records,db.outbox,db.search,db.files,async()=>{
    const source=await db.records.get([accountId,proposal.source.id]);
    if(proposal.source.accountId!==accountId || !source || source.deleted || source.archived || compareVersions(source,proposal.source)!==0)throw new Error('This item changed. Request a fresh suggestion.');
    const suggestion=proposal.suggestion;
    if(suggestion.kind==='organize') {
      if(source.kind!=='capture')throw new Error('Choose an Inbox capture');
      const target=await db.records.get([accountId,suggestion.destinationId]);
      if(!target || target.deleted || target.archived || !['project','area','resource'].includes(target.kind))throw new Error('Destination unavailable');
      if(!proposal.destination || proposal.destination.accountId!==accountId || proposal.destination.id!==target.id || compareVersions(target,proposal.destination)!==0)throw new Error('Destination changed. Request a fresh suggestion.');
      const note=await repository.process(source.id,'note');
      await repository.update(note.id,r=>{if(r.kind!=='note')throw new Error('Note unavailable');return {...r,data:{...r.data,[`${target.kind}Id`]:target.id}};});return;
    }
    if(suggestion.kind==='summary') {
      if(source.kind!=='note')throw new Error('Choose a note');
      await repository.create('note',{title:`Summary · ${source.data.title||'Note'}`,body:`${suggestion.summary}\n\nSource: [[${source.id}]]`,attachmentIds:[],projectId:source.data.projectId,areaId:source.data.areaId,resourceId:source.data.resourceId},proposal.id);return;
    }
    if(source.kind!=='project' && source.kind!=='task')throw new Error('Choose a project or task');
    const indices=[...new Set(selected)];if(!indices.length || indices.some(i=>!Number.isInteger(i) || i<0 || i>=suggestion.actions.length))throw new Error('Select suggested actions');
    for(const i of indices)await repository.create('task',{title:suggestion.actions[i],completed:false,...(source.kind==='project'?{projectId:source.id,areaId:source.data.areaId}:{parentTaskId:source.id,projectId:source.data.projectId,areaId:source.data.areaId})},`${proposal.id}:${i}`);
  });
}
