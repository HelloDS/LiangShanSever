<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search"><el-form-item label="处理状态"><el-select v-model="status" clearable style="width:160px"><el-option label="待处理" value="PENDING" /><el-option label="已处理" value="PROCESSED" /><el-option label="已驳回" value="REJECTED" /></el-select></el-form-item><el-form-item><el-button type="primary" native-type="submit">查询</el-button></el-form-item></el-form>
    <el-table :data="rows" v-loading="loading" empty-text="暂无举报">
      <el-table-column prop="type" label="类型" width="120" /><el-table-column prop="detail" label="举报说明" min-width="240" show-overflow-tooltip />
      <el-table-column prop="contactMask" label="联系信息" width="150" /><el-table-column label="提交时间" width="190"><template #default="{row}">{{gameTime(row.createdAt)}}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{row}">{{labels[row.status]}}</template></el-table-column>
      <el-table-column label="操作" width="210"><template #default="{row}"><el-button link type="primary" @click="detail=row;showDetail=true">详情</el-button><el-button v-if="row.status==='PENDING'" v-hasPermi="['game:report:process']" link type="primary" @click="process(row)">处理</el-button><el-button v-if="!row.anonymous" v-hasPermi="['game:report:contact']" link type="warning" @click="contacts(row.id)">联系方式</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="50" :total="total" layout="total, prev, pager, next" @change="load" class="pagination" />
    <el-dialog v-model="showDetail" title="举报详情" width="600px"><el-descriptions :column="1" border v-if="detail"><el-descriptions-item label="类型">{{detail.type}}</el-descriptions-item><el-descriptions-item label="说明"><span class="description">{{detail.detail}}</span></el-descriptions-item><el-descriptions-item label="提交者">{{detail.anonymous?'匿名':detail.reporterUid}}</el-descriptions-item><el-descriptions-item label="处理说明">{{detail.processNote||'—'}}</el-descriptions-item></el-descriptions></el-dialog>
    <el-dialog v-model="showProcess" title="处理举报" width="500px" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="处理结果"><el-select v-model="command.status"><el-option label="已处理" value="PROCESSED" /><el-option label="驳回" value="REJECTED" /></el-select></el-form-item><el-form-item label="处理说明"><el-input v-model="command.note" type="textarea" :rows="4" maxlength="512" /></el-form-item></el-form><el-alert v-if="error" :title="error" type="error" :closable="false" /><template #footer><el-button @click="showProcess=false" :disabled="saving">取消</el-button><el-button type="primary" :loading="saving" :disabled="command.note.trim().length<3" @click="submit">提交处理</el-button></template></el-dialog>
  </div>
</template>
<script setup lang="ts">
import {ref,reactive,onMounted} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {reports,reportProcess,reportContact} from '@/api/game'
import {gameTime} from '@/utils/game-time'
const rows=ref<any[]>([]),total=ref(0),page=ref(1),status=ref('PENDING'),loading=ref(false),saving=ref(false),showDetail=ref(false),showProcess=ref(false),detail=ref<any>(null),error=ref('')
const labels:Record<string,string>={PENDING:'待处理',PROCESSED:'已处理',REJECTED:'已驳回'}
const command=reactive({id:'',status:'PROCESSED',note:'',key:''})
async function load(){loading.value=true;try{const response:any=await reports({pageNum:page.value,pageSize:50,...(status.value?{status:status.value}:{})});rows.value=response.rows;total.value=response.total}finally{loading.value=false}}
function search(){page.value=1;load()}
function process(row:any){Object.assign(command,{id:row.id,status:'PROCESSED',note:'',key:crypto.randomUUID()});error.value='';showProcess.value=true}
async function submit(){saving.value=true;try{await reportProcess(command.id,{status:command.status,note:command.note},command.key);showProcess.value=false;ElMessage.success('处理已保存');await load()}catch{error.value='操作未确认完成，可以相同内容重试。'}finally{saving.value=false}}
async function contacts(id:string){try{const {value}=await ElMessageBox.prompt('查看将记录审计，请填写原因','查看联系方式',{inputValidator:(value:string)=>value?.trim().length>=3||'至少填写 3 字'});const response:any=await reportContact(id,value);await ElMessageBox.alert(`手机：${response.data.telephone||'未填写'}\n微信：${response.data.wechat||'未填写'}`,'联系方式',{confirmButtonText:'关闭'})}catch{}}
onMounted(load)
</script>
<style scoped>.pagination{justify-content:flex-end;margin-top:20px}.description{white-space:pre-wrap;overflow-wrap:anywhere}</style>
