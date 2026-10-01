import { useSyncExternalStore } from 'react';
import { AccountSession } from './data/account-session';
import { AuthSession } from './data/auth-session';
import { firebaseConfiguration } from './data/firebase-config';
import { SyncScheduler } from './data/sync-scheduler';
import { repository } from './ui/runtime';
import { App } from './ui/App';
import { SessionApp } from './ui/SessionApp';

type BootState = {state:'local'} | {state:'loading'} | {state:'ready';accounts:AccountSession;auth:AuthSession} | {state:'error';message:string};
let snapshot:BootState={state:'local'};
const listeners=new Set<()=>void>();
function publish(next:BootState) {snapshot=next;for(const listener of listeners)listener();}
const subscribe=(listener:()=>void)=>{listeners.add(listener);return ()=>{listeners.delete(listener);};};
const getSnapshot=()=>snapshot;
let initialized=false;
export async function initializeRuntime(environment:Record<string,string|undefined>) {
  if(initialized)return;initialized=true;
  try {
    const configuration=firebaseConfiguration(environment);
    if(!configuration)return;
    publish({state:'loading'});
    // Local-only startup does not download or initialize Firebase.
    const [{initializeNookFirebase,FirebaseTransport},{firebaseAuthPort}]=await Promise.all([import('./data/firebase'),import('./data/firebase-auth')]);
    const services=initializeNookFirebase(configuration.options,configuration.emulatorHost);
    const accounts=new AccountSession(repository,uid=>new FirebaseTransport(services.firestore,uid,services.storage));
    const auth=new AuthSession(accounts,firebaseAuthPort(services.auth));
    await auth.initialize();
    new SyncScheduler(accounts,window).start();
    publish({state:'ready',accounts,auth});
  }catch(error){publish({state:'error',message:error instanceof Error?error.message:'Cloud setup could not start'});}
}
export function Bootstrap() {
  const current=useSyncExternalStore(subscribe,getSnapshot);
  if(current.state==='loading')return <main role="status">Opening your Nook…</main>;
  if(current.state==='ready')return <SessionApp accounts={current.accounts} auth={current.auth}/>;
  if(current.state==='error')return <><p role="alert">{current.message}</p><App/></>;
  return <App/>;
}
