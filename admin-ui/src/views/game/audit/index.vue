<template>
  <div class="app-container">
    <p class="explanation">查询已落库的业务审计及命令状态。历史或独立查阅记录可能没有命令关联；未关联不表示执行失败。</p>
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="日期"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:255px" /></el-form-item>
      <el-form-item label="操作者"><el-input v-model="operatorId" maxlength="19" placeholder="管理员编号" clearable style="width:150px" /></el-form-item>
      <el-form-item label="动作"><el-input v-model="action" maxlength="64" placeholder="例如 kick" clearable style="width:165px" /></el-form-item>
      <el-form-item label="目标"><el-input v-model="target" maxlength="64" placeholder="精确目标编号" clearable style="width:170px" /></el-form-item>
      <el-form-item label="命令编号"><el-input v-model="operationId" maxlength="36" clearable style="width:310px" /></el-form-item>
      <el-form-item><el-button native-type="submit" type="primary" :disabled="!valid">查询</el-button><el-button @click="load">刷新</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <el-table v-loading="loading" :data="rows" :empty-text="error?'查询未完成':'所选范围暂无业务审计'">
      <el-table-column prop="id" label="审计编号" width="110" /><el-table-column prop="operatorId" label="操作者" width="110" />
      <el-table-column prop="action" label="动作" min-width="165" /><el-table-column prop="target" label="目标" min-width="160" show-overflow-tooltip />
      <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
      <el-table-column label="命令状态" width="130"><template #default="{row}"><el-tag :type="row.operationStatus==='COMPLETED'?'success':'info'">{{ operationText(row) }}</el-tag></template></el-table-column>
      <el-table-column label="审计时间" min-width="180"><template #default="{row}">{{ gameTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="openDetail(row.id)">详情</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="!error" v-model:current-page="page" :total="total" :page-size="50" layout="total, prev, pager, next" class="pagination" @change="load" />
    <el-dialog v-model="detailVisible" title="业务审计详情" width="min(900px,96%)">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" class="feedback" />
      <div v-loading="detailLoading" class="detail">
        <template v-if="selected">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="审计编号">{{ selected.id }}</el-descriptions-item><el-descriptions-item label="操作者">{{ selected.operatorId }}</el-descriptions-item>
            <el-descriptions-item label="动作">{{ selected.action }}</el-descriptions-item><el-descriptions-item label="目标">{{ selected.target }}</el-descriptions-item>
            <el-descriptions-item label="原因" :span="2">{{ selected.reason }}</el-descriptions-item>
            <el-descriptions-item label="命令编号" :span="2">{{ selected.operationId||'未关联' }}</el-descriptions-item>
            <el-descriptions-item label="命令状态">{{ operationText(selected) }}</el-descriptions-item><el-descriptions-item label="审计时间">{{ gameTime(selected.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="完成时间" :span="2">{{ selected.completedAt?gameTime(selected.completedAt):'无已关联的完成时间' }}</el-descriptions-item>
          </el-descriptions>
          <p v-if="!selected.operationId" class="explanation">此记录未保存命令关联，可能是历史审计或单独的信息查阅。页面不推断它的命令编号或执行结果。</p>
          <p class="summary-title">业务前后摘要</p>
          <p v-if="!selected.summaryAvailable" class="explanation">该动作尚无可展示的业务摘要。</p>
          <el-table v-else :data="changes" empty-text="本次未保存可展示的摘要字段">
            <el-table-column prop="field" label="字段" min-width="160" /><el-table-column prop="before" label="操作前" min-width="220" /><el-table-column prop="after" label="操作后" min-width="220" />
          </el-table>
        </template>
      </div>
      <template #footer><el-button @click="detailVisible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import {computed,ref} from 'vue'
import {auditRecords,auditRecord,type AuditRecord,type AuditQuery} from '@/api/audit'
import {gameTime} from '@/utils/game-time'
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const dates=ref<[string,string]>([today,today]),operatorId=ref(''),action=ref(''),target=ref(''),operationId=ref(''),page=ref(1)
const valid=computed(()=>dates.value?.length===2&&(!operatorId.value||/^[1-9][0-9]{0,18}$/.test(operatorId.value)))
const rows=ref<AuditRecord[]>([]),total=ref(0),loading=ref(false),error=ref('')
const applied=ref<AuditQuery>({start:today,end:today,pageNum:1,pageSize:50});let listSequence=0
function operationText(row:AuditRecord){return !row.operationId?'未关联':row.operationStatus==='COMPLETED'?'已完成':row.operationStatus==='RUNNING'?'执行中':'状态未确认'}
function search(){if(!valid.value)return;page.value=1;applied.value={start:dates.value[0],end:dates.value[1],operatorId:operatorId.value||undefined,action:action.value||undefined,target:target.value||undefined,operationId:operationId.value||undefined,pageNum:1,pageSize:50};void load()}
async function load(){const sequence=++listSequence;loading.value=true;error.value='';try{const result=await auditRecords({...applied.value,pageNum:page.value});if(sequence===listSequence){rows.value=result.rows;total.value=result.total}}catch{if(sequence===listSequence){rows.value=[];total.value=0;error.value='业务审计查询失败，请检查筛选范围和数据授权后重试。'}}finally{if(sequence===listSequence)loading.value=false}}
const detailVisible=ref(false),detailLoading=ref(false),detailError=ref(''),selected=ref<AuditRecord|null>(null);let detailSequence=0
async function openDetail(id:string){const sequence=++detailSequence;selected.value=null;detailError.value='';detailVisible.value=true;detailLoading.value=true;try{const result=await auditRecord(id);if(sequence===detailSequence)selected.value=result.data.record}catch{if(sequence===detailSequence)detailError.value='业务审计详情查询失败或数据授权已撤销。'}finally{if(sequence===detailSequence)detailLoading.value=false}}
const changes=computed(()=>{const before=selected.value?.before||{},after=selected.value?.after||{};return [...new Set([...Object.keys(before),...Object.keys(after)])].sort().map(field=>({field,before:display(before[field]),after:display(after[field])}))})
function display(value:string|boolean|undefined){return value===undefined?'未记录':typeof value==='boolean'?(value?'是':'否'):value}
void load()
</script>
<style scoped>.explanation{color:#606266;margin:0 0 20px;line-height:1.6}.feedback{margin:16px 0}.pagination{justify-content:flex-end;margin-top:20px}.detail{overflow-wrap:anywhere}.summary-title{margin:20px 0 12px}.detail .explanation{margin-top:16px}</style>
