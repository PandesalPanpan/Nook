import { beforeAuthStateChanged, createUserWithEmailAndPassword, GoogleAuthProvider, onAuthStateChanged, signInWithEmailAndPassword, signInWithPopup, signOut, type Auth } from 'firebase/auth';
import type { AuthPort } from './auth-session';

export function firebaseAuthPort(auth: Auth): AuthPort {
  return {
    current: () => auth.currentUser ? {uid:auth.currentUser.uid,email:auth.currentUser.email} : null,
    ready: () => auth.authStateReady(),
    observe: listener => onAuthStateChanged(auth,listener),
    beforeChange: callback => beforeAuthStateChanged(auth,callback),
    signIn: async(email,password) => {await signInWithEmailAndPassword(auth,email,password);},
    register: async(email,password) => {await createUserWithEmailAndPassword(auth,email,password);},
    signOut: () => signOut(auth),
    google: async()=>{await signInWithPopup(auth,new GoogleAuthProvider());},
  };
}
