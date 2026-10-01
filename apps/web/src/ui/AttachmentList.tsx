import {useRef,useState,type ReactNode} from 'react';
import {useLiveQuery} from 'dexie-react-hooks';
import type {Entity} from '../../../../packages/schemas/src';
import {useRepository} from './runtime';

export function AttachmentList({ownerId,children}: {ownerId:string;children?:(controls:{attach:()=>void;busy:boolean})=>ReactNode}) { const repository=useRepository();
  const files=useLiveQuery(()=>repository.list('attachment').then(items=>items.filter(r=>r.data.ownerId===ownerId)),[ownerId],[]);
  const [error,setError]=useState(''); const [adding,setAdding]=useState(false); const picker=useRef<HTMLInputElement>(null);
  async function attach(files:File[]) {if(adding || !files.length)return;setAdding(true);setError('');try{await repository.attach(ownerId,files);}catch(e){setError(e instanceof Error?e.message:'Could not add attachment');}finally{setAdding(false);}}
  async function download(file:Entity<'attachment'>) {try{const original=await repository.db.files.get([repository.accountId,file.id]);if(!original)throw new Error('Original file unavailable');const url=URL.createObjectURL(new Blob([new Uint8Array(original.bytes)],{type:file.data.mimeType}));const a=document.createElement('a');a.href=url;a.download=file.data.filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch(e){setError(e instanceof Error?e.message:'Could not save original');}}
  return <>{children?.({attach:()=>picker.current?.click(),busy:adding})}<section className="record-attachments">{(!children || files.length>0)&&<h3>Attachments</h3>}<label className={children?'sr-only':undefined} aria-hidden={children?true:undefined}>Add attachment<input ref={picker} tabIndex={children?-1:undefined} aria-label="Add attachment" type="file" multiple disabled={adding} onChange={e=>{void attach(Array.from(e.target.files??[]));e.target.value='';}}/></label>{adding&&<p role="status">Adding attachments…</p>}{files.map(file=><button key={file.id} className="linked-record" onClick={()=>void download(file)}>{file.data.filename}</button>)}{error&&<p role="alert">{error}</p>}</section></>;
}
