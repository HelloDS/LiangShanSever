<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="房号"><el-input v-model="query.roomCode" placeholder="6 位房号" maxlength="6" clearable style="width:140px" /></el-form-item>
      <el-form-item label="房主 UID"><el-input v-model="query.ownerUid" clearable style="width:145px" /></el-form-item>
      <el-form-item label="代开/创建者 UID"><el-input v-model="query.creatorUid" clearable style="width:145px" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" clearable placeholder="全部" style="width:140px"><el-option v-for="(label,value) in labels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item label="创建日期"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:255px" /></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit" :disabled="!valid">查询</el-button><el-button @click="load">刷新</el-button><el-button v-hasPermi="['game:room:export']" :disabled="loading||!!error" @click="openExport">导出当前筛选</el-button><el-button v-hasPermi="['game:room:export']" @click="openJobs">导出任务</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <el-table v-loading="loading" :data="rows" :empty-text="error?'查询未完成':'所选范围暂无房间记录'">
      <el-table-column label="房号" width="125"><template #default="{row}">{{ row.roomCode }} <el-tag v-if="row.isTest" size="small" type="info">测试</el-tag></template></el-table-column>
      <el-table-column label="游戏" width="100"><template #default="{row}">{{ row.gameId==='6003'?'斗地主':row.gameId }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{row}">{{ statusText(row.status) }}</template></el-table-column>
      <el-table-column prop="ownerUid" label="房主 UID" min-width="120" /><el-table-column prop="creatorUid" label="创建者 UID" min-width="120" />
      <el-table-column prop="clubId" label="亲友圈" min-width="110" /><el-table-column prop="participantCount" label="参与者" width="85" />
      <el-table-column label="当前局号" width="110"><template #default="{row}">{{ row.roundNo || '未开始' }}</template></el-table-column>
      <el-table-column label="创建时间" min-width="180"><template #default="{row}">{{ gameTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" @click="detail(row.roomId)">详情</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="!error" v-model:current-page="page" :total="total" :page-size="50" layout="total, prev, pager, next" class="pagination" @change="load" />
    <el-dialog v-model="exportDialog" title="导出当前筛选" width="min(560px,95%)" :close-on-click-modal="!exporting">
      <p>导出已查询日期和筛选范围内的房间记录，最多一万行，内容以生成时读取为准。文件生成后保留 24 小时，下载时会重新检查权限。</p>
      <p>{{ applied.start }} 至 {{ applied.end }}；房号：{{ applied.roomCode||'全部' }}；房主：{{ applied.ownerUid||'全部' }}；创建者：{{ applied.creatorUid||'全部' }}；状态：{{ applied.status?statusText(applied.status):'全部' }}</p>
      <el-input v-model="exportReason" type="textarea" maxlength="512" show-word-limit placeholder="填写导出用途（至少 3 个字）" :disabled="exporting||exportUncertain" />
      <el-alert v-if="exportError" :title="exportError" type="error" :closable="false" class="export-feedback" />
      <template #footer><el-button :disabled="exporting" @click="exportDialog=false">关闭</el-button><el-button type="primary" :loading="exporting" :disabled="exportReason.trim().length<3" @click="submitExport">{{ exportUncertain?'重试原任务请求':'创建导出任务' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="jobsDialog" title="我的房间导出任务" width="min(960px,95%)">
      <el-alert v-if="jobsError" :title="jobsError" type="error" :closable="false" />
      <el-alert v-else-if="!generationEnabled" title="导出生成已暂停，已完成且未过期的文件仍可按当前授权下载。" type="warning" :closable="false" />
      <el-table v-loading="jobsLoading" :data="exportJobs" :empty-text="jobsError?'任务查询未完成':'当前授权范围内暂无导出任务'">
        <el-table-column label="创建时间" min-width="180"><template #default="{row}">{{ gameTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{row}">{{ exportLabels[row.status as keyof typeof exportLabels]||row.status }}</template></el-table-column>
        <el-table-column label="文件行数" width="100"><template #default="{row}">{{ row.status==='DONE'||row.status==='EXPIRED'?row.rowCount:'—' }}</template></el-table-column>
        <el-table-column label="到期时间" min-width="180"><template #default="{row}">{{ row.expiresAt?gameTime(row.expiresAt):'—' }}</template></el-table-column>
        <el-table-column label="失败原因" min-width="160"><template #default="{row}">{{ exportErrorLabel(row.lastErrorCode) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button v-if="row.status==='DONE'" link type="primary" :loading="downloading===row.id" @click="downloadExport(row.id)">下载</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button :loading="jobsLoading" @click="loadJobs">刷新任务</el-button><el-button @click="jobsDialog=false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="show" title="房间记录" width="min(900px,95%)">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
      <div v-loading="detailLoading">
        <template v-if="selected">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="房号">{{ selected.roomCode }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusText(selected.status) }}</el-descriptions-item>
            <el-descriptions-item label="房主 UID">{{ selected.ownerUid }}</el-descriptions-item><el-descriptions-item label="创建者 UID">{{ selected.creatorUid }}</el-descriptions-item>
            <el-descriptions-item label="固定付款人 UID">{{ selected.payerUid || '未指定' }}</el-descriptions-item><el-descriptions-item label="亲友圈">{{ selected.clubId || '无' }}</el-descriptions-item>
            <el-descriptions-item label="当前局号">{{ selected.roundNo || '未开始' }}</el-descriptions-item><el-descriptions-item label="约定局数">{{ selected.playNumber || '未记录' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ gameTime(selected.createdAt) }}</el-descriptions-item><el-descriptions-item label="结束时间">{{ selected.endedAt?gameTime(selected.endedAt):'—' }}</el-descriptions-item>
            <el-descriptions-item label="实例编号" :span="2"><span class="identifier">{{ selected.roomId }}</span></el-descriptions-item>
          </el-descriptions>
          <h4>参与者</h4><el-table :data="players" empty-text="暂无参与者"><el-table-column label="座位" width="90"><template #default="{row}">{{ row.seat+1 }}</template></el-table-column><el-table-column prop="uid" label="UID" /><el-table-column prop="name" label="昵称" /><el-table-column label="加入时间" min-width="180"><template #default="{row}">{{ gameTime(row.joinedAt) }}</template></el-table-column></el-table>
          <h4>运行记录</h4><el-descriptions :column="2" border><el-descriptions-item label="阶段">{{ selected.phase }}</el-descriptions-item><el-descriptions-item label="版本">{{ selected.version }}</el-descriptions-item><el-descriptions-item label="恢复点">{{ selected.snapshotSeq }}</el-descriptions-item><el-descriptions-item label="规则版本">{{ selected.ruleVersion }}</el-descriptions-item><el-descriptions-item label="引擎版本" :span="2">{{ selected.engineVersion }}</el-descriptions-item></el-descriptions>
        </template>
      </div>
      <template #footer><el-button @click="show=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { gameRooms, gameRoom, type GameRoom, type RoomPlayer, type RoomStatus, type RoomQuery } from '@/api/rooms'
