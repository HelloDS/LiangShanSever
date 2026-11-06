<template>
  <div class="app-container">
    <p class="explanation">核对期初余额与已记账流水，结果反映各账户检查时刻。仅记录差异，不自动调整资产。</p>
    <el-alert v-if="processingEnabled===false" title="自动处理已暂停，任务与已完成进度会保留。" type="info" :closable="false" class="feedback" />
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="核对日期"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:255px" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="status" clearable placeholder="全部" style="width:155px"><el-option v-for="(label,value) in labels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item><el-button native-type="submit" type="primary" :disabled="!dates?.length">查询</el-button><el-button @click="load">刷新</el-button></el-form-item>
      <el-form-item><el-button v-hasPermi="['game:reconciliation:run']" @click="openOperation()">发起本日核对</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <el-table v-loading="loading" :data="rows" :empty-text="error?'查询未完成':'所选范围暂无核对记录'">
      <el-table-column prop="scheduledDate" label="核对日期" width="120" />
      <el-table-column label="状态" width="130"><template #default="{row}"><el-tag :type="tag(row.status)">{{ statusText(row) }}</el-tag></template></el-table-column>
      <el-table-column prop="checkedCount" label="已核对账户" width="120" /><el-table-column prop="matchedCount" label="一致" width="95" />
      <el-table-column label="差异" width="95"><template #default="{row}"><b :class="{'issue':row.mismatchedCount>0}">{{ row.mismatchedCount }}</b></template></el-table-column>
      <el-table-column label="来源" width="100"><template #default="{row}">{{ row.origin==='SYSTEM'?'自动':'管理员' }}</template></el-table-column>
      <el-table-column label="开始时间" min-width="180"><template #default="{row}">{{ row.startedAt?gameTime(row.startedAt):'尚未开始' }}</template></el-table-column>
      <el-table-column label="最近检查" min-width="180"><template #default="{row}">{{ row.lastCheckedAt?gameTime(row.lastCheckedAt):'—' }}</template></el-table-column>
      <el-table-column label="操作" width="155" fixed="right"><template #default="{row}"><el-button link type="primary" @click="openDetail(row.id)">详情</el-button><el-button v-if="row.status==='FAILED'" v-hasPermi="['game:reconciliation:retry']" link type="primary" @click="openOperation(row)">重试</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="!error" v-model:current-page="page" :total="total" :page-size="50" layout="total, prev, pager, next" class="pagination" @change="load" />
    <el-dialog v-model="detailVisible" title="账本核对记录" width="min(1100px,96%)">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" class="feedback" />
      <div v-loading="detailLoading">
        <template v-if="selected">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="核对日期">{{ selected.scheduledDate }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusText(selected) }}</el-descriptions-item><el-descriptions-item label="核对账户">{{ selected.checkedCount }}</el-descriptions-item>
            <el-descriptions-item label="一致">{{ selected.matchedCount }}</el-descriptions-item><el-descriptions-item label="差异">{{ selected.mismatchedCount }}</el-descriptions-item><el-descriptions-item label="重试次数">{{ selected.manualRetries }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ selected.startedAt?gameTime(selected.startedAt):'尚未开始' }}</el-descriptions-item><el-descriptions-item label="完成时间">{{ selected.completedAt?gameTime(selected.completedAt):'未完成' }}</el-descriptions-item><el-descriptions-item label="最近错误">{{ selected.lastErrorCode||'无' }}</el-descriptions-item>
          </el-descriptions>
          <div class="detail-toolbar"><span>核对明细</span><el-select v-model="detailStatus" clearable placeholder="全部明细" style="width:155px" @change="detailPage=1;loadDetail()"><el-option label="发现差异" value="MISMATCH" /><el-option label="一致" value="MATCH" /></el-select><el-button @click="loadDetail">刷新进度</el-button></div>
          <el-table :data="details" empty-text="所选状态暂无明细">
            <el-table-column label="主体" width="100"><template #default="{row}">{{ ownerLabels[row.ownerType as keyof typeof ownerLabels] }}</template></el-table-column><el-table-column prop="ownerId" label="编号" min-width="115" />
            <el-table-column label="币种" width="80"><template #default="{row}">{{ row.currency==='card'?'房卡':'钻石' }}</template></el-table-column>
            <el-table-column label="可用：应有 / 实有" min-width="180"><template #default="{row}">{{ row.expectedAvailable }} / {{ row.actualAvailable }}</template></el-table-column>
            <el-table-column label="冻结：应有 / 实有" min-width="180"><template #default="{row}">{{ row.expectedFrozen }} / {{ row.actualFrozen }}</template></el-table-column>
            <el-table-column prop="ledgerEntries" label="流水笔数" width="100" /><el-table-column prop="accountVersion" label="账户版本" width="100" />
            <el-table-column label="检查时间" min-width="180"><template #default="{row}">{{ gameTime(row.checkedAt) }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="detailPage" :total="detailTotal" :page-size="50" layout="total, prev, pager, next" class="pagination" @change="loadDetail" />
        </template>
      </div>
      <template #footer><el-button @click="detailVisible=false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="operationVisible" :title="operationTarget?'重试未完成核对':'发起本日账本核对'" width="min(560px,95%)" :close-on-click-modal="false" :before-close="beforeOperationClose">
      <p>{{ operationTarget?'保留原任务和已完成的核对进度，仅继续未完成部分。':'同一天只登记一项核对任务，已有任务会直接复用。' }}</p>
      <el-input v-model="reason" :disabled="attempted" type="textarea" :rows="3" maxlength="512" placeholder="填写操作原因（至少 3 字）" />
      <el-alert v-if="operationError" :title="operationError" type="error" :closable="false" class="feedback" />
      <el-alert v-if="accepted" title="请求已确认，可查看任务进度和实际核对结果。" type="success" :closable="false" class="feedback" />
      <template #footer><el-button :disabled="operating" @click="operationVisible=false">关闭</el-button><el-button v-if="accepted" type="primary" @click="viewAccepted">查看进度</el-button><el-button v-else type="primary" :loading="operating" :disabled="reason.trim().length<3" @click="operate">{{ attempted?'重试相同请求':'确认' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { reconciliationRuns,reconciliationRun,createTodayReconciliation,retryReconciliation,type ReconciliationRun,type ReconciliationStatus,type ReconciliationDetail,type ReconciliationQuery,type CheckStatus } from '@/api/reconciliation'
