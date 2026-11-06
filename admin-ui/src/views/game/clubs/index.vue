<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="圈 ID"><el-input v-model="query.id" placeholder="亲友圈编号" clearable /></el-form-item>
      <el-form-item label="名称"><el-input v-model="query.name" maxlength="8" placeholder="圈名称包含" clearable /></el-form-item>
      <el-form-item label="圈主 UID"><el-input v-model="query.owner" placeholder="圈主编号" clearable /></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" clearable class="select"><el-option label="正常" value="ACTIVE" /><el-option label="已停用" value="SUSPENDED" /><el-option label="已解散" value="DISBANDED" /></el-select></el-form-item>
      <el-form-item label="排序"><el-select v-model="query.sort" class="select"><el-option label="圈 ID" value="id" /><el-option label="成员数" value="members" /><el-option label="在线人数" value="online" /><el-option label="创建时间" value="created" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
    </el-form>
    <el-alert v-if="dataStatus && dataStatus!=='AVAILABLE'" title="在线数据暂不可用，在线人数显示未知。" type="warning" :closable="false" class="notice" />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="notice" />
    <el-table v-loading="loading" :data="rows" empty-text="暂无授权范围内的亲友圈">
      <el-table-column prop="clubId" label="圈 ID" width="110" /><el-table-column prop="name" label="名称" min-width="130" />
      <el-table-column label="圈主" min-width="180"><template #default="{row}">{{ row.ownerName }} · {{ row.ownerUid }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':'info'">{{ statusText(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="人数" width="100"><template #default="{row}">{{ row.memberCount }}/{{ row.maxMembers }}</template></el-table-column>
      <el-table-column label="在线" width="90"><template #default="{row}">{{ row.onlineCount ?? '未知' }}</template></el-table-column>
      <el-table-column label="测试圈" width="90"><template #default="{row}">{{ row.isTest ? '是' : '否' }}</template></el-table-column>
      <el-table-column label="创建时间" min-width="180"><template #default="{row}">{{ gameTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="180" fixed="right"><template #default="{row}">
        <el-button v-hasPermi="['game:club:query']" type="primary" link @click="openDetail(row.clubId)">详情</el-button>
        <el-button v-if="row.status!=='DISBANDED'" v-hasPermi="['game:club:status']" link :type="row.status==='ACTIVE'?'warning':'success'" @click="openCommand(row.clubId,'status',{status:row.status==='ACTIVE'?'SUSPENDED':'ACTIVE'},row.status==='ACTIVE'?'停用圈':'恢复圈')">{{ row.status==='ACTIVE'?'停用':'恢复' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total" :page-sizes="[20,50,100,200]" layout="total, sizes, prev, pager, next" @change="load" class="pagination" />
    <el-drawer v-model="showDetail" title="亲友圈详情" size="min(960px, 100%)" @closed="clearDetail">
      <div v-loading="detailLoading">
        <el-button v-if="details" class="refresh-detail" @click="refreshDetail">刷新详情</el-button>
        <el-descriptions v-if="details" :column="2" border>
          <el-descriptions-item label="圈 ID">{{ details.club.id }}</el-descriptions-item><el-descriptions-item label="名称">{{ details.club.name }}</el-descriptions-item>
          <el-descriptions-item label="圈主">{{ details.club.owner }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusText(details.club.status) }}</el-descriptions-item>
          <el-descriptions-item label="成员">{{ details.club.current_mem }}/{{ details.club.max_mem }}</el-descriptions-item><el-descriptions-item label="管理员上限">{{ details.club.maxManagers ?? '待确认' }}</el-descriptions-item>
        </el-descriptions>
        <el-tabs v-if="details" v-model="tab" @tab-change="loadTab">
          <el-tab-pane label="成员" name="members">
            <el-table :data="memberRows" empty-text="暂无成员">
              <el-table-column prop="uid" label="UID" width="110" /><el-table-column prop="name" label="昵称" min-width="130" />
              <el-table-column label="身份" width="100"><template #default="{row}">{{ roleText(row.role) }}</template></el-table-column>
              <el-table-column label="在线" width="80"><template #default="{row}">{{ row.online===null?'未知':row.online?'在线':'离线' }}</template></el-table-column>
              <el-table-column label="加入时间" min-width="180"><template #default="{row}">{{ gameTime(row.joinedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="210"><template #default="{row}"><template v-if="row.role!=='OWNER' && details.club.status==='ACTIVE'">
                <el-button v-hasPermi="['game:club:role']" link type="primary" @click="openCommand(selected,row.role==='MANAGER'?'unmanager':'manager',{targetUid:row.uid},row.role==='MANAGER'?'撤销管理员':'设为管理员')">{{ row.role==='MANAGER'?'撤销管理':'设为管理' }}</el-button>
                <el-button v-hasPermi="['game:club:member']" link type="danger" @click="openCommand(selected,'kick',{targetUid:row.uid},'移除成员')">移除</el-button>
              </template></template></el-table-column>
            </el-table>
            <el-pagination v-model:current-page="memberPage" :page-size="50" :total="memberTotal" layout="total, prev, pager, next" @change="loadTab" class="pagination" />
          </el-tab-pane>
          <el-tab-pane v-if="canReview" label="待审批" name="applications">
            <el-table :data="applicationRows" empty-text="暂无待处理申请"><el-table-column prop="uid" label="UID" width="110" /><el-table-column prop="name" label="昵称" min-width="140" />
              <el-table-column label="申请类型" width="100"><template #default="{row}">{{ row.kind==='JOIN'?'加入':'退出' }}</template></el-table-column>
              <el-table-column label="申请时间" min-width="180"><template #default="{row}">{{ gameTime(row.appliedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="150"><template #default="{row}"><el-button link type="primary" @click="openCommand(selected,'review',{targetUid:row.uid,kind:row.kind,decision:1},'同意申请')">同意</el-button><el-button link type="danger" @click="openCommand(selected,'review',{targetUid:row.uid,kind:row.kind,decision:2},'拒绝申请')">拒绝</el-button></template></el-table-column>
            </el-table><el-pagination v-model:current-page="applicationPage" :page-size="50" :total="applicationTotal" layout="total, prev, pager, next" @change="loadTab" class="pagination" />
          </el-tab-pane>
          <el-tab-pane label="圈资产" name="assets"><el-table :data="details.wallets"><el-table-column prop="currency" label="币种" /><el-table-column prop="balance" label="可用" /><el-table-column prop="frozen" label="冻结" /></el-table><p class="muted">创建、升级与资金操作尚未开放。</p></el-tab-pane>
          <el-tab-pane label="配置" name="config"><p>仅管理员可开房：{{ details.club.onlyManagerOpenRoom?'是':'否' }}</p><el-button v-if="details.club.status==='ACTIVE'" v-hasPermi="['game:club:config']" @click="openCommand(selected,'config',{onlyManagers:!details.club.onlyManagerOpenRoom},details.club.onlyManagerOpenRoom?'允许成员开房':'仅管理员开房')">修改开房权限</el-button></el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>
    <el-dialog v-model="showCommand" :title="command.title" width="min(500px, 95%)" :close-on-click-modal="false">
      <p>亲友圈 {{ command.id }}<span v-if="command.payload.targetUid"> · 玩家 {{ command.payload.targetUid }}</span></p>
      <el-input v-model="command.reason" :disabled="attempted" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="填写操作原因（至少 3 字）" />
      <el-alert v-if="commandError" :title="commandError" type="error" :closable="false" class="notice" />
      <template #footer><el-button :disabled="submitting" @click="showCommand=false">取消</el-button><el-button type="primary" :loading="submitting" :disabled="command.reason.trim().length<3" @click="submit">{{ attempted?'重试相同操作':'确认'+command.title }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { clubs, club, clubMembers, clubApplications, clubCommand } from '@/api/game'
import { gameTime } from '@/utils/game-time'
import useUserStore from '@/store/modules/user'
type ClubRow={clubId:string;name:string;ownerUid:string;ownerName:string;status:string;memberCount:number;maxMembers:number;onlineCount:number|null;createdAt:string;isTest:boolean}
type Member={uid:string;name:string;role:string;joinedAt:string;online:boolean|null}
type Application={uid:string;name:string;kind:string;appliedAt:string}
type Detail={club:{id:number;name:string;owner:number;status:string;current_mem:number;max_mem:number;maxManagers?:number;onlyManagerOpenRoom:boolean};wallets:{currency:string;balance:number;frozen:number}[]}
const user=useUserStore(),canReview=computed(()=>user.permissions.includes('*:*:*')||user.permissions.includes('game:club:review'))
const query=reactive({id:'',name:'',owner:'',status:'',sort:'id',pageNum:1,pageSize:50})
const rows=ref<ClubRow[]>([]),total=ref(0),loading=ref(false),dataStatus=ref(''),loadError=ref('')
let listSequence=0,detailSequence=0
const showDetail=ref(false),detailLoading=ref(false),details=ref<Detail|null>(null),selected=ref(''),tab=ref('members')
const memberRows=ref<Member[]>([]),memberPage=ref(1),memberTotal=ref(0),applicationRows=ref<Application[]>([]),applicationPage=ref(1),applicationTotal=ref(0)
const showCommand=ref(false),submitting=ref(false),attempted=ref(false),commandError=ref('')
const command=reactive({id:'',action:'',title:'',payload:{} as Record<string,unknown>,reason:'',key:''})
function statusText(value:string){return ({ACTIVE:'正常',SUSPENDED:'已停用',DISBANDED:'已解散'} as Record<string,string>)[value]||value}
function roleText(value:string){return ({OWNER:'圈主',MANAGER:'管理员',MEMBER:'成员'} as Record<string,string>)[value]||value}
async function load(){const seq=++listSequence;loading.value=true;loadError.value='';try{const result:any=await clubs(Object.fromEntries(Object.entries(query).filter(([,value])=>value!=='')));if(seq===listSequence){rows.value=result.rows;total.value=result.total;dataStatus.value=result.dataStatus}}catch{if(seq===listSequence)loadError.value='列表加载失败，请检查筛选条件后重试。'}finally{if(seq===listSequence)loading.value=false}}
function search(){query.pageNum=1;void load()}
function reset(){Object.assign(query,{id:'',name:'',owner:'',status:'',sort:'id',pageNum:1});void load()}
async function openDetail(id:string){const seq=++detailSequence;selected.value=id;details.value=null;showDetail.value=true;detailLoading.value=true;tab.value='members';memberPage.value=1;applicationPage.value=1;try{const result:any=await club(id);if(seq===detailSequence){details.value=result.data;await loadTab()}}finally{if(seq===detailSequence)detailLoading.value=false}}
async function refreshDetail(){const id=selected.value,seq=detailSequence;if(!id)return;detailLoading.value=true;try{const result:any=await club(id);if(seq===detailSequence){details.value=result.data;await loadTab()}}finally{if(seq===detailSequence)detailLoading.value=false}}
function clearDetail(){++detailSequence;selected.value='';details.value=null;memberRows.value=[];applicationRows.value=[]}
async function loadTab(){const id=selected.value,seq=detailSequence;if(!id)return;if(tab.value==='members'){const result:any=await clubMembers(id,{pageNum:memberPage.value,pageSize:50});if(seq===detailSequence){memberRows.value=result.rows;memberTotal.value=result.total;if(details.value)details.value.club.current_mem=result.total}}else if(tab.value==='applications'&&canReview.value){const result:any=await clubApplications(id,{pageNum:applicationPage.value,pageSize:50});if(seq===detailSequence){applicationRows.value=result.rows;applicationTotal.value=result.total}}}
function openCommand(id:string,action:string,payload:Record<string,unknown>,title:string){Object.assign(command,{id,action,payload,title,reason:'',key:crypto.randomUUID()});attempted.value=false;commandError.value='';showCommand.value=true}
async function submit(){submitting.value=true;attempted.value=true;commandError.value='';try{await clubCommand(command.id,command.action,{...command.payload,reason:command.reason},command.key);showCommand.value=false;ElMessage.success('操作已完成');await load();if(showDetail.value&&selected.value===command.id){const result:any=await club(selected.value);details.value=result.data;await loadTab()}}catch{commandError.value='操作未确认完成，可重试相同操作或刷新查看当前状态。'}finally{submitting.value=false}}
onMounted(load)
</script>
<style scoped>.refresh-detail{margin-bottom:15px}.select{width:135px}.pagination{justify-content:flex-end;margin-top:20px}.notice{margin:12px 0}.muted{color:#606266;line-height:1.8}</style>
