import 'fake-indexeddb/auto';
import {afterEach,expect,test,vi} from 'vitest';
import {unzipSync,strFromU8} from 'fflate';
import {NookDatabase,Repository} from '../data/repository';
import {exportBackup} from '../data/backup';
import {AiSettingsStore,CompatibleProvider,defaults} from './provider';
import {applyProposal} from './proposals';
const databases:NookDatabase[]=[];
const values=new Map<string,string>();
const storage:Storage={get length(){return values.size;},clear:()=>values.clear(),getItem:key=>values.get(key)??null,key:index=>[...values.keys()][index]??null,removeItem:key=>{values.delete(key);},setItem:(key,value)=>{values.set(key,value);}};
function repository(){const db=new NookDatabase(crypto.randomUUID());databases.push(db);return new Repository(db,'local:ai','client');}
afterEach(async()=>{storage.clear();await Promise.all(databases.splice(0).map(db=>db.delete()));});
const configuration={...defaults('openai'),apiKey:'fake-device-key'};
const response=(content:unknown)=>new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify(content)}}]}));
test('Off makes no request; account-local keys never enter records, outbox or backup',async()=>{
  const repo=repository(),fetcher=vi.fn();const store=new AiSettingsStore(storage,repo.accountId);store.save(configuration);
  expect(new AiSettingsStore(storage,'bob').load().provider).toBe('off');expect(store.load().apiKey).toBe('fake-device-key');
  await expect(new CompatibleProvider(defaults('off'),fetcher).suggest({operation:'summary',text:'Note',destinations:[]},new AbortController().signal)).rejects.toThrow('off');expect(fetcher).not.toHaveBeenCalled();
  await repo.capture('Local thought');expect(JSON.stringify(await repo.db.outbox.toArray())).not.toContain('fake-device-key');
  const entries=unzipSync(await exportBackup(repo));expect(Object.values(entries).map(bytes=>strFromU8(bytes)).join()).not.toContain('fake-device-key');
  store.save(defaults('off'));expect(store.load().apiKey).toBe('');
});
test('provider validates schema and destination IDs and hides untrusted error bodies',async()=>{
  const request={operation:'organize' as const,text:'Research',destinations:[{id:'garden',kind:'project' as const,title:'Garden'}]};
  const fetcher=vi.fn<typeof fetch>().mockResolvedValue(response({kind:'organize',destinationId:'unknown',reason:'fake-device-key'}));
  await expect(new CompatibleProvider(configuration,fetcher).suggest(request,new AbortController().signal)).rejects.toThrow('valid suggestion');
  fetcher.mockResolvedValue(new Response('fake-device-key',{status:401}));await expect(new CompatibleProvider(configuration,fetcher).suggest(request,new AbortController().signal)).rejects.not.toThrow('fake-device-key');
  expect(fetcher.mock.calls[0][1]).toMatchObject({redirect:'error',credentials:'omit',headers:{Authorization:'Bearer fake-device-key'}});
  await expect(new CompatibleProvider({...configuration,provider:'custom',endpoint:'http://unsafe.example'},fetcher).suggest(request,new AbortController().signal)).rejects.toThrow('HTTPS');
});
test('all enabled providers use the configured endpoint and validate successful responses',async()=>{
  for(const name of ['openai','deepseek','custom'] as const) {
    const config={...defaults(name),apiKey:'fake-local-key',...(name==='custom'?{endpoint:'https://compatible.example/v1/chat/completions',model:'custom-model'}:{})};
    const fetcher=vi.fn<typeof fetch>().mockResolvedValue(response({kind:'summary',summary:'Short note'}));
    expect(await new CompatibleProvider(config,fetcher).suggest({operation:'summary',text:'Saved note',destinations:[]},new AbortController().signal)).toEqual({kind:'summary',summary:'Short note'});
    expect(fetcher.mock.calls[0][0]).toBe(config.endpoint);const body=JSON.parse(fetcher.mock.calls[0][1]!.body as string);expect(body.model).toBe(config.model);expect(body.response_format).toEqual({type:'json_object'});expect(JSON.stringify(body)).not.toContain('fake-local-key');
    if(name==='openai'){expect(body.store).toBe(false);expect(body.max_completion_tokens).toBe(2048);}else expect(body.max_tokens).toBe(2048);
  }
});
test('only confirmed selected next actions are created, atomically and once',async()=>{
  const repo=repository();const source=await repo.create('project',{title:'Garden',outcome:'Plant it',progress:0});
  const suggestion=await new CompatibleProvider(configuration,async()=>response({kind:'actions',actions:['Buy seeds','Plant seeds']})).suggest({operation:'actions',text:'Garden',destinations:[]},new AbortController().signal);
  expect(await repo.list('task')).toEqual([]);const proposal={id:crypto.randomUUID(),source,suggestion};
  await applyProposal(repo,proposal,[1]);expect((await repo.list('task')).map(r=>r.data)).toEqual([{title:'Plant seeds',completed:false,projectId:source.id,areaId:undefined}]);
  await expect(applyProposal(repo,proposal,[0,1])).rejects.toThrow('identity');expect(await repo.list('task')).toHaveLength(1);
});
test('stale, deleted or other-account proposals cannot mutate anything',async()=>{
  const repo=repository();const source=await repo.create('task',{title:'Task',completed:false});const proposal={id:crypto.randomUUID(),source,suggestion:{kind:'actions' as const,actions:['Next']}};
  await repo.update(source.id,r=>r.kind==='task'?{...r,data:{...r.data,title:'Edited'}}:r);await expect(applyProposal(repo,proposal,[0])).rejects.toThrow('changed');
  await expect(applyProposal(new Repository(repo.db,'bob','b'),proposal,[0])).rejects.toThrow('changed');
  await repo.remove(source.id);await expect(applyProposal(repo,proposal,[0])).rejects.toThrow('changed');expect(await repo.list('task')).toEqual([]);
  const target=await repo.create('area',{title:'Health',responsibility:'',standards:''}),capture=await repo.capture('Reference');
  await repo.update(target.id,r=>r.kind==='area'?{...r,data:{...r.data,title:'Renamed'}}:r);
  await expect(applyProposal(repo,{id:crypto.randomUUID(),source:capture,destination:target,suggestion:{kind:'organize',destinationId:target.id,reason:'Reference'}})).rejects.toThrow('Destination changed');expect(await repo.list('note')).toEqual([]);
});
test('confirmed organization preserves originals and summary leaves source note untouched',async()=>{
  const repo=repository();const target=await repo.create('area',{title:'Health',responsibility:'Stay healthy',standards:''});
  const source=await repo.capture('Reference','text',[new File(['original'],'a.txt')]);
  await applyProposal(repo,{id:crypto.randomUUID(),source,destination:target,suggestion:{kind:'organize',destinationId:target.id,reason:'Reference'}});
  const note=(await repo.list('note'))[0];expect(note.data.areaId).toBe(target.id);expect((await repo.list('attachment'))[0].data.ownerId).toBe(note.id);
  expect(await repo.db.files.count()).toBe(1);await applyProposal(repo,{id:crypto.randomUUID(),source:note,suggestion:{kind:'summary',summary:'Short reference'}});
  expect((await repo.db.records.get([repo.accountId,note.id]))?.data).toEqual(note.data);expect(await repo.list('note')).toHaveLength(2);
});
