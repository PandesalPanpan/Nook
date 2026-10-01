import { compareVersions, type Entity, type SyncOperation } from '../../../../packages/schemas/src/index';
import { Repository } from './repository';
import Dexie from 'dexie';
import type {FileSyncProgress} from './sync-progress';

export interface SyncTransport {
  /** Atomically retain the greater version on the server, returning the winner. */
  push(record: Entity, signal: AbortSignal): Promise<Entity>;
  pull(accountId: string, signal: AbortSignal): Promise<Entity[]>;
  originals?: {
    /** Stable backend identity opts into durable successful-transfer caching. */
    cacheKey?: string;
    upload(record: Entity<'attachment'>, bytes: Uint8Array, signal: AbortSignal, progress?:(transferred:number)=>void): Promise<void>;
    download(record: Entity<'attachment'>, signal: AbortSignal): Promise<Uint8Array>;
    remove(record: Entity<'attachment'>, signal: AbortSignal): Promise<void>;
  };
}
function sameVersion(a: Entity, b: Entity): boolean { return compareVersions(a, b) === 0; }
const ORIGINAL_RECHECK_MS = 15 * 60 * 1000;

/** SDK requests may keep retrying offline. Fence their results without waiting for them. */
async function cancellable<T>(work: () => Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) throw new DOMException('Sync stopped','AbortError');
  let cancel!: () => void;
  const stopped = new Promise<never>((_,reject) => {
    cancel=()=>reject(new DOMException('Sync stopped','AbortError'));
    signal.addEventListener('abort',cancel,{once:true});
  });
  try {return await Promise.race([work(),stopped]);}
  finally {signal.removeEventListener('abort',cancel);}
}

