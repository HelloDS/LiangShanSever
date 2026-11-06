import { getCurrentInstance } from 'vue'

/** 全局插件在 main.ts 注册；仅允许在组件 setup 中取得非空实例。 */
export function useAppProxy() {
  const instance = getCurrentInstance()
  if (!instance?.proxy) throw new Error('useAppProxy 必须在组件 setup 中调用')
  return instance.proxy
}
