import { Repository } from './repository';
import { SyncEngine, type SyncTransport } from './sync';
import type {FileSyncProgress} from './sync-progress';

export interface SessionSnapshot {
  repository: Repository;
  changing: boolean;
  error?: string;
  syncing?: boolean;
  syncError?: string;
  lastSyncAt?: number;
  files?:FileSyncProgress;
}

/** Owns the active namespace. UI must remount editors when repository.accountId changes. */
export class AccountSession {
  private snapshot: SessionSnapshot;
  private listeners = new Set<() => void>();
  private engine?: SyncEngine;
  private transitions: Promise<void> = Promise.resolve();
  constructor(private readonly guest: Repository, private readonly transport: (accountId: string) => SyncTransport) {
    if (!guest.accountId.startsWith('local:')) throw new Error('Guest repository required');
    this.snapshot = {repository: guest, changing: false};
  }
  getSnapshot = (): SessionSnapshot => this.snapshot;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {this.listeners.delete(listener);};
  };
  private publish(snapshot: SessionSnapshot) {
    this.snapshot = snapshot;
    for (const listener of this.listeners) listener();
  }
  private enqueue(action: () => Promise<void>): Promise<void> {
    const transition = this.transitions.then(action);
    this.transitions = transition.catch(() => undefined);
    return transition;
  }
  /** Merge only during an explicit guest sign-in, never while changing cloud accounts. */
  activate(accountId: string | null, mergeGuest = false): Promise<void> {
    return this.enqueue(async () => {
      const previous = this.snapshot.repository;
      const target = accountId ?? this.guest.accountId;
      if (accountId !== null && (!accountId || accountId.startsWith('local:'))) throw new Error('Cloud account required');
      if (target === previous.accountId && (accountId === null || this.engine)) return;
      this.publish({repository: previous, changing: true});
      try {
        await this.engine?.stop();
        this.engine = undefined;
        // Validate the cloud adapter before moving any local records.
        const transport = accountId === null ? undefined : this.transport(accountId);
        const next = accountId === null ? this.guest
          : mergeGuest && previous.accountId === this.guest.accountId
            ? await this.guest.mergeIntoAccount(accountId)
            : new Repository(this.guest.db, accountId, this.guest.clientId);
        if(transport) {
          const engine=new SyncEngine(next,transport);
          engine.observeProgress(files=>{if(this.engine===engine && !this.snapshot.changing)this.publish({...this.snapshot,files});});
          this.engine=engine;
        }
        this.publish({repository: next, changing: false});
      } catch (error) {
        // Never resume a worker for an identity that may already have signed out.
        this.publish({repository: previous, changing: false, error: error instanceof Error ? error.message : 'Could not switch accounts'});
        throw error;
      }
    });
  }
  /** Call before revoking credentials. Data and pending operations stay on-device. */
  prepareSignOut(): Promise<void> {
    return this.enqueue(async () => {
      await this.engine?.stop();
      this.engine = undefined;
      this.publish({...this.snapshot, syncing: false, syncError: undefined, lastSyncAt: undefined,files:undefined});
    });
  }
  async sync(): Promise<void> {
    await this.transitions;
    if (this.snapshot.changing) return;
    const engine = this.engine;
    if (!engine) return;
    this.publish({...this.snapshot, syncing: true, syncError: undefined,files:this.snapshot.syncing?this.snapshot.files:undefined});
    try {
      await engine.sync();
      if (this.engine === engine && !this.snapshot.changing) this.publish({...this.snapshot, syncing: false, lastSyncAt: Date.now()});
    } catch (error) {
      if (this.engine === engine && !this.snapshot.changing) this.publish({...this.snapshot, syncing: false,
        syncError: 'Some changes or files could not sync. Your saved data stays on this device; Nook will retry.'});
      throw error;
    }
  }
}
