import { compareVersions, type Entity, type Payloads } from '../../../../packages/schemas/src/index';

export const defaultSettings: Payloads['settings'] = {tipsEnabled:true,dismissedTips:[],dailyNotesEnabled:false,inboxReviewEnabled:false};
export function currentSettings(records: Entity[]): Entity<'settings'> | undefined {
  return records.filter((r): r is Entity<'settings'> => r.kind==='settings' && !r.deleted && !r.archived)
    .sort((a,b)=>compareVersions(b,a)||a.id.localeCompare(b.id))[0];
}
