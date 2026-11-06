import request from '@/utils/request'

export type RoomStatus='WAITING'|'PLAYING'|'ROUND_FINISHED'|'CLOSED'
export interface GameRoom {
  roomId:string;roomCode:string;gameId:string;ownerUid:string;creatorUid:string;payerUid?:string;clubId?:string
  status:RoomStatus;isTest:boolean;ruleVersion:string;createdAt:string;endedAt?:string;phase:string;roundNo:number
  version:string;snapshotSeq:string;engineVersion:string;playNumber?:string;participantCount:number
}
export interface RoomPlayer { uid:string;name:string;head:string;sex:number;seat:number;joinedAt:string }
export interface RoomQuery {roomCode?:string;ownerUid?:string;creatorUid?:string;status?:RoomStatus;start:string;end:string;pageNum:number;pageSize:number}
export const gameRooms=(params:RoomQuery)=>request<unknown,{code:number;rows:GameRoom[];total:number;asOf:string}>({url:'/game-admin/rooms',method:'get',params})
export const gameRoom=(id:string)=>request<unknown,{code:number;data:{room:GameRoom;players:RoomPlayer[];asOf:string}}>({url:`/game-admin/rooms/${id}`,method:'get'})
