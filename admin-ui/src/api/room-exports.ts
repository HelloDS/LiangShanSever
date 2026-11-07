import request from '@/utils/request'
import type { RoomQuery } from './rooms'
export interface RoomExportJob {id:string;status:'READY'|'RUNNING'|'DONE'|'FAILED'|'EXPIRED';attempts:number;rowCount:number;fileBytes:number;createdAt:string;completedAt?:string;expiresAt?:string;lastErrorCode?:string}
export const roomExportJobs=()=>request<unknown,{code:number;data:{jobs:{rows:RoomExportJob[];asOf:string};generationEnabled:boolean}}>({url:'/game-admin/room-exports',method:'get'})
export const createRoomExport=(query:RoomQuery,reason:string,key:string)=>request<unknown,{code:number;data:{result:{jobId:string;status:string}}}>({url:'/game-admin/room-exports',method:'post',data:{query:{roomCode:query.roomCode,ownerUid:query.ownerUid,creatorUid:query.creatorUid,status:query.status,start:query.start,end:query.end},reason},headers:{'Idempotency-Key':key}})
export async function downloadRoomExport(id:string){
  const blob=await request<unknown,Blob>({url:`/game-admin/room-exports/${id}/download`,method:'get',responseType:'blob'})
  if(!blob.type.toLowerCase().startsWith('text/csv'))throw Error('文件未生成、已过期或下载授权已撤销。')
  const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`rooms-${id}.csv`;document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)
}
