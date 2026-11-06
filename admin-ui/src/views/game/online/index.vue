<template>
  <div class="app-container">
    <div class="toolbar"><div><h3>在线玩家</h3><p>有效连接租约，按 UID 去重，每 10 秒刷新。采样时间：{{ gameTime(asOf) }}</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="status!=='AVAILABLE'" title="在线数据暂不可用，稍后重试" type="warning" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" empty-text="暂无在线玩家"><el-table-column prop="uid" label="UID" /><el-table-column prop="state" label="所在状态" /><el-table-column prop="node" label="节点" /><el-table-column label="最近活动" min-width="220"><template #default="{row}">{{ gameTime(row.lastActivity) }}</template></el-table-column></el-table>
    <el-pagination v-if="total!==null" v-model:current-page="page" :page-size="50" :total="total" layout="total, prev, pager, next" @change="load" class="pagination" />
  </div>
</template>
<script setup lang="ts">
import {ref,onMounted,onUnmounted} from 'vue'
import {online} from '@/api/game'
import {gameTime} from '@/utils/game-time'
const rows=ref<any[]>([]),total=ref<number|null>(null),status=ref('REBUILDING'),asOf=ref(''),page=ref(1),loading=ref(false)
let timer:ReturnType<typeof setInterval>|undefined
async function load(){if(loading.value)return;loading.value=true;try{const response:any=await online({pageNum:page.value,pageSize:50});rows.value=response.rows;total.value=response.total;status.value=response.dataStatus;asOf.value=response.asOf}catch{status.value='UNAVAILABLE';total.value=null}finally{loading.value=false}}
onMounted(()=>{load();timer=setInterval(load,10000)})
onUnmounted(()=>{if(timer)clearInterval(timer)})
</script>
<style scoped>.toolbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px}.toolbar p{font-size:13px;color:#768077}.pagination{justify-content:flex-end;margin-top:20px}</style>
