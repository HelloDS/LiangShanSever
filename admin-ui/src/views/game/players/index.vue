<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="UID"><el-input v-model="query.uid" placeholder="玩家编号" clearable /></el-form-item>
      <el-form-item label="昵称"><el-input v-model="query.name" placeholder="昵称包含" maxlength="32" clearable /></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" clearable style="width:130px"><el-option label="正常" value="ACTIVE" /><el-option label="已封禁" value="BANNED" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="rows" empty-text="暂无符合条件的玩家">
      <el-table-column prop="uid" label="UID" width="120" /><el-table-column prop="name" label="昵称" min-width="140" />
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='BANNED'?'danger':'success'">{{ row.status==='BANNED'?'已封禁':'正常' }}</el-tag></template></el-table-column>
      <el-table-column label="测试账号" width="100"><template #default="{row}">{{ row.isTest ? '是' : '否' }}</template></el-table-column>
      <el-table-column label="注册时间" min-width="180"><template #default="{row}">{{ gameTime(row.createdAt) }}</template></el-table-column><el-table-column label="最近登录" min-width="180"><template #default="{row}">{{ gameTime(row.lastLoginAt) }}</template></el-table-column>
      <el-table-column label="操作" width="260"><template #default="{row}">
        <el-button v-hasPermi="['game:player:query']" link type="primary" @click="detail(row.uid)">详情</el-button>
        <el-button v-hasPermi="['game:player:ban']" link :type="row.status==='BANNED'?'success':'danger'" @click="openCommand(row,row.status==='BANNED'?'unban':'ban')">{{ row.status==='BANNED'?'解禁':'封禁' }}</el-button>
        <el-button v-hasPermi="['game:player:kick']" link type="warning" @click="openCommand(row,'kick')">强制下线</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total" :page-sizes="[20,50,100,200]" layout="total, sizes, prev, pager, next" @change="load" class="pagination" />
    <el-dialog v-model="showDetail" title="玩家详情" width="620px">
      <el-descriptions v-if="details" :column="2" border><el-descriptions-item label="UID">{{ details.player.uid }}</el-descriptions-item><el-descriptions-item label="昵称">{{ details.player.name }}</el-descriptions-item><el-descriptions-item label="状态">{{ details.player.status }}</el-descriptions-item><el-descriptions-item label="注册时间">{{ gameTime(details.player.createdAt) }}</el-descriptions-item></el-descriptions>
      <el-table :data="details?.wallets || []"><el-table-column prop="currency" label="币种" /><el-table-column prop="balance" label="可用" /><el-table-column prop="frozen" label="冻结" /></el-table>
    </el-dialog>
    <el-dialog v-model="showCommand" :title="commandTitle" width="500px" :close-on-click-modal="false">
      <p>玩家 {{ command.uid }} · {{ command.name }}</p><el-input v-model="command.reason" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="填写操作原因（至少 3 字）" />
      <el-alert v-if="commandError" :title="commandError" type="error" :closable="false" class="feedback" />
      <template #footer><el-button @click="showCommand=false" :disabled="submitting">取消</el-button><el-button type="primary" :loading="submitting" :disabled="command.reason.trim().length<3" @click="submit">确认{{ commandTitle }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { players, player, playerCommand } from '@/api/game'
import { gameTime } from '@/utils/game-time'
const query = reactive({ uid:'', name:'', status:'', pageNum:1, pageSize:50 })
const rows=ref<any[]>([]), total=ref(0), loading=ref(false), showDetail=ref(false), details=ref<any>(null)
const showCommand=ref(false), submitting=ref(false), commandError=ref('')
const command=reactive({uid:'',name:'',action:'',reason:'',key:''})
const titles:Record<string,string>={ban:'封禁',unban:'解禁',kick:'强制下线'}
const commandTitle=computed(()=>titles[command.action] || '')
async function load(){loading.value=true;try{const response:any=await players(Object.fromEntries(Object.entries(query).filter(([,value])=>value!=='')));rows.value=response.rows;total.value=response.total}finally{loading.value=false}}
function search(){query.pageNum=1;load()}
function reset(){Object.assign(query,{uid:'',name:'',status:'',pageNum:1});load()}
async function detail(uid:string){const response:any=await player(uid);details.value=response.data;showDetail.value=true}
function openCommand(row:any,action:string){Object.assign(command,{uid:row.uid,name:row.name,action,reason:'',key:crypto.randomUUID()});commandError.value='';showCommand.value=true}
async function submit(){submitting.value=true;commandError.value='';try{await playerCommand(command.uid,command.action,command.reason,command.key);showCommand.value=false;ElMessage.success('操作已完成');await load()}catch{commandError.value='操作未确认完成，可使用相同内容重试。'}finally{submitting.value=false}}
onMounted(load)
</script>
<style scoped>.pagination{justify-content:flex-end;margin-top:20px}.feedback{margin-top:15px}</style>
