import { useSyncExternalStore } from 'react';
import type { AccountSession } from '../data/account-session';
import { App } from './App';
import { RepositoryContext } from './runtime';
import type {AuthSession} from '../data/auth-session';
import {AuthContext} from './AccountControls';

/** A namespace change discards navigation, drafts, live queries and capture dialogs. */
export function SessionApp({accounts,auth}: {accounts:AccountSession;auth?:AuthSession}) {
  const snapshot=useSyncExternalStore(accounts.subscribe,accounts.getSnapshot);
  if(snapshot.changing) return <main role="status">Switching accounts…</main>;
  return <AuthContext.Provider value={auth??null}><RepositoryContext.Provider value={snapshot.repository}>
    {snapshot.error && <p role="alert">{snapshot.error}</p>}
    <App key={snapshot.repository.accountId}/>
  </RepositoryContext.Provider></AuthContext.Provider>;
}
