// @vitest-environment jsdom
import React from 'react';
import 'fake-indexeddb/auto';
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {expect,test} from 'vitest';
import {AccountSession} from '../data/account-session';
import {AuthSession} from '../data/auth-session';
import {NookDatabase,Repository} from '../data/repository';
import {AccountControls,AuthContext} from './AccountControls';
import {RepositoryContext} from './runtime';

test('sync controls announce active work and safe retry status, then successful recovery',async()=>{
  const db=new NookDatabase(crypto.randomUUID());let fail=true;let release!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;});
  const accounts=new AccountSession(new Repository(db,'local:status','client'),()=>({push:async r=>r,pull:async()=>{await gate;if(fail)throw new Error('private transport detail');return [];}}));
  try {
    await accounts.activate('alice');
    const auth=new AuthSession(accounts,{current:()=>({uid:'alice',email:'alice@example.test'}),ready:async()=>{},observe:()=>()=>{},beforeChange:()=>()=>{},signIn:async()=>{},register:async()=>{},signOut:async()=>{}});
    render(<AuthContext.Provider value={auth}><RepositoryContext.Provider value={accounts.getSnapshot().repository}><AccountControls/></RepositoryContext.Provider></AuthContext.Provider>);
    fireEvent.click(screen.getByRole('button',{name:'Sync now'}));
    await waitFor(()=>expect(screen.getByLabelText('Sync status').textContent).toContain('Checking changes and files'));
    expect((screen.getByRole('button',{name:'Sync now'}) as HTMLButtonElement).disabled).toBe(true);
    await act(async()=>{release();});
    await waitFor(()=>expect(screen.getByLabelText('Sync status').textContent).toContain('Nook will retry'));
    expect(screen.queryByText('private transport detail')).toBeNull();
    await waitFor(()=>expect((screen.getByRole('button',{name:'Sync now'}) as HTMLButtonElement).disabled).toBe(false));
    fail=false;fireEvent.click(screen.getByRole('button',{name:'Sync now'}));
    await waitFor(()=>expect(screen.getByLabelText('Sync status').textContent).toContain('Last checked'));
    expect(screen.queryByRole('alert')).toBeNull();
  } finally {cleanup();await accounts.prepareSignOut();await db.delete();}
});

test('actual file attempt exposes upload progress and safe failed-file counts, then clears on disconnect',async()=>{
  const db=new NookDatabase(crypto.randomUUID());let remote:import('../../../../packages/schemas/src').Entity[]=[];
  let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});
  let late:((bytes:number)=>void)|undefined;
  const accounts=new AccountSession(new Repository(db,'local:files','client'),()=>({push:async record=>record,pull:async()=>remote,originals:{
    upload:async(_record,_bytes,_signal,progress)=>{late=progress;progress?.(50);await gate;throw new Error('private detail');},download:async()=>new Uint8Array(),remove:async()=>{},
  }}));
  try {
    await accounts.activate('alice');const repo=accounts.getSnapshot().repository;
    const capture=await repo.create('capture',{body:'Photo',captureType:'image',attachmentIds:[]});
    const file=await repo.create('attachment',{filename:'photo.png',mimeType:'image/png',size:100,ownerId:capture.id});
    await db.files.put({accountId:'alice',id:file.id,bytes:new Uint8Array(100)});remote=[capture,file];
    const auth=new AuthSession(accounts,{current:()=>({uid:'alice',email:'alice@example.test'}),ready:async()=>{},observe:()=>()=>{},beforeChange:()=>()=>{},signIn:async()=>{},register:async()=>{},signOut:async()=>{}});
    render(<AuthContext.Provider value={auth}><RepositoryContext.Provider value={repo}><AccountControls/></RepositoryContext.Provider></AuthContext.Provider>);
    fireEvent.click(screen.getByRole('button',{name:'Sync now'}));
    await waitFor(()=>expect(screen.getByLabelText('File sync progress').textContent).toContain('Uploading photo.png (50%)'));
    await act(async()=>{release();});
    await waitFor(()=>expect(screen.getByLabelText('File sync progress').textContent).toBe('1 of 1 files checked · 1 file needs retry'));
    await act(async()=>{await accounts.prepareSignOut();late?.(75);});
    expect(screen.queryByLabelText('File sync progress')).toBeNull();
  } finally {release();cleanup();await accounts.prepareSignOut();await db.delete();}
});