import { gameTime } from '@/utils/game-time'
import { roomExportJobs,createRoomExport,downloadRoomExport,type RoomExportJob } from '@/api/room-exports'
const labels:Record<RoomStatus,string>={WAITING:'等待中',PLAYING:'进行中',ROUND_FINISHED:'单局结束',CLOSED:'已关闭'}
function statusText(status:string){return labels[status as RoomStatus]||status}
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date()),dates=ref<[string,string]>([today,today])
const query=reactive({roomCode:'',ownerUid:'',creatorUid:'',status:'' as RoomStatus|''})
const valid=computed(()=>Boolean(dates.value?.length===2)&&(!query.roomCode||/^[0-9]{6}$/.test(query.roomCode))&&[query.ownerUid,query.creatorUid].every(value=>!value||/^[1-9][0-9]{0,8}$/.test(value)))
const applied=ref<RoomQuery>({start:today,end:today,pageNum:1,pageSize:50}),rows=ref<GameRoom[]>([]),page=ref(1),total=ref(0),loading=ref(false),error=ref('')
let sequence=0
function search(){if(!valid.value)return;applied.value={roomCode:query.roomCode||undefined,ownerUid:query.ownerUid||undefined,creatorUid:query.creatorUid||undefined,status:query.status||undefined,start:dates.value[0],end:dates.value[1],pageNum:1,pageSize:50};page.value=1;void load()}
async function load(){const seq=++sequence;loading.value=true;error.value='';try{const result=await gameRooms({...applied.value,pageNum:page.value});if(seq===sequence){rows.value=result.rows;total.value=result.total}}catch{if(seq===sequence){rows.value=[];total.value=0;error.value='房间查询失败，请检查日期范围与数据授权后重试。'}}finally{if(seq===sequence)loading.value=false}}
const show=ref(false),selected=ref<GameRoom|null>(null),players=ref<RoomPlayer[]>([]),detailLoading=ref(false),detailError=ref('')
let detailSequence=0
async function detail(id:string){const seq=++detailSequence;show.value=true;selected.value=null;players.value=[];detailLoading.value=true;detailError.value='';try{const result=await gameRoom(id);if(seq===detailSequence){selected.value=result.data.room;players.value=result.data.players}}catch{if(seq===detailSequence)detailError.value='房间详情加载失败或数据授权已撤销。'}finally{if(seq===detailSequence)detailLoading.value=false}}
const exportDialog=ref(false),exportReason=ref(''),exporting=ref(false),exportError=ref(''),exportUncertain=ref(false),jobsDialog=ref(false),jobsLoading=ref(false),jobsError=ref(''),generationEnabled=ref(true),exportJobs=ref<RoomExportJob[]>([]),downloading=ref('')
const exportLabels={READY:'等待生成',RUNNING:'生成中',DONE:'已完成',FAILED:'生成失败',EXPIRED:'已过期'}
let exportKey='',exportQuery:RoomQuery={...applied.value},jobSequence=0
function exportErrorLabel(code?:string){return code?({AUTH_CHANGED:'权限或范围已改变',EXPORT_LIMIT:'超过生成容量',GENERATE_FAILED:'生成未完成',LEASE_EXHAUSTED:'恢复次数已用尽',FILTER_INVALID:'任务筛选数据不可用',STOPPED:'服务停止后等待恢复'} as Record<string,string>)[code]||'生成未完成':'—'}
function openExport(){exportQuery={...applied.value};exportReason.value='';exportError.value='';exportUncertain.value=false;exportKey=crypto.randomUUID();exportDialog.value=true}
async function submitExport(){if(exporting.value||exportReason.value.trim().length<3)return;exporting.value=true;exportError.value='';try{await createRoomExport(exportQuery,exportReason.value,exportKey);exportDialog.value=false;openJobs()}catch{exportUncertain.value=true;exportError.value='创建结果尚未确认。可先查看导出任务，或保持原请求重试；请勿反复新建。'}finally{exporting.value=false}}
function openJobs(){jobsDialog.value=true;void loadJobs()}
async function loadJobs(){const seq=++jobSequence;jobsLoading.value=true;jobsError.value='';try{const result=await roomExportJobs();if(seq===jobSequence){exportJobs.value=result.data.jobs.rows;generationEnabled.value=result.data.generationEnabled}}catch{if(seq===jobSequence){exportJobs.value=[];jobsError.value='导出任务查询失败或授权已撤销，请重新检查。'}}finally{if(seq===jobSequence)jobsLoading.value=false}}
async function downloadExport(id:string){if(downloading.value)return;downloading.value=id;try{await downloadRoomExport(id)}catch{exportJobs.value=[];jobsError.value='文件下载失败、已过期或授权已撤销。请刷新任务确认。'}finally{downloading.value=''}}
void load()
</script>
<style scoped>.feedback{margin-bottom:16px}.export-feedback{margin-top:12px}.pagination{justify-content:flex-end;margin-top:20px}.identifier{overflow-wrap:anywhere;font-family:monospace}h4{margin:22px 0 12px}</style>
