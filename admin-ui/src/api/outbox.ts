import request from '@/utils/request'

export type TaskStatus = 'READY' | 'CLAIMED' | 'DONE' | 'DEAD'
export interface OutboxTask {
  id: string
  businessKey: string
  eventType: string
  status: TaskStatus
  attempts: number
  totalAttempts: number
  manualRetries: number
  retryAt: string
  leaseUntil?: string
  lastErrorCode?: string
  occurredAt: string
  completedAt?: string
}
export interface OutboxPage { code: number; rows: OutboxTask[]; total: number; asOf: string }
export interface OutboxBacklog { code: number; data: { asOf: string; rows: { status: TaskStatus; total: number; oldest: string }[] } }
export interface OutboxRetry { code: number; data: { operationId: string; status: string; result: { eventId: string; taskStatus: TaskStatus } } }
export const outboxTasks = (params: { status?: TaskStatus; start: string; end: string; pageNum: number; pageSize: number }) =>
  request<unknown, OutboxPage>({ url: '/game-admin/outbox', method: 'get', params })
export const outboxBacklog = () => request<unknown, OutboxBacklog>({ url: '/game-admin/outbox/backlog', method: 'get' })
export const outboxRetry = (id: string, reason: string, key: string) => request<unknown, OutboxRetry>({
  url: `/game-admin/outbox/${id}/retry`, method: 'post', data: { reason },
  headers: { 'Idempotency-Key': key, repeatSubmit: false }
})
