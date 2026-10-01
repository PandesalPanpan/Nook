import {expect,test} from 'vitest';
import {firebaseConfiguration} from './firebase-config';
test('local-only mode needs no configuration and partial cloud config is rejected',()=>{
  expect(firebaseConfiguration({})).toBeUndefined();
  expect(()=>firebaseConfiguration({VITE_FIREBASE_PROJECT_ID:'nook'})).toThrow('requires');
});
test('demo projects require explicit emulator routing',()=>{
  const environment={VITE_FIREBASE_PROJECT_ID:'demo-nook',VITE_FIREBASE_API_KEY:'demo-nook',VITE_FIREBASE_APP_ID:'demo-nook'};
  expect(()=>firebaseConfiguration(environment)).toThrow('emulator host');
  expect(firebaseConfiguration({...environment,VITE_FIREBASE_EMULATOR_HOST:'127.0.0.1'})).toMatchObject({emulatorHost:'127.0.0.1',options:{projectId:'demo-nook'}});
});
