<template>
  <div class="app-container dashboard">
    <div class="heading"><div><h2>运营概览</h2><p>北京时间 {{ data.businessDate || '—' }} · 每 10 秒更新</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-row :gutter="20" class="metrics">
      <el-col :xs="24" :sm="8"><el-card shadow="never"><div class="label">今日新增玩家</div><div class="number">{{ data.todayNew ?? '—' }}</div><small>正式角色首次创建，排除测试账号</small></el-card></el-col>
      <el-col :xs="24" :sm="8"><el-card shadow="never"><div class="label">今日登录玩家</div><div class="number">{{ data.todayLogin ?? '—' }}</div><small>成功认证 UID 去重，重连不重复计数</small></el-card></el-col>
      <el-col :xs="24" :sm="8"><el-card shadow="never"><div class="label">当前在线玩家</div><div class="number">{{ data.online?.onlineCount ?? '—' }}</div><small>{{ data.online?.dataStatus === 'AVAILABLE' ? '有效认证连接，排除测试账号' : '在线数据暂不可用' }}</small></el-card></el-col>
    </el-row>
    <el-card shadow="never"><template #header>最近七天登录玩家</template><div ref="chartElement" class="chart" /></el-card>
    <p class="sample">统计时间：{{ formatTime(data.asOf) }} · 在线采样：{{ formatTime(data.online?.asOf) }}</p>
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { dashboard } from '@/api/game'
interface Dashboard { todayNew?: number; todayLogin?: number; businessDate?: string; asOf?: string; online?: { onlineCount: number | null; dataStatus: string; asOf: string }; loginTrend?: { date: string; loginCount: number }[] }
const data = ref<Dashboard>({}), loading = ref(false), error = ref(''), chartElement = ref<HTMLElement>()
let chart: echarts.ECharts | undefined, timer: ReturnType<typeof setInterval> | undefined
const formatTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(new Date(value)) : '—'
async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const response: any = await dashboard(); data.value = response.data; error.value = ''
    const start = new Date(`${data.value.businessDate}T00:00:00+08:00`)
    const dates = Array.from({ length: 7 }, (_, index) => new Date(start.getTime() - (6 - index) * 86400000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }))
    const counts = new Map(data.value.loginTrend?.map(row => [String(row.date).slice(0, 10), row.loginCount]))
    chart?.setOption({ tooltip: { trigger: 'axis' }, grid: { left: 45, right: 20, top: 25, bottom: 35 }, xAxis: { type: 'category', data: dates }, yAxis: { type: 'value', minInterval: 1 }, series: [{ type: 'line', data: dates.map(date => counts.get(date) || 0), lineStyle: { color: '#357a63' }, itemStyle: { color: '#357a63' }, areaStyle: { color: '#edf5f1' } }] })
  } catch { error.value = '统计加载失败，已显示的数据可能过期。' } finally { loading.value = false }
}
const resize = () => chart?.resize()
onMounted(() => { if (chartElement.value) chart = echarts.init(chartElement.value); load(); timer = setInterval(load, 10000); window.addEventListener('resize', resize) })
onUnmounted(() => { if (timer) clearInterval(timer); window.removeEventListener('resize', resize); chart?.dispose() })
</script>
<style scoped>
.dashboard{max-width:1400px;margin:auto}.heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px}h2{margin:0 0 10px;font-size:25px}.heading p,.sample{color:#718078;font-size:13px}.metrics{margin-bottom:24px}.metrics .el-col{margin-bottom:12px}.label{font-size:14px;color:#52645b}.number{font-size:38px;font-weight:600;margin:18px 0}small{color:#87918b}.chart{height:320px}.sample{text-align:right}
</style>
