import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { deleteDoc, doc, getDoc, setDoc } from 'firebase/firestore';
import { ref, uploadBytes, getBytes, getMetadata } from 'firebase/storage';
import 'fake-indexeddb/auto';
import { NookDatabase, Repository } from '../apps/web/src/data/repository';
import { SyncEngine } from '../apps/web/src/data/sync';
import { FirebaseTransport, initializeNookFirebase } from '../apps/web/src/data/firebase';
import { signInAnonymously, signOut } from 'firebase/auth';
import { deleteApp } from 'firebase/app';
import { AccountSession } from '../apps/web/src/data/account-session';
import { AuthSession } from '../apps/web/src/data/auth-session';
import { firebaseAuthPort } from '../apps/web/src/data/firebase-auth';

let environment: RulesTestEnvironment;
beforeAll(async () => {
  environment = await initializeTestEnvironment({projectId: 'demo-nook',
    firestore: {host: '127.0.0.1', port: 8080, rules: await readFile('firebase/firestore.rules', 'utf8')},
    storage: {host: '127.0.0.1', port: 9199, rules: await readFile('firebase/storage.rules', 'utf8')},
  });
});
beforeEach(async () => { await environment.clearFirestore(); await environment.clearStorage(); });
afterAll(async () => { await environment?.cleanup(); });
function record() { return {id: 'note', accountId: 'alice', kind: 'note', data: {title: 'Private', body: 'Text', attachmentIds: []}, schemaVersion: 1, createdAt: 1, updatedAt: 1, clientId: 'a', deleted: false, archived: false}; }
test('resource contexts accept legacy and related records while rejecting malformed references',async()=>{
  const reference=doc(environment.authenticatedContext('alice').firestore(),'users/alice/records/resource');
  const resource={...record(),id:'resource',kind:'resource',data:{title:'Seed guide',description:'Reference'}};
  await assertSucceeds(setDoc(reference,resource));
  await assertSucceeds(setDoc(reference,{...resource,updatedAt:2,data:{...resource.data,projectId:'garden',areaId:'health'}}));
  await assertFails(setDoc(reference,{...resource,updatedAt:3,data:{...resource.data,projectId:'unsafe/path'}}));
  await assertFails(setDoc(reference,{...resource,updatedAt:3,data:{...resource.data,areaId:42}}));
});
test('only authenticated owner reads and writes its record tree', async () => {
  const alice = doc(environment.authenticatedContext('alice').firestore(), 'users/alice/records/note');
  await assertSucceeds(setDoc(alice, record()));
  await assertSucceeds(getDoc(alice));
  await assertFails(getDoc(doc(environment.authenticatedContext('bob').firestore(), alice.path)));
  await assertFails(setDoc(doc(environment.authenticatedContext('bob').firestore(), alice.path), record()));
  await assertFails(getDoc(doc(environment.unauthenticatedContext().firestore(), alice.path)));
});
test('malformed data and hidden credential fields are rejected', async () => {
  const reference = doc(environment.authenticatedContext('alice').firestore(), 'users/alice/records/note');
  await assertFails(setDoc(reference, {...record(), accountId: 'bob'}));
  await assertFails(setDoc(reference, {...record(), schemaVersion: 99}));
  await assertFails(setDoc(reference, {...record(), apiKey: 'must-not-sync'}));
  await assertFails(setDoc(reference, {...record(), data: {...record().data, apiKey: 'must-not-sync'}}));
  await assertFails(setDoc(reference, {...record(), updatedAt: -1}));
});
test('actual Firebase transport syncs offline captures, edits, deletion and guest merge', async () => {
  const a = new NookDatabase(crypto.randomUUID()); const b = new NookDatabase(crypto.randomUUID());
  const services = initializeNookFirebase({projectId: 'demo-nook', apiKey: 'demo-nook', appId: 'demo-nook', storageBucket: 'demo-nook.appspot.com'}, '127.0.0.1');
  try {
    const {user} = await signInAnonymously(services.auth);
    const guest = new Repository(a, 'local:guest', 'web-a');
    const captured = await guest.capture('Saved before sign-in');
    const alice = await guest.mergeIntoAccount(user.uid);
    const other = new Repository(b, user.uid, 'web-b');
    const transport = new FirebaseTransport(services.firestore, user.uid);
    await new SyncEngine(alice, transport).sync(); await new SyncEngine(other, transport).sync();
    expect((await other.list('capture'))[0].id).toBe(captured.id);
    await alice.update(captured.id, r => r.kind === 'capture' ? {...r, data: {...r.data, body: 'Edited offline'}} : r);
    await new SyncEngine(alice, transport).sync(); await new SyncEngine(other, transport).sync();
    expect((await other.list('capture'))[0].data).toMatchObject({body: 'Edited offline'});
    await alice.remove(captured.id); await new SyncEngine(alice, transport).sync();
    await other.update(captured.id, r => ({...r, updatedAt: Date.now() + 100_000}));
    await new SyncEngine(other, transport).sync();
    expect(await other.list('capture')).toEqual([]);
  } finally { await a.delete(); await b.delete(); await deleteApp(services.app); }
});
test('product sync transfers original bytes between databases and removes deleted originals',async()=>{
  const a=new NookDatabase(crypto.randomUUID()),b=new NookDatabase(crypto.randomUUID());
  const services=initializeNookFirebase({projectId:'demo-nook',apiKey:'demo-nook',appId:'demo-nook',storageBucket:'demo-nook.appspot.com'},'127.0.0.1');
  try {
    const {user}=await signInAnonymously(services.auth);
    const first=new Repository(a,user.uid,'file-a'),second=new Repository(b,user.uid,'file-b');
    const original=new Uint8Array([0,255,1,128,3]);
    const capture=await first.capture('Original photo','image',[new File([original],'../private.bin',{type:'application/octet-stream'})]);
    const id=capture.data.attachmentIds[0],transport=new FirebaseTransport(services.firestore,user.uid,services.storage);
    await new SyncEngine(first,transport).sync();await new SyncEngine(second,transport).sync();
    expect((await b.files.get([user.uid,id]))?.bytes).toEqual(original);
    await first.remove(capture.id);await new SyncEngine(first,transport).sync();await new SyncEngine(second,transport).sync();
    expect(await b.files.get([user.uid,id])).toBeUndefined();
    await environment.withSecurityRulesDisabled(async context=>{
      await expect(getMetadata(ref(context.storage(),`users/${user.uid}/attachments/${id}/original`))).rejects.toMatchObject({code:'storage/object-not-found'});
    });
  }finally {await a.delete();await b.delete();await deleteApp(services.app);}
});
test('actual Auth session registers, merges, signs out and switches between isolated accounts',async()=>{
  const db=new NookDatabase(crypto.randomUUID());
  const services=initializeNookFirebase({projectId:'demo-nook',apiKey:'demo-nook',appId:'demo-nook',storageBucket:'demo-nook.appspot.com'},'127.0.0.1');
  const guest=new Repository(db,'local:auth-flow','web-auth');
  const accounts=new AccountSession(guest,uid=>new FirebaseTransport(services.firestore,uid));
  const auth=new AuthSession(accounts,firebaseAuthPort(services.auth));
  const suffix=crypto.randomUUID();const aliceEmail=`alice-${suffix}@example.com`;const bobEmail=`bob-${suffix}@example.com`;
  try {
    // Previous tests use the same named app; Auth persistence survives deleteApp.
    await services.auth.authStateReady();await signOut(services.auth);
    const thought=await guest.capture('Alice initial local thought');
    await auth.signIn(aliceEmail,'test-only-password',true);
    const aliceId=services.auth.currentUser!.uid;
    expect(accounts.getSnapshot().repository.accountId).toBe(aliceId);
    await accounts.sync();
    expect((await getDoc(doc(services.firestore,'users',aliceId,'records',thought.id))).data()?.data.body).toBe('Alice initial local thought');
    await auth.signOut();expect(services.auth.currentUser).toBeNull();
    expect(accounts.getSnapshot().repository.accountId).toBe(guest.accountId);
    expect(await guest.list('capture')).toEqual([]);
    await guest.capture('Bob local thought');
    await auth.signIn(bobEmail,'test-only-password',true);await accounts.sync();
    expect((await accounts.getSnapshot().repository.list('capture'))[0].data.body).toBe('Bob local thought');
    await auth.signIn(aliceEmail,'test-only-password');await accounts.sync();
    expect(accounts.getSnapshot().repository.accountId).toBe(aliceId);
    expect((await accounts.getSnapshot().repository.list('capture')).map(r=>r.data.body)).toEqual(['Alice initial local thought']);
  } finally {await auth.dispose();await db.delete();await deleteApp(services.app);}
});

