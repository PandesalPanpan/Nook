// @vitest-environment jsdom
import React from 'react';
import 'fake-indexeddb/auto';
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,test} from 'vitest';
import {AccountSession} from '../data/account-session';
import {NookDatabase,Repository} from '../data/repository';
import {SessionApp} from './SessionApp';

const databases:NookDatabase[]=[];
afterEach(async()=>{cleanup();localStorage.clear();await Promise.all(databases.splice(0).map(db=>db.delete()));});
test('account changes reset an open editor and capture dialog without exposing the prior namespace',async()=>{
  // jsdom has no native dialog implementation; browser flows exercise showModal.
  HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
  HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
  localStorage.setItem('nook-onboarded','yes');
  const db=new NookDatabase(crypto.randomUUID());databases.push(db);
  const accounts=new AccountSession(new Repository(db,'local:test','client'),()=>({push:async record=>record,pull:async()=>[]}));
  await accounts.activate('alice');const alice=accounts.getSnapshot().repository;
  const project=await alice.create('project',{title:'Alice private garden',outcome:'Original',progress:0});
  render(<SessionApp accounts={accounts}/>);
  fireEvent.click(screen.getByRole('button',{name:'Projects'}));
  fireEvent.click(await screen.findByRole('button',{name:/Alice private garden/}));
  fireEvent.change(screen.getByRole('textbox',{name:'Outcome'}),{target:{value:'Unsaved Alice draft'}});
  fireEvent.click(screen.getByRole('button',{name:'Quick capture'}));
  fireEvent.change(screen.getByRole('textbox',{name:'Thought'}),{target:{value:'Alice capture draft'}});
  await act(()=>accounts.activate('bob'));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByRole('textbox',{name:'Outcome'})).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'Projects'}));
  await waitFor(()=>expect(screen.queryByRole('button',{name:/Alice private garden/})).toBeNull());
  expect(await accounts.getSnapshot().repository.list('project')).toEqual([]);
  expect((await alice.list('project'))[0].data.outcome).toBe('Original');
  await act(()=>accounts.activate('alice'));
  fireEvent.click(screen.getByRole('button',{name:'Projects'}));
  fireEvent.click(await screen.findByRole('button',{name:/Alice private garden/}));
  expect((screen.getByRole('textbox',{name:'Outcome'}) as HTMLTextAreaElement).value).toBe('Original');
  expect((await alice.list('project'))[0].id).toBe(project.id);
});
