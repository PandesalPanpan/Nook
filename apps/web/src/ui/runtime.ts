import { NookDatabase, Repository } from '../data/repository';
import { createContext, useContext } from 'react';

function identity(key: string): string {
  const previous = localStorage.getItem(key); if (previous) return previous;
  const value = crypto.randomUUID(); localStorage.setItem(key, value); return value;
}
export const db = new NookDatabase();
export const clientId = identity('nook-client');
export const guestAccount = `local:${identity('nook-guest')}`;
export const repository = new Repository(db, guestAccount, clientId);
export const RepositoryContext = createContext(repository);
export function useRepository(): Repository {return useContext(RepositoryContext);}
export function localDate(date = new Date()): string {
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
}
export function displayDate(date: string): string { return new Date(`${date}T12:00:00`).toLocaleDateString('en', {month:'short',day:'numeric'}); }
