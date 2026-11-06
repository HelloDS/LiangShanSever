import request from '@/utils/request'

export const dashboard = () => request({ url: '/game-admin/dashboard', method: 'get' })
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
