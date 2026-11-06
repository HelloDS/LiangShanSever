<template>
  <div v-if="canDashboard" class="app-container dashboard">
    <div class="heading"><div><h2>运营概览</h2><p>北京时间 {{ data.businessDate || '—' }} · {{error?'自动刷新暂停':'每 10 秒更新'}}</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-row :gutter="20" class="metrics">
      <el-col :xs="24" :sm="8"><el-card shadow="never"><div class="label">今日新增玩家</div><div class="number">{{ data.todayNew ?? '—' }}</div><small>正式角色首次创建，排除测试账号</small></el-card></el-col>
      <el-col :xs="24" :sm="8"><el-card shadow="never"><div class="label">今日登录玩家</div><div class="number">{{ data.todayLogin ?? '—' }}</div><small>成功认证 UID 去重，重连不重复计数</small></el-card></el-col>
      <el-col :xs="24" :sm="8"><el-card shadow="never"><div class="label">当前在线玩家</div><div class="number">{{ data.online?.onlineCount ?? '—' }}</div><small>{{ data.online?.dataStatus === 'AVAILABLE' ? '有效认证连接，排除测试账号' : '在线数据暂不可用' }}</small></el-card></el-col>
    </el-row>
    <el-card shadow="never"><template #header>最近七天登录玩家</template><div ref="chartElement" class="chart" /></el-card>
    <el-card shadow="never" class="online-history"><template #header><div class="heading history-heading"><span>在线人数分钟历史</span><el-date-picker v-model="historyDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:150px" @change="loadHistory" /><el-button :loading="historyLoading" @click="loadHistory">刷新</el-button></div></template>
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" />
      <el-alert v-if="historyEnabled===false" title="分钟采样已暂停，历史记录保留。" type="info" :closable="false" />
      <p v-if="historyError" class="history-note">查询未完成，记录数量未知。</p>
      <p v-else class="history-note">实际记录 {{ historyPoints }} 个分钟点。空缺、重建及不可用均不按零人绘制；停机历史不回填。</p>
      <div ref="historyElement" v-loading="historyLoading" class="chart" />
    </el-card>
    <p class="sample">统计时间：{{ formatTime(data.asOf) }} · 在线采样：{{ formatTime(data.online?.asOf) }}</p>
  </div>
  <div v-else class="app-container"><el-result icon="info" title="工作台" sub-title="请从导航菜单进入已授权的功能" /></div>
</template>
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { dashboard,onlineHistory } from '@/api/game'
import useUserStore from '@/store/modules/user'
interface Dashboard { todayNew?: number; todayLogin?: number; businessDate?: string; asOf?: string; online?: { onlineCount: number | null; dataStatus: string; asOf: string }; loginTrend?: { date: string; loginCount: number }[] }
const data = ref<Dashboard>({}), loading = ref(false), error = ref(''), chartElement = ref<HTMLElement>()
let chart: echarts.ECharts | undefined, timer: ReturnType<typeof setInterval> | undefined
const historyDate=ref(new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())),historyElement=ref<HTMLElement>(),historyLoading=ref(false),historyError=ref(''),historyPoints=ref(0),historyEnabled=ref<boolean|null>(null)
let historyChart:echarts.ECharts|undefined,historyTimer:ReturnType<typeof setInterval>|undefined,historySequence=0
const user=useUserStore()
const canDashboard=computed(()=>user.permissions.includes('*:*:*')||user.permissions.includes('game:dashboard:query'))
const formatTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(new Date(value)) : '—'
async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const response: any = await dashboard(); data.value = response.data; error.value = ''
    if(!timer)timer=setInterval(load,10000)
    const start = new Date(`${data.value.businessDate}T00:00:00+08:00`)
    const dates = Array.from({ length: 7 }, (_, index) => new Date(start.getTime() - (6 - index) * 86400000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }))
    const counts = new Map(data.value.loginTrend?.map(row => [String(row.date).slice(0, 10), row.loginCount]))
    chart?.setOption({ tooltip: { trigger: 'axis' }, grid: { left: 45, right: 20, top: 25, bottom: 35 }, xAxis: { type: 'category', data: dates }, yAxis: { type: 'value', minInterval: 1 }, series: [{ type: 'line', data: dates.map(date => counts.get(date) || 0), lineStyle: { color: '#357a63' }, itemStyle: { color: '#357a63' }, areaStyle: { color: '#edf5f1' } }] })
  } catch { error.value = '统计加载失败，已显示的数据可能过期，可手动刷新重试。';if(timer)clearInterval(timer);timer=undefined } finally { loading.value = false }
}
async function loadHistory(){
  const sequence=++historySequence,date=historyDate.value;if(!date)return;historyLoading.value=true;historyError.value=''
  try{
    const response=await onlineHistory(date);if(sequence!==historySequence)return
    historyPoints.value=response.data.rows.length;historyEnabled.value=response.data.samplingEnabled
    const counts=new Map(response.data.rows.map(row=>[Date.parse(row.sampleMinute),row.dataStatus==='AVAILABLE'?row.onlineCount:null]))
    const start=Date.parse(`${date}T00:00:00+08:00`),times=Array.from({length:1440},(_,index)=>start+index*60000)
    const minuteLabel=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',hourCycle:'h23'})
    historyChart?.setOption({tooltip:{trigger:'axis'},grid:{left:45,right:20,top:25,bottom:35},xAxis:{type:'category',data:times.map(time=>minuteLabel.format(time))},yAxis:{type:'value',minInterval:1},series:[{type:'line',connectNulls:false,showSymbol:false,data:times.map(time=>counts.get(time)??null),lineStyle:{color:'#357a63'}}]})
    if(!historyTimer)historyTimer=setInterval(loadHistory,60000)
  }catch{if(sequence===historySequence){historyError.value='在线历史查询未完成，请检查日期和数据授权后重试。';historyPoints.value=0;historyEnabled.value=null;historyChart?.clear();if(historyTimer)clearInterval(historyTimer);historyTimer=undefined}}
  finally{if(sequence===historySequence)historyLoading.value=false}
}
const resize = () => {chart?.resize();historyChart?.resize()}
onMounted(() => { if(!canDashboard.value)return;if (chartElement.value) chart = echarts.init(chartElement.value);if(historyElement.value)historyChart=echarts.init(historyElement.value);load();void loadHistory();window.addEventListener('resize', resize) })
onUnmounted(() => { if (timer) clearInterval(timer);if(historyTimer)clearInterval(historyTimer);++historySequence;window.removeEventListener('resize', resize);chart?.dispose();historyChart?.dispose() })
</script>
<style scoped>
.dashboard{max-width:1400px;margin:auto}.heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px}h2{margin:0 0 10px;font-size:25px}.heading p,.sample{color:#718078;font-size:13px}.metrics{margin-bottom:24px}.metrics .el-col{margin-bottom:12px}.label{font-size:14px;color:#52645b}.number{font-size:38px;font-weight:600;margin:18px 0}small{color:#87918b}.chart{height:320px}.sample{text-align:right}
.online-history{margin-top:24px}.history-heading{margin:0;gap:12px}.history-note{font-size:13px;color:#718078;line-height:1.6}
</style>
