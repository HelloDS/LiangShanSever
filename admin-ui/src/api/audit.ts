import request from '@/utils/request'

export interface AuditRecord {
  id:string;operatorId:string;action:string;target:string;reason:string;createdAt:string
  operationId?:string;operationStatus?:string;completedAt?:string
  before?:Record<string,string|boolean>;after?:Record<string,string|boolean>;summaryAvailable?:boolean
}
export interface AuditQuery {operatorId?:string;action?:string;target?:string;operationId?:string;start:string;end:string;pageNum:number;pageSize:number}
export const auditRecords=(params:AuditQuery)=>request<unknown,{code:number;rows:AuditRecord[];total:number;asOf:string}>({url:'/game-admin/audit',method:'get',params})
export const auditRecord=(id:string)=>request<unknown,{code:number;data:{record:AuditRecord;asOf:string}}>({url:`/game-admin/audit/${id}`,method:'get'})
