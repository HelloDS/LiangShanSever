<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="状态"><el-select v-model="status" clearable placeholder="全部" style="width:155px"><el-option v-for="(label,value) in labels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item label="发生日期"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:255px" /></el-form-item>
      <el-form-item><el-button native-type="submit" type="primary" :disabled="!dates || dates.length!==2">查询</el-button><el-button @click="load">刷新</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <div class="backlog"><span>待处理 <b>{{ summary?.READY ?? '未知' }}</b></span><span>处理中 <b>{{ summary?.CLAIMED ?? '未知' }}</b></span><span>待人工处理 <b>{{ summary?.DEAD ?? '未知' }}</b></span><small v-if="asOf">截至 {{ gameTime(asOf) }}</small></div>
    <el-table v-loading="loading" :data="rows" empty-text="所选范围暂无持久任务">
      <el-table-column label="发生时间" min-width="175"><template #default="{row}">{{ gameTime(row.occurredAt) }}</template></el-table-column>
      <el-table-column label="类型" width="145"><template #default="{row}">{{ eventText(row.eventType) }}</template></el-table-column>
      <el-table-column label="状态" width="130"><template #default="{row}"><el-tag :type="row.status==='DEAD'?'danger':row.status==='DONE'?'success':'info'">{{ labels[row.status as TaskStatus] }}</el-tag></template></el-table-column>
      <el-table-column prop="businessKey" label="业务编号" min-width="210" show-overflow-tooltip />
      <el-table-column prop="totalAttempts" label="累计领取" width="100" /><el-table-column prop="manualRetries" label="人工重试" width="100" />
      <el-table-column prop="lastErrorCode" label="最近错误代码" min-width="155" />
      <el-table-column label="重试/租约时间" min-width="175"><template #default="{row}">{{ gameTime(row.leaseUntil||row.retryAt) }}</template></el-table-column>
      <el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button v-if="row.status==='DEAD'" v-hasPermi="['game:outbox:retry']" link type="primary" @click="openRetry(row)">重新处理</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="50" layout="total, prev, pager, next" class="pagination" @change="load" />
    <el-dialog v-model="showRetry" title="重新处理失败任务" width="min(560px,95%)" :close-on-click-modal="false" :before-close="beforeClose">
      <p>重试保留原事件及业务编号。排查错误后填写处理原因，系统会记录操作人和审计。</p>
      <p class="identifier">{{ selected?.businessKey }}</p>
      <el-input v-model="reason" :disabled="attempted" type="textarea" :rows="3" maxlength="512" placeholder="填写处理原因（至少 3 字）" />
      <el-alert v-if="retryError" :title="retryError" type="error" :closable="false" class="feedback" />
      <el-alert v-if="accepted" title="任务已重新进入处理队列，可刷新列表查看结果。" type="success" :closable="false" class="feedback" />
      <template #footer><el-button :disabled="retrying" @click="showRetry=false">关闭</el-button><el-button v-if="!accepted" type="primary" :loading="retrying" :disabled="reason.trim().length<3" @click="retry">{{ attempted?'重试相同请求':'确认重新处理' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { outboxTasks, outboxBacklog, outboxRetry, type TaskStatus, type OutboxTask } from '@/api/outbox'
import { gameTime } from '@/utils/game-time'
const labels: Record<TaskStatus,string>={READY:'待处理',CLAIMED:'处理中',DONE:'已完成',DEAD:'待人工处理'}
function eventText(type:string){return ({PLAYER_REVOKED:'玩家会话撤销',ROOM_EVENT_COMMITTED:'房间事件投影'} as Record<string,string>)[type]||type}
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const dates=ref<[string,string]>([today,today]),status=ref<TaskStatus|''>(''),page=ref(1),total=ref(0)
const rows=ref<OutboxTask[]>([]),loading=ref(false),error=ref(''),asOf=ref(''),summary=ref<Record<TaskStatus,number>|null>(null)
let sequence=0
async function load(){
  if(!dates.value || dates.value.length!==2)return
  const seq=++sequence;loading.value=true;error.value=''
  try{
    const [tasks,backlog]=await Promise.all([outboxTasks({status:status.value||undefined,start:dates.value[0],end:dates.value[1],pageNum:page.value,pageSize:50}),outboxBacklog()])
    if(seq!==sequence)return
    rows.value=tasks.rows;total.value=tasks.total;asOf.value=backlog.data.asOf
    const counts: Record<TaskStatus,number>={READY:0,CLAIMED:0,DONE:0,DEAD:0}
    for(const row of backlog.data.rows)counts[row.status]=row.total
    summary.value=counts
  }catch{if(seq===sequence){error.value='任务查询失败，请检查时间范围与数据授权后重试。';summary.value=null}}
  finally{if(seq===sequence)loading.value=false}
}
function search(){page.value=1;void load()}
const showRetry=ref(false),selected=ref<OutboxTask|null>(null),reason=ref(''),key=ref(''),attempted=ref(false),retrying=ref(false),accepted=ref(false),retryError=ref('')
function openRetry(row:OutboxTask){selected.value=row;reason.value='';key.value=crypto.randomUUID();attempted.value=false;accepted.value=false;retryError.value='';showRetry.value=true}
function beforeClose(done:()=>void){if(!retrying.value)done()}
async function retry(){
  if(!selected.value||reason.value.trim().length<3)return
  retrying.value=true;attempted.value=true;retryError.value=''
  try{await outboxRetry(selected.value.id,reason.value,key.value);accepted.value=true;await load()}
  catch{retryError.value='任务重试未确认受理，可重试相同请求。'}
  finally{retrying.value=false}
}
void load()
</script>
<style scoped>.feedback{margin-top:16px}.pagination{justify-content:flex-end;margin-top:20px}.backlog{display:flex;align-items:center;gap:30px;flex-wrap:wrap;margin:20px 0;color:#606266}.backlog b{font-size:22px;margin-left:8px;color:#303133}.backlog small{margin-left:auto}.identifier{overflow-wrap:anywhere;font-family:monospace}</style>
