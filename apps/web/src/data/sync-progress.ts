export interface FileSyncProgress {
  total:number;
  checked:number;
  failed:number;
  phase:'checking'|'uploading'|'downloading'|'removing';
  filename?:string;
  transferred?:number;
  size?:number;
}

/** Counts include cached integrity checks and deletion cleanup, not only byte transfers. */
export function fileSyncProgressText(progress:FileSyncProgress,active:boolean):string {
  const count=`${progress.checked} of ${progress.total} files checked`;
  const retry=progress.failed?` · ${progress.failed} ${progress.failed===1?'file needs':'files need'} retry`:'';
  if(!active || !progress.filename) return count+retry;
  const action={checking:'Checking',uploading:'Uploading',downloading:'Downloading',removing:'Removing'}[progress.phase];
  const percent=progress.phase==='uploading' && progress.transferred!==undefined && progress.size!==undefined && progress.size>0
    ? ` (${Math.floor(Math.min(progress.transferred,progress.size)/progress.size*100)}%)`:'';
  return `${count}${retry} · ${action} ${progress.filename}${percent}`;
}
