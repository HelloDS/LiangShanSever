<template>
  <div class="top-right-btn" :style="style">
    <el-row>
      <el-tooltip class="item" effect="dark" :content="showSearch ? '隐藏搜索' : '显示搜索'" placement="top" v-if="search">
        <el-button circle icon="Search" @click="toggleSearch()" />
      </el-tooltip>
      <el-tooltip class="item" effect="dark" content="刷新" placement="top">
        <el-button circle icon="Refresh" @click="refresh()" />
      </el-tooltip>
      <el-tooltip class="item" effect="dark" content="显隐列" placement="top" v-if="Object.keys(columns).length > 0">
        <el-button circle icon="Menu" @click="showColumn()" v-if="showColumnsType == 'transfer'"/>
        <el-dropdown trigger="click" :hide-on-click="false" style="padding-left: 12px" v-if="showColumnsType == 'checkbox'">
          <el-button circle icon="Menu" />
          <template #dropdown>
            <el-dropdown-menu>
              <!-- 全选/反选 按钮 -->
              <el-dropdown-item>
                <el-checkbox :indeterminate="isIndeterminate" :model-value="isChecked" @change="toggleCheckAll"> 列展示 </el-checkbox>
              </el-dropdown-item>
              <div class="check-line"></div>
              <template v-for="entry in columnEntries" :key="entry.key">
                <el-dropdown-item>
                  <el-checkbox v-model="entry.column.visible" @change="checkboxChange($event, entry.key)" :label="entry.column.label" />
                </el-dropdown-item>
              </template>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-tooltip>
    </el-row>
    <el-dialog :title="title" v-model="open" append-to-body>
      <el-transfer
        :titles="['显示', '隐藏']"
        v-model="value"
        :data="transferData"
        @change="dataChange"
      ></el-transfer>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PropType } from 'vue'
import type { CheckboxValueType, TransferKey } from 'element-plus'
import { useAppProxy } from '@/composables/useAppProxy'
import cache from '@/plugins/cache'
import type { TableShowColumns } from '@/types/api/common'
const props=defineProps({
  showSearch:{type:Boolean,default:true},
  columns:{type:[Array,Object] as PropType<TableShowColumns[]|Record<string,TableShowColumns>>,default:()=>({})},
  search:{type:Boolean,default:true},showColumnsType:{type:String,default:'checkbox'},gutter:{type:Number,default:10},storageKey:{type:String,default:''}
})
const emits=defineEmits<{'update:showSearch':[value:boolean];queryTable:[]}>()
const proxy=useAppProxy(),value=ref<string[]>([]),title=ref('显示/隐藏'),open=ref(false)
const columnEntries=computed(()=>Object.entries(props.columns).map(([key,column])=>({key,column})))
const isChecked=computed(()=>columnEntries.value.length>0&&columnEntries.value.every(entry=>entry.column.visible))
const isIndeterminate=computed(()=>columnEntries.value.some(entry=>entry.column.visible)&&!isChecked.value)
const transferData=computed(()=>columnEntries.value.map(entry=>({key:entry.key,label:entry.column.label})))
const style=computed(()=>({marginRight:`${props.gutter/2}px`}))
function toggleSearch(){
  let element:HTMLElement|null=proxy.$el instanceof HTMLElement?proxy.$el:null
  let form:HTMLElement|null=null
  while((element=element?.parentElement??null)&&element!==document.body){form=element.querySelector('.el-form');if(form)break}
  if(!form){emits('update:showSearch',!props.showSearch);return}
  animateSearch(form,props.showSearch)
}
function animateSearch(el: HTMLElement, isHide: boolean): void {
  const DURATION = 260
  const TRANSITION = 'max-height 0.25s ease, opacity 0.2s ease'
  const clear = () => Object.assign(el.style, { transition: '', maxHeight: '', opacity: '', overflow: '' })
  Object.assign(el.style, { overflow: 'hidden', transition: '' })
  if (isHide) {
    Object.assign(el.style, { maxHeight: el.scrollHeight + 'px', opacity: '1', transition: TRANSITION })
    requestAnimationFrame(() => Object.assign(el.style, { maxHeight: '0', opacity: '0' }))
    setTimeout(() => { emits('update:showSearch', false); clear() }, DURATION)
  } else {
    emits('update:showSearch', true)
    nextTick(() => {
      Object.assign(el.style, { maxHeight: '0', opacity: '0' })
      requestAnimationFrame(() => requestAnimationFrame(() => {
        Object.assign(el.style, { transition: TRANSITION, maxHeight: el.scrollHeight + 'px', opacity: '1' })
      }))
      setTimeout(clear, DURATION)
    })
  }
}


function refresh(){emits('queryTable')}
function dataChange(keys:TransferKey[]){const hidden=new Set(keys.map(String));for(const entry of columnEntries.value)entry.column.visible=!hidden.has(entry.key);saveStorage()}
function showColumn(){open.value=true}
function checkboxChange(event:CheckboxValueType,key:string){const entry=columnEntries.value.find(entry=>entry.key===key);if(entry)entry.column.visible=event===true;saveStorage()}
function toggleCheckAll(event:CheckboxValueType){for(const entry of columnEntries.value)entry.column.visible=event===true;saveStorage()}
function saveStorage(){
  if(!props.storageKey)return
  try{cache.local.setJSON(props.storageKey,Object.fromEntries(columnEntries.value.map(entry=>[entry.key,entry.column.visible])))}catch{/* 浏览器不允许持久存储时保留本次页面内状态。 */}
}
if(props.storageKey){
  try{
    const saved:unknown=cache.local.getJSON(props.storageKey)
    if(saved&&typeof saved==='object')for(const entry of columnEntries.value){const visible=(saved as Record<string,unknown>)[entry.key];if(typeof visible==='boolean')entry.column.visible=visible}
  }catch{/* 不完整的旧偏好不阻止表格显示。 */}
}
watch(columnEntries,entries=>{const hidden=entries.filter(entry=>!entry.column.visible).map(entry=>entry.key);if(hidden.join()!==value.value.join())value.value=hidden},{deep:true,immediate:true})
</script>

<style lang='scss' scoped>
:deep(.el-transfer__button) {
  border-radius: 50%;
  display: block;
  margin-left: 0px;
}
:deep(.el-transfer__button:first-child) {
  margin-bottom: 10px;
}
:deep(.el-dropdown-menu__item) {
  line-height: 30px;
  padding: 0 17px;
}
.check-line {
  width: 90%;
  height: 1px;
  background-color: #ccc;
  margin: 3px auto;
}
</style>
