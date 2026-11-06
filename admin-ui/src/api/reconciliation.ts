import request from '@/utils/request'

export type ReconciliationStatus='READY'|'RUNNING'|'DONE'|'DONE_WITH_ISSUES'|'FAILED'
export type CheckStatus='MATCH'|'MISMATCH'
export interface ReconciliationRun {
  id:string;scheduledDate:string;status:ReconciliationStatus;algorithmVersion:string
  checkedCount:number;matchedCount:number;mismatchedCount:number;attempts:number;totalClaims:number
  failureCount:number;manualRetries:number;retryAt:string;lastErrorCode?:string;origin:'SYSTEM'|'ADMIN';createdBy?:string
  createdAt:string;startedAt?:string;lastCheckedAt?:string;completedAt?:string
}
export interface ReconciliationDetail {
  ownerType:'PERSONAL'|'CLUB'|'SYSTEM';ownerId:string;currency:'card'|'diamond';status:CheckStatus
  expectedAvailable:string;actualAvailable:string;expectedFrozen:string;actualFrozen:string
  ledgerEntries:string;throughLedgerId:string;accountVersion:string;checkedAt:string
}
export interface ReconciliationQuery { start:string;end:string;status?:ReconciliationStatus;pageNum:number;pageSize:number }
interface OperationResult { code:number;data:{operationId:string;status:string;result:{run:ReconciliationRun}} }
export const reconciliationRuns=(params:ReconciliationQuery)=>request<unknown,{code:number;rows:ReconciliationRun[];total:number;asOf:string;processingEnabled:boolean}>({url:'/game-admin/reconciliation',method:'get',params})
export const reconciliationRun=(id:string,params:{status?:CheckStatus;pageNum:number;pageSize:number})=>request<unknown,{code:number;data:{run:ReconciliationRun;rows:ReconciliationDetail[];total:number;asOf:string}}>({url:`/game-admin/reconciliation/${id}`,method:'get',params})
export const createTodayReconciliation=(reason:string,key:string)=>request<unknown,OperationResult>({url:'/game-admin/reconciliation/today',method:'post',data:{reason},headers:{'Idempotency-Key':key,repeatSubmit:false}})
export const retryReconciliation=(id:string,reason:string,key:string)=>request<unknown,OperationResult>({url:`/game-admin/reconciliation/${id}/retry`,method:'post',data:{reason},headers:{'Idempotency-Key':key,repeatSubmit:false}})
