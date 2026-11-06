<template>
  <el-col :span="element.span" :class="className" @click.stop="activeItem(element)">
    <el-form-item :label="element.label" :label-width="element.labelWidth ? element.labelWidth + 'px' : undefined"
      :required="element.required" v-if="element.layout === 'colFormItem'">
      <render :key="element.tag" :conf="element" v-model="element.defaultValue" />
    </el-form-item>
    <el-row :gutter="element.gutter" :class="element.class" @click.stop="activeItem(element)" v-else>
      <span class="component-name"> {{ element.componentName }} </span>
      <draggable group="componentsGroup" :animation="340" :list="element.children" class="drag-wrapper" item-key="label"
        ref="draggableItemRef" :component-data="getComponentData()">
        <template #item="scoped">
          <draggable-item :key="scoped.element.renderKey" :drawing-list="element.children" :element="scoped.element"
            :index="scoped.index" :active-id="activeId" :form-conf="formConf" @activeItem="activeItem(scoped.element)"
            @copyItem="copyItem(scoped.element, element.children)"
            @deleteItem="deleteItem(scoped.index, element.children)" />
        </template>
      </draggable>
    </el-row>
    <span class="drawing-item-copy" title="复制" @click.stop="copyItem(element)">
      <el-icon><CopyDocument /></el-icon>
    </span>
    <span class="drawing-item-delete" title="删除" @click.stop="deleteItem(index)">
      <el-icon><Delete /></el-icon>
    </span>
  </el-col>
</template>
<script setup lang="ts" name="DraggableItem">
import draggable from "vuedraggable/dist/vuedraggable.common"
import render from '@/utils/generator/render'
import type { PropType } from 'vue'
interface DrawingItem {span?:number;label?:string;labelWidth?:number;required?:boolean;layout?:string;tag?:string;defaultValue?:unknown;gutter?:number;class?:string;componentName?:string;children?:DrawingItem[];renderKey?:string|number;formId?:string|number;justify?:string;align?:string}

const props = defineProps({
  element: {type:Object as PropType<DrawingItem>,required:true},
  index: {type:Number,default:0},
  drawingList: {type:Array as PropType<DrawingItem[]>,default:()=>[]},
  activeId: {
    type: [String, Number]
  },
  formConf: {type:Object as PropType<{unFocusedComponentBorder?:boolean}>,default:()=>({})}
})
const className = ref('')
const draggableItemRef = ref(null)
const emits = defineEmits(['activeItem', 'copyItem', 'deleteItem'])

function activeItem(item: DrawingItem): void {
  emits('activeItem', item)
}
function copyItem(item: DrawingItem, parent?: DrawingItem[]): void {
  emits('copyItem', item, parent ?? props.drawingList)
}
function deleteItem(item: number | DrawingItem, parent?: DrawingItem[]): void {
  emits('deleteItem', item, parent ?? props.drawingList)
}

function getComponentData(): Record<string, any> {
  return {
    gutter: props.element.gutter,
    justify: props.element.justify,
    align: props.element.align
  }
}

watch(() => props.activeId, (val) => {
  className.value = (props.element.layout === 'rowFormItem' ? 'drawing-row-item' : 'drawing-item') + (val === props.element.formId ? ' active-from-item' : '')
  if (props.formConf.unFocusedComponentBorder) {
    className.value += ' unfocus-bordered'
  }
}, { immediate: true })
</script>