import { gameTime } from '@/utils/game-time'
const labels:Record<ReconciliationStatus,string>={READY:'待执行 / 继续',RUNNING:'核对中',DONE:'全部一致',DONE_WITH_ISSUES:'发现差异',FAILED:'未完成'}
const ownerLabels={PERSONAL:'个人',CLUB:'亲友圈',SYSTEM:'系统清算'}
function tag(status:ReconciliationStatus){return status==='FAILED'?'danger':status==='DONE_WITH_ISSUES'?'warning':status==='DONE'?'success':'info'}
function statusText(run:ReconciliationRun){return run.status==='READY'?(run.checkedCount?'等待继续':'待执行'):labels[run.status]}
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const dates=ref<[string,string]>([today,today]),status=ref<ReconciliationStatus|''>(''),page=ref(1),rows=ref<ReconciliationRun[]>([]),total=ref(0),loading=ref(false),error=ref('')
const applied=ref<ReconciliationQuery>({start:today,end:today,pageNum:1,pageSize:50});let listSequence=0
const processingEnabled=ref<boolean|null>(null)
function search(){if(!dates.value||dates.value.length!==2)return;applied.value={start:dates.value[0],end:dates.value[1],status:status.value||undefined,pageNum:1,pageSize:50};page.value=1;void load()}
async function load(){const sequence=++listSequence;loading.value=true;error.value='';try{const result=await reconciliationRuns({...applied.value,pageNum:page.value});if(sequence===listSequence){rows.value=result.rows;total.value=result.total;processingEnabled.value=result.processingEnabled}}catch{if(sequence===listSequence){rows.value=[];total.value=0;processingEnabled.value=null;error.value='核对记录查询失败，请检查日期范围和数据授权后重试。'}}finally{if(sequence===listSequence)loading.value=false}}
const detailVisible=ref(false),detailLoading=ref(false),detailError=ref(''),selected=ref<ReconciliationRun|null>(null),details=ref<ReconciliationDetail[]>([]),detailStatus=ref<CheckStatus|''>('MISMATCH'),detailPage=ref(1),detailTotal=ref(0),detailId=ref('');let detailSequence=0
function openDetail(id:string){detailId.value=id;detailPage.value=1;detailStatus.value='MISMATCH';selected.value=null;details.value=[];detailVisible.value=true;void loadDetail()}
async function loadDetail(){const sequence=++detailSequence;detailLoading.value=true;detailError.value='';try{const result=await reconciliationRun(detailId.value,{status:detailStatus.value||undefined,pageNum:detailPage.value,pageSize:50});if(sequence===detailSequence){selected.value=result.data.run;details.value=result.data.rows;detailTotal.value=result.data.total}}catch{if(sequence===detailSequence){selected.value=null;details.value=[];detailTotal.value=0;detailError.value='核对明细查询失败或数据授权已撤销。'}}finally{if(sequence===detailSequence)detailLoading.value=false}}
const operationVisible=ref(false),operationTarget=ref<ReconciliationRun|null>(null),reason=ref(''),key=ref(''),attempted=ref(false),accepted=ref(false),operating=ref(false),operationError=ref(''),acceptedId=ref('')
function openOperation(run?:ReconciliationRun){operationTarget.value=run||null;reason.value='';key.value=crypto.randomUUID();attempted.value=false;accepted.value=false;acceptedId.value='';operationError.value='';operationVisible.value=true}
function beforeOperationClose(done:()=>void){if(!operating.value)done()}
async function operate(){if(reason.value.trim().length<3||operating.value)return;operating.value=true;attempted.value=true;operationError.value='';try{const result=operationTarget.value?await retryReconciliation(operationTarget.value.id,reason.value,key.value):await createTodayReconciliation(reason.value,key.value);acceptedId.value=result.data.result.run.id;accepted.value=true;await load()}catch{operationError.value='请求结果尚未确认，可重试相同请求；已完成的核对不会重复登记。'}finally{operating.value=false}}
function viewAccepted(){operationVisible.value=false;openDetail(acceptedId.value)}
void load()
</script>
<style scoped>.explanation{color:#606266;margin:0 0 20px;line-height:1.6}.feedback{margin:16px 0}.pagination{justify-content:flex-end;margin-top:20px}.issue{color:#e6a23c}.detail-toolbar{display:flex;gap:16px;align-items:center;margin:20px 0}</style>
