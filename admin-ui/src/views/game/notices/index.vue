<template>
  <div class="app-container">
    <div class="toolbar"><div><h3>大厅公告</h3><p>发布后展示在玩家公告面板，最多同时展示 4 条。</p></div><el-button v-hasPermi="['game:notice:edit']" type="primary" @click="edit()">新建草稿</el-button></div>
    <el-table :data="rows" v-loading="loading" empty-text="暂无公告">
      <el-table-column prop="id" label="编号" width="90" /><el-table-column prop="title" label="当前标题" min-width="160" />
      <el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="row.published?'success':'info'">{{ row.published?'已发布':'未发布' }}</el-tag></template></el-table-column>
      <el-table-column prop="version" label="发布版本" width="110" /><el-table-column label="更新时间" min-width="180"><template #default="{row}">{{ gameTime(row.updatedAt) }}</template></el-table-column>
      <el-table-column label="操作" min-width="220"><template #default="{row}"><el-button v-hasPermi="['game:notice:edit']" link type="primary" @click="edit(row)">新版本草稿</el-button><el-button link type="primary" @click="history(row.id)">版本记录</el-button><el-button v-if="row.published" v-hasPermi="['game:notice:publish']" link type="warning" @click="prepareAction(row.id,'unpublish')">下架</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="showEditor" title="公告草稿" width="850px" :close-on-click-modal="false">
      <div class="editor"><el-form label-position="top" class="form"><el-form-item label="标题"><el-input v-model="draft.title" maxlength="12" show-word-limit /></el-form-item><el-form-item label="正文"><el-input v-model="draft.content" type="textarea" :rows="12" maxlength="450" show-word-limit /></el-form-item><el-form-item label="修改原因"><el-input v-model="draft.reason" maxlength="512" /></el-form-item><el-button @click="preview" :loading="previewing">预览</el-button></el-form><div class="preview"><img v-if="previewUrl" :src="previewUrl" alt="公告预览" /><el-empty v-else description="填写内容后预览" /></div></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template #footer><el-button @click="showEditor=false" :disabled="saving">取消</el-button><el-button type="primary" @click="save" :loading="saving" :disabled="draft.reason.trim().length<3">保存草稿</el-button></template>
    </el-dialog>
    <el-dialog v-model="showHistory" title="公告版本" width="760px"><el-table :data="versions"><el-table-column prop="version" label="版本" width="80" /><el-table-column label="标题"><template #default="{row}">{{ payload(row.payload).title }}</template></el-table-column><el-table-column prop="status" label="状态" width="130" /><el-table-column label="操作" width="160"><template #default="{row}"><el-button v-hasPermi="['game:notice:publish']" link type="primary" @click="prepareAction(selectedId,row.status==='DRAFT'?'publish':'rollback',row.version)">{{ row.status==='DRAFT'?'发布':'回滚至此版本' }}</el-button></template></el-table-column></el-table></el-dialog>
    <el-dialog v-model="showAction" :title="actionTitle" width="470px" :close-on-click-modal="false"><el-input v-model="action.reason" type="textarea" :rows="3" maxlength="512" placeholder="填写操作原因，至少 3 字" /><el-alert v-if="error" :title="error" type="error" :closable="false" /><template #footer><el-button @click="showAction=false" :disabled="saving">取消</el-button><el-button type="primary" :loading="saving" :disabled="action.reason.trim().length<3" @click="submitAction">确认{{ actionTitle }}</el-button></template></el-dialog>
  </div>
</template>
<script setup lang="ts">
import {ref,reactive,computed,onMounted,onUnmounted} from 'vue'
import {ElMessage} from 'element-plus'
import {gameTime} from '@/utils/game-time'
import {notices,noticeVersions,noticeDraft,noticeAction,noticePreview} from '@/api/game'
const rows=ref<any[]>([]),versions=ref<any[]>([]),loading=ref(false),saving=ref(false),showEditor=ref(false),showHistory=ref(false),showAction=ref(false),selectedId=ref(''),error=ref(''),previewUrl=ref(''),previewing=ref(false)
const draft=reactive<{id:string|null;title:string;content:string;reason:string;key:string}>({id:null,title:'',content:'',reason:'',key:''})
const action=reactive({id:'',kind:'',version:0,reason:'',key:''})
const titles:Record<string,string>={publish:'发布公告',rollback:'回滚公告',unpublish:'下架公告'}
const actionTitle=computed(()=>titles[action.kind])
const payload=(value:any)=>typeof value==='string'?JSON.parse(value):value
async function load(){loading.value=true;try{const response:any=await notices();rows.value=response.rows}finally{loading.value=false}}
function clearPreview(){if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value=''}
function edit(row?:any){clearPreview();Object.assign(draft,{id:row?.id||null,title:row?.title||'',content:row?.content||'',reason:'',key:crypto.randomUUID()});error.value='';showEditor.value=true}
async function preview(){previewing.value=true;try{const blob:any=await noticePreview(draft);if(!blob.type.includes('image/png'))throw new Error('invalid image');clearPreview();previewUrl.value=URL.createObjectURL(blob);error.value=''}catch{error.value='无法生成预览，请检查正文长度和换行。'}finally{previewing.value=false}}
async function save(){saving.value=true;try{const response:any=await noticeDraft({id:draft.id,title:draft.title,content:draft.content,reason:draft.reason},draft.key);showEditor.value=false;ElMessage.success('草稿已保存');await load();await history(response.data.result.id)}catch{error.value='草稿保存未确认，可使用相同内容重试。'}finally{saving.value=false}}
async function history(id:string){selectedId.value=id;const response:any=await noticeVersions(id);versions.value=response.data;showHistory.value=true}
function prepareAction(id:string,kind:string,version=0){Object.assign(action,{id,kind,version,reason:'',key:crypto.randomUUID()});error.value='';showAction.value=true}
async function submitAction(){saving.value=true;try{await noticeAction(action.id,action.kind,{reason:action.reason,version:action.version},action.key);showAction.value=false;showHistory.value=false;ElMessage.success('操作已完成');await load()}catch{error.value='操作未确认完成，可使用相同内容重试。'}finally{saving.value=false}}
onMounted(load);onUnmounted(clearPreview)
</script>
<style scoped>.toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:22px}.toolbar p{font-size:13px;color:#768077}.editor{display:grid;grid-template-columns:1fr 280px;gap:24px}.preview{background:#f4f7f4;display:flex;align-items:center;justify-content:center;min-height:320px}.preview img{width:280px;height:320px;object-fit:contain}.el-alert{margin-top:16px}@media(max-width:700px){.editor{grid-template-columns:1fr}}</style>
