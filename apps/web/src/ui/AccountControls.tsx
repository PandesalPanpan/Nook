import {createContext,useContext,useState,useSyncExternalStore} from 'react';
import {useLiveQuery} from 'dexie-react-hooks';
import type {AuthSession} from '../data/auth-session';
import {useRepository} from './runtime';
import {fileSyncProgressText} from '../data/sync-progress';
const subscribeNothing=()=>()=>{};
const noSnapshot=()=>undefined;
export const AuthContext=createContext<AuthSession|null>(null);
export function AccountControls() {
  const auth=useContext(AuthContext);const repository=useRepository();
  const [email,setEmail]=useState('');const [password,setPassword]=useState('');
  const [emailOpen,setEmailOpen]=useState(false);const [register,setRegister]=useState(false);
  const [pending,setPending]=useState(false);const [error,setError]=useState('');
  const queued=useLiveQuery(()=>repository.db.outbox.where('accountId').equals(repository.accountId).count(),[repository],0);
  const sync=useSyncExternalStore(auth?.accounts.subscribe??subscribeNothing,auth?.accounts.getSnapshot??noSnapshot);
  const local=repository.accountId.startsWith('local:');
  async function run(action:()=>Promise<void>, failureMessage?:string) {
    setPending(true);setError('');
    try{await action();setPassword('');}catch(error){setError(failureMessage??(error instanceof Error?error.message:'Please try again'));}
    finally{setPending(false);}
  }
  return <section className="account-controls" aria-label="Account and sync">
    <h3>{local?'Keep Nook yours':'Everything is saved locally first.'}</h3>
    <p>{local?'Start offline. Sync only if you want it.':`${auth?.identity()?.email??'Signed in'} · ${queued} changes waiting to sync`}</p>
    {!local&&<p aria-live="polite" aria-label="Sync status">{sync?.syncing?'Checking changes and files…':sync?.syncError??(sync?.lastSyncAt?`Last checked ${new Date(sync.lastSyncAt).toLocaleTimeString()}.`:'Changes and files sync automatically when connected.')}</p>}
    {!local && !!sync?.files?.total && <p aria-live="polite" aria-label="File sync progress">{fileSyncProgressText(sync.files,!!sync.syncing)}</p>}
    {!auth?<p>Sync is unavailable on this installation. Everything still saves on this device.</p>:local?<>
      <div className="account-actions"><button className="primary" disabled={pending} onClick={()=>void run(()=>auth.google())}>Continue with Google</button><button disabled={pending} onClick={()=>setEmailOpen(!emailOpen)}>Use email</button></div>
      {emailOpen&&<form onSubmit={event=>{event.preventDefault();void run(()=>auth.signIn(email,password,register));}}>
        <label>Email<input type="email" autoComplete="email" required value={email} onChange={event=>setEmail(event.target.value)}/></label>
        <label>Password<input type="password" autoComplete={register?'new-password':'current-password'} required minLength={6} value={password} onChange={event=>setPassword(event.target.value)}/></label>
        <label className="check-label"><input type="checkbox" checked={register} onChange={event=>setRegister(event.target.checked)}/>Create a new account</label>
        <button className="primary" disabled={pending}>{pending?'Connecting…':register?'Create account':'Sign in'}</button>
      </form>}
      <div className="account-info"><strong>Turning sync on later is safe.</strong><p>Your local Nook becomes the starting copy; it isn’t replaced by an empty cloud.</p></div>
    </>:<><div className="account-actions"><button disabled={pending||sync?.syncing} onClick={()=>void run(()=>auth.accounts.sync(),'Sync could not finish. Nook will retry.')}>Sync now</button><button disabled={pending} onClick={()=>void run(()=>auth.signOut())}>Disconnect web</button></div><p className="muted small">Disconnecting keeps this account’s data and pending changes on this device. Local-only mode opens a separate space.</p></>}
    {error&&<p role="alert">{error}</p>}
  </section>;
}
