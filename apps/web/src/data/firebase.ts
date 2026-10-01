import { initializeApp, type FirebaseOptions } from 'firebase/app';
import { connectAuthEmulator, getAuth } from 'firebase/auth';
import { collection, connectFirestoreEmulator, doc, getDocsFromServer, getFirestore, runTransaction, type Firestore } from 'firebase/firestore';
import { connectStorageEmulator, getStorage, ref, uploadBytesResumable, getBytes, getMetadata, deleteObject, type FirebaseStorage } from 'firebase/storage';
import { compareVersions, type Entity } from '../../../../packages/schemas/src/index';
import { parseEntity } from '../../../../packages/schemas/src/validation';
import type { SyncTransport } from './sync';

export function initializeNookFirebase(options: FirebaseOptions, emulatorHost?: string) {
  const app = initializeApp(options, 'nook');
  const auth = getAuth(app); const firestore = getFirestore(app); const storage = getStorage(app);
  if (emulatorHost) {
    connectAuthEmulator(auth, `http://${emulatorHost}:9099`, {disableWarnings: true});
    connectFirestoreEmulator(firestore, emulatorHost, 8080);
    connectStorageEmulator(storage, emulatorHost, 9199);
  }
  return {app, auth, firestore, storage};
}
function checkCancelled(signal: AbortSignal) { if (signal.aborted) throw new DOMException('Sync stopped', 'AbortError'); }
/** Unified collection retains stable identity even if record kinds evolve later. */
export class FirebaseTransport implements SyncTransport {
  readonly originals: NonNullable<SyncTransport['originals']>;
  constructor(private readonly firestore: Firestore, private readonly accountId: string, storage?: FirebaseStorage) {
    const bucket=storage ?? getStorage(firestore.app);
    const reference=(record:Entity<'attachment'>)=>{
      if(record.accountId!==accountId)throw new Error('Account mismatch');
      // Filename and imported storagePath never control a cloud path.
      return ref(bucket,`users/${accountId}/attachments/${record.id}/original`);
    };
    const hash=async(bytes:Uint8Array)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new Uint8Array(bytes))),b=>b.toString(16).padStart(2,'0')).join('');
    const missing=(error:unknown)=>typeof error==='object' && error!==null && 'code' in error && error.code==='storage/object-not-found';
    this.originals={
      cacheKey: `firebase:${firestore.app.options.projectId}:${ref(bucket).bucket}`,
      upload:async(record,bytes,signal,progress)=>{
        checkCancelled(signal);if(bytes.length!==record.data.size)throw new Error('Attachment size mismatch');
        const target=reference(record),sha256=await hash(bytes);checkCancelled(signal);
        try {
          const metadata=await getMetadata(target);checkCancelled(signal);
          if(metadata.size!==bytes.length || metadata.customMetadata?.sha256!==sha256)throw new Error('Attachment identity has different original bytes');
          return;
        } catch(error) {if(!missing(error))throw error;}
        checkCancelled(signal);
        const task=uploadBytesResumable(target,bytes,{contentType:record.data.mimeType,customMetadata:{sha256}});
        const unsubscribe=task.on('state_changed',snapshot=>{if(!signal.aborted)progress?.(snapshot.bytesTransferred);});
        const cancel=()=>{task.cancel();};signal.addEventListener('abort',cancel,{once:true});
        try {if(signal.aborted)cancel();await task;checkCancelled(signal);}
        finally {unsubscribe();signal.removeEventListener('abort',cancel);}
      },
      download:async(record,signal)=>{
        checkCancelled(signal);const target=reference(record);
        const metadata=await getMetadata(target);checkCancelled(signal);
        if(metadata.size!==record.data.size || metadata.size>50*1024*1024)throw new Error('Attachment size mismatch');
        const bytes=new Uint8Array(await getBytes(target,50*1024*1024));checkCancelled(signal);
        if(bytes.length!==record.data.size || await hash(bytes)!==metadata.customMetadata?.sha256)throw new Error('Attachment integrity check failed');
        return bytes;
      },
      remove:async(record,signal)=>{
        checkCancelled(signal);try {await deleteObject(reference(record));}catch(error){if(!missing(error))throw error;}checkCancelled(signal);
      },
    };
  }
  async push(record: Entity, signal: AbortSignal): Promise<Entity> {
    checkCancelled(signal); parseEntity(record);
    if (record.accountId !== this.accountId) throw new Error('Account mismatch');
    const reference = doc(this.firestore, 'users', this.accountId, 'records', record.id);
    return runTransaction(this.firestore, async transaction => {
      checkCancelled(signal);
      const snapshot = await transaction.get(reference); checkCancelled(signal);
      const remote = snapshot.exists() ? parseEntity(snapshot.data()) : undefined;
      if (!remote || compareVersions(record, remote) > 0) {
        // JSON normalization omits optional undefined fields rejected by Firestore.
        transaction.set(reference, JSON.parse(JSON.stringify(record)));
        return record;
      }
      return remote;
    });
  }
  async pull(accountId: string, signal: AbortSignal): Promise<Entity[]> {
    checkCancelled(signal);
    if (accountId !== this.accountId) throw new Error('Account mismatch');
    const snapshot = await getDocsFromServer(collection(this.firestore, 'users', accountId, 'records'));
    checkCancelled(signal);
    return snapshot.docs.map(item => parseEntity(item.data()));
  }
}