test('tombstones are durable and reject later-clock resurrection', async () => {
  const reference = doc(environment.authenticatedContext('alice').firestore(), 'users/alice/records/note');
  await assertSucceeds(setDoc(reference, record()));
  await assertSucceeds(setDoc(reference, {...record(), deleted: true, updatedAt: 2}));
  await assertFails(setDoc(reference, {...record(), updatedAt: 1000}));
  await assertFails(deleteDoc(reference));
  expect((await getDoc(reference)).data()?.deleted).toBe(true);
});
test('stale live versions cannot overwrite newer records', async () => {
  const reference = doc(environment.authenticatedContext('alice').firestore(), 'users/alice/records/note');
  await assertSucceeds(setDoc(reference, {...record(), updatedAt: 10, clientId: 'b'}));
  await assertFails(setDoc(reference, {...record(), updatedAt: 9}));
  await assertFails(setDoc(reference, {...record(), updatedAt: 10, clientId: 'a'}));
  await assertSucceeds(setDoc(reference, {...record(), updatedAt: 10, clientId: 'c'}));
});
test('attachments are isolated by authenticated owner', async () => {
  const firestore=environment.authenticatedContext('alice').firestore();
  await setDoc(doc(firestore,'users/alice/records/note'),record());
  const photo={...record(),id:'photo',kind:'attachment',data:{filename:'photo.png',mimeType:'image/png',size:3,ownerId:'note'}};
  await setDoc(doc(firestore,'users/alice/records/photo'),photo);
  const path = 'users/alice/attachments/photo/original';
  const alice = ref(environment.authenticatedContext('alice').storage(), path);
  const metadata={contentType:'image/png',customMetadata:{sha256:'a'.repeat(64)}};
  await assertSucceeds(uploadBytes(alice, new Uint8Array([1,2,3]), metadata));
  await assertSucceeds(getBytes(alice));
  await assertFails(getBytes(ref(environment.authenticatedContext('bob').storage(), path)));
  await assertFails(uploadBytes(ref(environment.unauthenticatedContext().storage(), path), new Uint8Array([1])));
  await assertFails(uploadBytes(alice,new Uint8Array([1,2,3]),{...metadata,customMetadata:{sha256:'b'.repeat(64)}}));
  await setDoc(doc(firestore,'users/alice/records/photo'),{...photo,deleted:true,updatedAt:2});
  await assertFails(uploadBytes(alice,new Uint8Array([1,2,3]),metadata));
  await assertFails(getBytes(alice));
  const secondPhoto={...photo,id:'photo2'};
  await setDoc(doc(firestore,'users/alice/records/photo2'),secondPhoto);
  const second=ref(environment.authenticatedContext('alice').storage(),'users/alice/attachments/photo2/original');
  await assertFails(uploadBytes(second,new Uint8Array([1,2]),metadata));
  await assertSucceeds(uploadBytes(second,new Uint8Array([1,2,3]),metadata));
  await setDoc(doc(firestore,'users/alice/records/note'),{...record(),deleted:true,updatedAt:2});
  // The owner tombstone blocks a late upload even before the child tombstone arrives.
  await assertFails(uploadBytes(second,new Uint8Array([1,2,3]),metadata));
  await assertFails(getBytes(second));
});
