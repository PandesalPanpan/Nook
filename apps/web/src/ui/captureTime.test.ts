import {expect,test} from 'vitest';
import {formatCaptureTime} from './captureTime';

test('capture times stay relative for recent thoughts and show date plus time for older ones',()=>{
  const now=Date.parse('2026-10-05T12:00:00Z');
  expect(formatCaptureTime(now-30_000,now)).toBe('just now');
  expect(formatCaptureTime(now-45*60_000,now)).toBe('45 minutes ago');
  const oldCapture=new Date(now-2*60*60_000);
  expect(formatCaptureTime(oldCapture.getTime(),now)).toBe(`${oldCapture.toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'})} · ${oldCapture.toLocaleTimeString(undefined,{hour:'numeric',minute:'2-digit'})}`);
});