/** One worker per active account. Stopping fences late SDK results before sign-out. */
export class SyncEngine {
  private controller = new AbortController();
  private running?: Promise<void>;
  private progressObserver?:(progress:FileSyncProgress)=>void;
  constructor(private readonly repository: Repository, private readonly transport: SyncTransport, private readonly now = Date.now) {}
  observeProgress(observer:(progress:FileSyncProgress)=>void):this {this.progressObserver=observer;return this;}
  sync(): Promise<void> {
    if (this.controller.signal.aborted) return Promise.resolve();
    if (!this.running) {
      this.running = this.run().finally(() => { this.running = undefined; });
    }
    return this.running;
  }
  async stop(): Promise<void> {
    this.controller.abort();
    // A failed pull is already reported to its caller; it must not prevent sign-out.
    await this.running?.catch(() => undefined);
    this.progressObserver=undefined;
  }
  private async run(): Promise<void> {
    const {db, accountId} = this.repository;
    if (accountId.startsWith('local:')) return;
    const signal = this.controller.signal;
    const operations = await db.outbox.where('accountId').equals(accountId).filter(op => op.nextAttemptAt <= this.now()).toArray();
    for (const operation of operations) {
      if (signal.aborted) return;
      try {
        const winner = await cancellable(()=>this.transport.push(operation.record, signal),signal);
        if (signal.aborted) return;
        await this.repository.receive(winner);
        await db.transaction('rw', db.outbox, async () => {
          const current = await db.outbox.get(operation.id);
          // An edit made during the network request must remain queued.
          if (current && sameVersion(current.record, operation.record)) await db.outbox.delete(operation.id);
        });
      } catch {
        if (signal.aborted) return;
        await this.retry(operation);
      }
    }
    if (signal.aborted) return;
    // Pull failure must not undo successful local writes or push acknowledgements.
    let remote: Entity[];
    try {remote = await cancellable(()=>this.transport.pull(accountId, signal),signal);}
    catch(error) {if(signal.aborted)return;throw error;}
    for (const record of remote) {
      if (signal.aborted) return;
      await this.repository.receive(record);
    }
    // Persisted attachment records are the retry ledger, including after metadata ACK.
    // A failed file must not prevent unrelated records or originals from syncing.
    if (this.transport.originals) {
      let failure: unknown;
      const cloud=new Map(remote.map(record=>[record.id,record]));
      const attachments = (await db.records.where('[accountId+kind]').equals([accountId,'attachment']).toArray())
        .filter((record):record is Entity<'attachment'>=>{
          if(record.kind!=='attachment')return false;
          const retained=cloud.get(record.id),parent=cloud.get(record.data.ownerId);
          return !!retained && sameVersion(retained,record) && (record.deleted || !!parent && !parent.deleted);
        });
      let progress:FileSyncProgress={total:attachments.length,checked:0,failed:0,phase:'checking'};
      const publish=(value:FileSyncProgress)=>{if(!signal.aborted)this.progressObserver?.(value);};
      publish(progress);
      for (const record of attachments) {
        if (signal.aborted) return;
        // A local capture/edit committed after this run's outbox snapshot belongs
        // to the next run. Its Storage rules require metadata already on the server.
        progress={...progress,phase:'checking',filename:record.data.filename};publish(progress);
        let fileActive=true;
        try {
          const scope = this.transport.originals.cacheKey;
          const version = JSON.stringify(record);
          const revisionOf = async () => {
            const keys = await db.files.where('[accountId+id+revision]').between([accountId,record.id,Dexie.minKey],[accountId,record.id,Dexie.maxKey]).keys();
            return keys.length ? String((keys[0] as unknown as string[])[2]) : '';
          };
          const revision = scope ? await revisionOf() : '';
          let acknowledgedRevision = revision;
          const previous = scope ? await db.transfers.get([accountId,record.id]) : undefined;
          const age = previous ? this.now() - previous.checkedAt : -1;
          if (scope && previous && previous.scope === scope && previous.version === version && previous.revision === revision && age >= 0 && age < ORIGINAL_RECHECK_MS && (record.deleted || revision)) continue;
          const original = await db.files.get([accountId,record.id]);
          if (record.deleted) {
            progress={...progress,phase:'removing'};publish(progress);
            await cancellable(()=>this.transport.originals!.remove(record,signal),signal);
          } else if (original) {
            progress={...progress,phase:'uploading',transferred:0,size:record.data.size};publish(progress);
            const base=progress;let lastPercent=0;
            await cancellable(()=>this.transport.originals!.upload(record,original.bytes,signal,transferred=>{
              const bytes=Math.max(0,Math.min(record.data.size,transferred));
              const percent=record.data.size?Math.floor(bytes/record.data.size*100):100;
              // Bound UI and background status updates; never publish stale SDK callbacks.
              if(fileActive && percent>=lastPercent+5){lastPercent=percent;publish({...base,transferred:bytes});}
            }),signal);
          }
          else {
            progress={...progress,phase:'downloading',size:record.data.size};publish(progress);
            const bytes = await cancellable(()=>this.transport.originals!.download(record,signal),signal);
            if (signal.aborted) return;
            if (bytes.length !== record.data.size) throw new Error('Attachment size mismatch');
            await db.transaction('rw',db.records,db.files,async()=>{
              const current=await db.records.get([accountId,record.id]);
              if(current?.kind==='attachment' && !current.deleted && sameVersion(current,record) && !await db.files.get([accountId,record.id])) {
                await db.files.put({accountId,id:record.id,bytes});
                if (scope) acknowledgedRevision = await revisionOf();
              }
            });
          }
          if (scope && !signal.aborted) await db.transaction('rw',db.records,db.files,db.transfers,async()=>{
            const current = await db.records.get([accountId,record.id]);
            const currentRevision = await revisionOf();
            // A concurrent bytes replacement must not inherit an old upload acknowledgement.
            if (current && JSON.stringify(current) === version && (record.deleted || (currentRevision && currentRevision === acknowledgedRevision)))
              await db.transfers.put({accountId,id:record.id,scope,version,revision:currentRevision,checkedAt:this.now()});
          });
        } catch(error) {if(signal.aborted)return;failure=error;progress={...progress,failed:progress.failed+1};}
        finally {
          fileActive=false;
          progress={total:progress.total,checked:progress.checked+1,failed:progress.failed,phase:'checking'};publish(progress);
        }
      }
      if(failure)throw failure;
    }
  }
  private async retry(operation: SyncOperation): Promise<void> {
    await this.repository.db.transaction('rw', this.repository.db.outbox, async () => {
      const current = await this.repository.db.outbox.get(operation.id);
      if (!current || !sameVersion(current.record, operation.record)) return;
      const attempts = current.attempts + 1;
      const delay = Math.min(300_000, 1_000 * 2 ** Math.min(attempts - 1, 9));
      await this.repository.db.outbox.put({...current, attempts, nextAttemptAt: this.now() + delay});
    });
  }
}
