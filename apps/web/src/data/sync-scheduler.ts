import {liveQuery,type Subscription} from 'dexie';
import {AccountSession} from './account-session';

/** Runs after local commits. Network failures leave retries in the durable outbox. */
export class SyncScheduler {
  private subscription?:Subscription;
  private unsubscribe?:()=>void;
  private timer?:ReturnType<typeof setInterval>;
  private namespace?:string;
  private stopped=true;
  private running=false;
  private again=false;
  constructor(private readonly accounts:AccountSession,private readonly events:EventTarget,private readonly interval=30_000) {}
  start() {
    if(!this.stopped)return;
    this.stopped=false;
    this.unsubscribe=this.accounts.subscribe(()=>this.watchAccount());
    this.events.addEventListener('online',this.request);
    this.timer=setInterval(this.request,this.interval);
    this.watchAccount();
  }
  private watchAccount() {
    const snapshot=this.accounts.getSnapshot();
    const namespace=snapshot.changing?undefined:snapshot.repository.accountId;
    if(namespace===this.namespace)return;
    this.namespace=namespace;this.subscription?.unsubscribe();this.subscription=undefined;
    if(!namespace || namespace.startsWith('local:'))return;
    const repository=snapshot.repository;
    let previous:string|undefined;
    this.subscription=liveQuery(()=>repository.db.outbox.where('accountId').equals(namespace).toArray()).subscribe({
      next:operations=>{
        // Retry counters don't trigger an immediate retry loop; the timer handles backoff.
        const signature=operations.map(op=>`${op.entityId}:${op.record.updatedAt}:${op.record.clientId}:${op.record.deleted}`).sort().join('|');
        if(signature!==previous){previous=signature;this.request();}
      },
      error:()=>{/* Local failure is reported by normal repository operations. */},
    });
    this.request();
  }
  private request=()=>{
    if(this.stopped || !this.namespace || this.namespace.startsWith('local:'))return;
    if(this.running){this.again=true;return;}
    this.running=true;
    void this.accounts.sync().catch(()=>undefined).finally(()=>{
      this.running=false;
      if(this.again){this.again=false;this.request();}
    });
  };
  async stop() {
    this.stopped=true;this.again=false;
    this.subscription?.unsubscribe();this.unsubscribe?.();
    if(this.timer!==undefined)clearInterval(this.timer);
    this.events.removeEventListener('online',this.request);
    await this.accounts.prepareSignOut();
  }
}
