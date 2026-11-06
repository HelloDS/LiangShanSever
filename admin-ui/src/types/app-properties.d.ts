import type { useDict } from '@/utils/dict'
import type { download } from '@/utils/request'
import type { getConfigKey } from '@/api/system/config'
import type { parseTime, resetForm, addDateRange, handleTree, selectDictLabel, selectDictLabels } from '@/utils/ruoyi'
import type tab from '@/plugins/tab'
import type auth from '@/plugins/auth'
import type cache from '@/plugins/cache'
import type modal from '@/plugins/modal'
import type fileDownload from '@/plugins/download'

// 使用模块增强，不重新声明 Vue 模块而遮蔽其原有导出。
declare module 'vue' {
  interface ComponentCustomProperties {
    useDict: typeof useDict
    download: typeof download
    getConfigKey: typeof getConfigKey
    parseTime: typeof parseTime
    resetForm: typeof resetForm
    addDateRange: typeof addDateRange
    handleTree: typeof handleTree
    selectDictLabel: typeof selectDictLabel
    selectDictLabels: typeof selectDictLabels
    $tab: typeof tab
    $auth: typeof auth
    $cache: typeof cache
    $modal: typeof modal
    $download: typeof fileDownload
  }
}
export {}

declare module 'vue-router' {
  interface RouteMeta {
    title?:string
    icon?:string
    activeMenu?:string
    link?:string
    noCache?:boolean
    affix?:boolean
  }
}
