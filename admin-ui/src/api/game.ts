import request from '@/utils/request'

export const dashboard = () => request({ url: '/game-admin/dashboard', method: 'get' })
export interface OnlineHistoryPoint {sampleMinute:string;observedAt:string;dataStatus:'AVAILABLE'|'REBUILDING'|'UNAVAILABLE';onlineCount:number|null}
export const onlineHistory = (date:string) => request<unknown,{code:number;data:{rows:OnlineHistoryPoint[];date:string;zone:string;samplingEnabled:boolean;asOf:string}}>({url:'/game-admin/online/history',method:'get',params:{date}})
export const players = (params: Record<string, unknown>) => request({ url: '/game-admin/players', method: 'get', params })
export const player = (uid: string) => request({ url: `/game-admin/players/${uid}`, method: 'get' })
export const online = (params: Record<string, unknown>) => request({ url: '/game-admin/online', method: 'get', params })
export const playerCommand = (uid: string, action: string, reason: string, key: string) => request({
  url: `/game-admin/players/${uid}/${action}`, method: 'post', data: { reason },
  headers: { 'Idempotency-Key': key, repeatSubmit: false }
})
export const notices = () => request({ url: '/game-admin/notices', method: 'get' })
export const noticeVersions = (id: string) => request({ url: `/game-admin/notices/${id}/versions`, method: 'get' })
export const noticeDraft = (data: unknown, key: string) => request({ url: '/game-admin/notices/drafts', method: 'post', data, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
export const noticeAction = (id: string, action: string, data: unknown, key: string) => request({ url: `/game-admin/notices/${id}/${action}`, method: 'post', data, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
export const noticePreview = (data: unknown) => request({ url: '/game-admin/notices/preview', method: 'post', data, responseType: 'blob', headers: { repeatSubmit: false } })
export const reports = (params: Record<string, unknown>) => request({ url: '/game-admin/reports', method: 'get', params })
export const reportProcess = (id: string, data: unknown, key: string) => request({ url: `/game-admin/reports/${id}/process`, method: 'post', data, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
export const reportContact = (id: string, reason: string) => request({ url: `/game-admin/reports/${id}/contact`, method: 'post', data: { reason }, headers: { repeatSubmit: false } })
export const clubs = (params: Record<string, unknown>) => request({ url: '/game-admin/clubs', method: 'get', params })
export const club = (id: string) => request({ url: `/game-admin/clubs/${id}`, method: 'get' })
export const clubMembers = (id: string, params: Record<string, unknown>) => request({ url: `/game-admin/clubs/${id}/members`, method: 'get', params })
export const clubApplications = (id: string, params: Record<string, unknown>) => request({ url: `/game-admin/clubs/${id}/applications`, method: 'get', params })
export const clubCommand = (id: string, action: string, data: unknown, key: string) => request({ url: `/game-admin/clubs/${id}/${action}`, method: 'post', data, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
export const walletLedger = (type: string, owner: string, currency: string, params: Record<string, unknown>) => request({ url: `/game-admin/wallets/${type}/${owner}/${currency}`, method: 'get', params })
export const walletReconcile = (type: string, owner: string, currency: string, reason: string, key: string) => request({ url: `/game-admin/wallets/${type}/${owner}/${currency}/reconcile`, method: 'post', data: { reason }, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
