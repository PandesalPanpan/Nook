export function formatCaptureTime(createdAt:number, now=Date.now()) {
  const elapsed=Math.max(0,now-createdAt);
  if(elapsed<60_000)return 'just now';
  if(elapsed<3_600_000){const minutes=Math.floor(elapsed/60_000);return `${minutes} ${minutes===1?'minute':'minutes'} ago`;}
  const date=new Date(createdAt);
  return `${date.toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'})} · ${date.toLocaleTimeString(undefined,{hour:'numeric',minute:'2-digit'})}`;
}
