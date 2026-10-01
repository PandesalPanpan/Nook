import type {Entity} from './index';

export function createProjectResolver(records: Entity[], accountId: string): (record: Entity) => string | undefined {
  const index = new Map(records.filter(r => !r.deleted && r.accountId === accountId).map(r => [r.id, r]));
  return record => {
    if(record.accountId !== accountId) return undefined;
    const visited = new Set<string>();
    let current: Entity | undefined = record;
    while(current && !current.deleted && !visited.has(current.id)) {
      visited.add(current.id);
      if('projectId' in current.data && current.data.projectId) return current.data.projectId;
      current = current.kind === 'task' && current.data.parentTaskId ? index.get(current.data.parentTaskId) : undefined;
      if(current && current.kind !== 'task') return undefined;
    }
    return undefined;
  };
}

/** Explicit context wins; older children can inherit through their Project. */
export function effectiveAreaId(record: Entity, records: Entity[]): string | undefined {
  return createAreaResolver(records,record.accountId)(record);
}
export function createAreaResolver(records: Entity[],accountId: string): (record:Entity)=>string|undefined {
  const index=new Map(records.filter(r=>!r.deleted&&r.accountId===accountId).map(r=>[r.id,r]));
  return record=>{
  if(record.accountId!==accountId)return undefined;
  const visited=new Set<string>();
  let current: Entity | undefined=record;
  while(current&&!current.deleted&&!visited.has(current.id)) {
    visited.add(current.id);
    if('areaId' in current.data&&current.data.areaId)return current.data.areaId;
    const projectId='projectId' in current.data?current.data.projectId:undefined;
    if(projectId) {
      const project=index.get(projectId);
      return project?.kind==='project'?project.data.areaId:undefined;
    }
    current=current.kind==='task'&&current.data.parentTaskId?index.get(current.data.parentTaskId):undefined;
    if(current&&current.kind!=='task')return undefined;
  }
  return undefined;
  };
}
