import { AccountSession } from './account-session';

export interface AuthIdentity {uid: string; email: string | null}
export interface AuthPort {
  current(): AuthIdentity | null;
  ready(): Promise<void>;
  observe(listener: () => void): () => void;
  beforeChange(callback: () => Promise<void>): () => void;
  signIn(email: string, password: string): Promise<void>;
  register(email: string, password: string): Promise<void>;
  signOut(): Promise<void>;
  google?(): Promise<void>;
}

/** Credentials belong to Auth only; never persisted in records, backups or logs. */
export class AuthSession {
  private operations: Promise<void> = Promise.resolve();
  private explicitOperation = false;
  private unsubscribe?: () => void;
  private removeBarrier?: () => void;
  private initialization?: Promise<void>;
  constructor(readonly accounts: AccountSession, private readonly auth: AuthPort) {}
  identity() {return this.auth.current();}
  google(): Promise<void> {
    return this.enqueue(async()=>{
      await this.initialize();this.explicitOperation=true;
      try {
        if(!this.auth.google)throw new Error('Google sign-in is unavailable');
        await this.auth.google();const identity=this.auth.current();
        if(!identity)throw new Error('Sign-in did not establish an account');
        await this.accounts.activate(identity.uid,true);
      }finally{this.explicitOperation=false;await this.reconcile();}
    });
  }
  initialize(): Promise<void> {
    return this.initialization ??= this.start();
  }
  private async start() {
    this.removeBarrier = this.auth.beforeChange(() => this.accounts.prepareSignOut());
    await this.auth.ready();
    await this.accounts.activate(this.auth.current()?.uid ?? null);
    this.unsubscribe = this.auth.observe(() => {
      if (!this.explicitOperation) void this.enqueue(() => this.reconcile()).catch(() => undefined);
    });
  }
  private enqueue(operation: () => Promise<void>): Promise<void> {
    const result = this.operations.then(operation);
    this.operations = result.catch(() => undefined);
    return result;
  }
  private reconcile() {return this.accounts.activate(this.auth.current()?.uid ?? null);}
  signIn(email: string, password: string, createAccount = false): Promise<void> {
    return this.enqueue(async () => {
      await this.initialize();
      this.explicitOperation = true;
      try {
        if (createAccount) await this.auth.register(email.trim(),password);
        else await this.auth.signIn(email.trim(),password);
        const identity = this.auth.current();
        if (!identity) throw new Error('Sign-in did not establish an account');
        await this.accounts.activate(identity.uid,true);
      } finally {
        this.explicitOperation = false;
        // Also resumes the previous account if authentication failed after stopping it.
        await this.reconcile();
      }
    });
  }
  signOut(): Promise<void> {
    return this.enqueue(async () => {
      await this.initialize();this.explicitOperation = true;
      try {
        await this.accounts.prepareSignOut();
        await this.auth.signOut();
      } finally {
        this.explicitOperation = false;
        await this.reconcile();
      }
    });
  }
  async dispose() {
    await this.operations;
    this.unsubscribe?.();this.removeBarrier?.();
    await this.accounts.prepareSignOut();
  }
}
