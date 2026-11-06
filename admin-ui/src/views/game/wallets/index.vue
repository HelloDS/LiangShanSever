<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="主体"><el-select v-model="query.type" style="width:120px"><el-option label="个人" value="PERSONAL" /><el-option label="亲友圈" value="CLUB" /></el-select></el-form-item>
      <el-form-item :label="query.type==='PERSONAL'?'玩家 UID':'圈 ID'"><el-input v-model="query.owner" placeholder="输入编号" clearable /></el-form-item>
      <el-form-item label="币种"><el-select v-model="query.currency" style="width:110px"><el-option label="房卡" value="card" /><el-option label="钻石" value="diamond" /></el-select></el-form-item>
      <el-form-item label="时间范围"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:255px" /></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit" :disabled="!valid">查询</el-button></el-form-item>
    </el-form>
    <el-alert v-if="!searched" title="选择个人或亲友圈、输入编号后查询。流水时间范围最多 31 天，按北京时间计算。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <template v-if="searched">
      <div v-if="account" class="account-card"><div><span class="muted">{{ selected.type==='PERSONAL'?'玩家':'亲友圈' }} {{ selected.owner }} · {{ selected.currency==='card'?'房卡':'钻石' }}</span><p>可用 {{ account.available }} <span class="frozen">冻结 {{ account.frozen }}</span></p><span class="muted">截至 {{ gameTime(asOf) }}</span></div><el-button v-hasPermi="['game:wallet:reconcile']" @click="openCheck">核对余额</el-button></div>
      <el-table v-loading="loading" :data="rows" empty-text="所选时间内暂无流水">
        <el-table-column prop="createdAt" label="时间" min-width="180"><template #default="{row}">{{ gameTime(row.createdAt) }}</template></el-table-column>
        <el-table-column prop="kind" label="类型" width="130"><template #default="{row}">{{ kindText(row.kind) }}</template></el-table-column>
        <el-table-column prop="availableDelta" label="可用变动" width="120" /><el-table-column prop="frozenDelta" label="冻结变动" width="120" />
        <el-table-column prop="balanceAfter" label="变动后可用" min-width="140" /><el-table-column prop="frozenAfter" label="变动后冻结" min-width="140" />
        <el-table-column prop="businessKey" label="业务编号" min-width="220" show-overflow-tooltip /><el-table-column prop="reference" label="来源依据" min-width="180" show-overflow-tooltip /><el-table-column prop="ruleVersion" label="规则版本" min-width="130" />
      </el-table><el-pagination v-model:current-page="page" :page-size="50" :total="total" layout="total, prev, pager, next" @change="load" class="pagination" />
    </template>
    <el-dialog v-model="showCheck" title="核对余额" width="min(560px,95%)" :close-on-click-modal="false">
      <p>核对 {{ selected.type==='PERSONAL'?'玩家':'亲友圈' }} {{ selected.owner }} 的{{ selected.currency==='card'?'房卡':'钻石' }}余额与完整账本。结果会留存记录，核对不会调整资产。</p>
      <el-input v-model="reason" :disabled="attempted" type="textarea" :rows="3" maxlength="512" placeholder="填写核对原因（至少 3 字）" />
      <el-alert v-if="checkError" :title="checkError" type="error" :closable="false" class="feedback" />
      <el-alert v-if="report" :title="report.status==='MATCH'?'余额与账本一致':'发现账实差异，待核查'" :type="report.status==='MATCH'?'success':'error'" :closable="false" class="feedback" />
      <el-descriptions v-if="report" :column="2" border class="feedback"><el-descriptions-item label="账本可用">{{ report.expectedAvailable }}</el-descriptions-item><el-descriptions-item label="账户可用">{{ report.actualAvailable }}</el-descriptions-item><el-descriptions-item label="账本冻结">{{ report.expectedFrozen }}</el-descriptions-item><el-descriptions-item label="账户冻结">{{ report.actualFrozen }}</el-descriptions-item><el-descriptions-item label="流水笔数">{{ report.ledgerEntries }}</el-descriptions-item><el-descriptions-item label="核对时间">{{ gameTime(report.asOf) }}</el-descriptions-item></el-descriptions>
      <template #footer><el-button @click="showCheck=false" :disabled="checking">关闭</el-button><el-button v-if="!report" type="primary" :loading="checking" :disabled="reason.trim().length<3" @click="check">{{ attempted?'重试核对':'开始核对' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { walletLedger, walletReconcile } from '@/api/game'
import { gameTime } from '@/utils/game-time'
type Ledger={id:string;createdAt:string;kind:string;availableDelta:string;frozenDelta:string;balanceAfter:string;frozenAfter:string;businessKey:string;reference:string;ruleVersion:string}
type Balance={available:string;frozen:string;version:string}
type Report={status:string;expectedAvailable:string;actualAvailable:string;expectedFrozen:string;actualFrozen:string;ledgerEntries:number;asOf:string}
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const query=reactive({type:'PERSONAL',owner:'',currency:'card'}),dates=ref<[string,string]>([today,today]),selected=reactive({type:'',owner:'',currency:'',start:'',end:''})
const valid=computed(()=>/^[1-9][0-9]{0,8}$/.test(query.owner)&&dates.value?.length===2)
const rows=ref<Ledger[]>([]),account=ref<Balance|null>(null),asOf=ref(''),page=ref(1),total=ref(0),loading=ref(false),searched=ref(false),error=ref('')
let sequence=0
const showCheck=ref(false),reason=ref(''),checking=ref(false),attempted=ref(false),key=ref(''),checkError=ref(''),report=ref<Report|null>(null)
function kindText(kind:string){return ({TRANSFER:'划转',FREEZE:'冻结',RELEASE:'释放冻结',CONSUMPTION:'消耗',EXCHANGE:'兑换',COMPENSATION:'补偿',REWARD:'奖励'} as Record<string,string>)[kind]||kind}
function search(){if(!valid.value)return;Object.assign(selected,query,{start:dates.value[0],end:dates.value[1]});page.value=1;account.value=null;rows.value=[];total.value=0;searched.value=true;void load()}
async function load(){const seq=++sequence;loading.value=true;error.value='';try{const result:any=await walletLedger(selected.type,selected.owner,selected.currency,{start:selected.start,end:selected.end,pageNum:page.value,pageSize:50});if(seq===sequence){rows.value=result.rows;total.value=result.total;account.value=result.account;asOf.value=result.asOf}}catch{if(seq===sequence)error.value='账本查询失败，请检查编号、时间范围和数据授权后重试。'}finally{if(seq===sequence)loading.value=false}}
function openCheck(){reason.value='';attempted.value=false;key.value=crypto.randomUUID();checkError.value='';report.value=null;showCheck.value=true}
async function check(){checking.value=true;attempted.value=true;checkError.value='';try{const result:any=await walletReconcile(selected.type,selected.owner,selected.currency,reason.value,key.value);report.value=result.data.result.report}catch{checkError.value='核对未确认完成，可重试相同请求。'}finally{checking.value=false}}
</script>
<style scoped>.feedback{margin-top:16px}.pagination{justify-content:flex-end;margin-top:20px}.account-card{display:flex;justify-content:space-between;align-items:center;border:1px solid #dcdfe6;border-radius:6px;padding:18px 24px;margin:18px 0}.account-card p{font-size:22px;margin:12px 0}.muted{font-size:13px;color:#606266}.frozen{margin-left:25px}</style>
