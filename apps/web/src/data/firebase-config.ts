export function firebaseConfiguration(environment: Record<string,string|undefined>) {
  const apiKey=environment.VITE_FIREBASE_API_KEY;
  const projectId=environment.VITE_FIREBASE_PROJECT_ID;
  const appId=environment.VITE_FIREBASE_APP_ID;
  if (!apiKey && !projectId && !appId) return undefined;
  if (!apiKey || !projectId || !appId) throw new Error('Firebase configuration requires API key, project ID and app ID');
  const emulatorHost=environment.VITE_FIREBASE_EMULATOR_HOST || undefined;
  if (projectId.startsWith('demo-') && !emulatorHost) throw new Error('Demo Firebase projects require an emulator host');
  return {options:{apiKey,projectId,appId,authDomain:environment.VITE_FIREBASE_AUTH_DOMAIN,storageBucket:environment.VITE_FIREBASE_STORAGE_BUCKET},emulatorHost};
}
