import 'fake-indexeddb/auto';
import { afterEach, expect, test } from 'vitest';
import { AccountSession } from './account-session';
import { AuthSession, type AuthIdentity, type AuthPort } from './auth-session';
import { NookDatabase, Repository } from './repository';

class AuthFake implements AuthPort {
  identity: AuthIdentity | null = null;
  fail = false;
  listeners = new Set<() => void>();
  barriers = new Set<() => Promise<void>>();
  current() {return this.identity;}
  async ready() {}
  observe(listener:()=>void) {this.listeners.add(listener);return ()=>{this.listeners.delete(listener);};}
  beforeChange(callback:()=>Promise<void>) {this.barriers.add(callback);return ()=>{this.barriers.delete(callback);};}
  async change(uid:string|null) {
    for(const callback of this.barriers) await callback();
    if(this.fail) throw new Error('Auth unavailable');
    this.identity=uid?{uid,email:uid+'@example.com'}:null;
    for(const listener of this.listeners) listener();
  }
  signIn(email:string) {return this.change(email.split('@')[0]);}
  register(email:string) {return this.signIn(email);}
  signOut() {return this.change(null);}
}
const databases:NookDatabase[]=[];
function setup() {
  const db=new NookDatabase(crypto.randomUUID());databases.push(db);
  const guest=new Repository(db,'local:device','client');
  const accounts=new AccountSession(guest,()=>({push:async record=>record,pull:async()=>[]}));
  const port=new AuthFake();const auth=new AuthSession(accounts,port);
  return {guest,accounts,port,auth};
}
afterEach(async()=>{await Promise.all(databases.splice(0).map(db=>db.delete()));});
test('restored authentication isolates guest data until explicit sign-in',async()=>{
  const {guest,accounts,port,auth}=setup();await guest.capture('Local private');
  port.identity={uid:'alice',email:'alice@example.com'};await auth.initialize();
  expect(accounts.getSnapshot().repository.accountId).toBe('alice');
  expect(await accounts.getSnapshot().repository.list('capture')).toEqual([]);
  expect(await guest.list('capture')).toHaveLength(1);await auth.dispose();
});
test('explicit registration merges guest data and sign-out returns to a separate local namespace',async()=>{
  const {guest,accounts,auth}=setup();const record=await guest.capture('Before account');
  await auth.signIn('alice@example.com','secret',true);
  expect((await accounts.getSnapshot().repository.list('capture'))[0].id).toBe(record.id);
  await auth.signOut();expect(accounts.getSnapshot().repository.accountId).toBe('local:device');
  expect(await guest.list('capture')).toEqual([]);await auth.dispose();
});
test('failed authentication preserves guest data and failed sign-out resumes sync',async()=>{
  const {guest,accounts,port,auth}=setup();await guest.capture('Keep local');
  port.fail=true;await expect(auth.signIn('alice@example.com','secret')).rejects.toThrow('Auth unavailable');
  expect(await guest.list('capture')).toHaveLength(1);
  port.fail=false;await auth.signIn('alice@example.com','secret');
  port.fail=true;await expect(auth.signOut()).rejects.toThrow('Auth unavailable');
  expect(accounts.getSnapshot().repository.accountId).toBe('alice');
  await accounts.sync();expect(await guest.db.outbox.count()).toBe(0);await auth.dispose();
});
