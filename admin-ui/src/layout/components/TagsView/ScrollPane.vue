<template>
  <el-scrollbar
    ref="scrollContainer"
    :vertical="false"
    class="scroll-container"
    @wheel.prevent="handleScroll"
  >
    <slot />
  </el-scrollbar>
</template>

<script setup lang="ts">
import { useAppProxy } from '@/composables/useAppProxy'
import { useTemplateRef } from 'vue'
import type { ScrollbarInstance } from 'element-plus'
const scrollbar=useTemplateRef<ScrollbarInstance>('scrollContainer')
import useTagsViewStore from '@/store/modules/tagsView'

const tagAndTagSpacing = ref<number>(4)
const proxy = useAppProxy()

const scrollWrapper = computed(() => scrollbar.value?.wrapRef)

onMounted(() => {
  scrollWrapper.value?.addEventListener('scroll', emitScroll, true)
})

onBeforeUnmount(() => {
  scrollWrapper.value?.removeEventListener('scroll', emitScroll, true)
})

function handleScroll(e: WheelEvent): void {
  const eventDelta = -e.deltaY * 40
  const $scrollWrapper = scrollWrapper.value
  if (!$scrollWrapper) return
  $scrollWrapper.scrollLeft = $scrollWrapper.scrollLeft + eventDelta / 4
  emits('updateArrows')
}

function smoothScrollTo(target: number): void {
  const candidate = scrollWrapper.value
  if (!candidate) return
  const $scrollWrapper=candidate
  const start: number = $scrollWrapper.scrollLeft
  const distance: number = target - start
  const duration = 300
  let startTime: number | null = null

  function ease(t: number, b: number, c: number, d: number): number {
    t /= d / 2
    if (t < 1) return c / 2 * t * t + b
    t--
    return -c / 2 * (t * (t - 2) - 1) + b
  }

  function step(timestamp: number): void {
    if (!startTime) startTime = timestamp
    const elapsed = timestamp - startTime
    $scrollWrapper.scrollLeft = ease(elapsed, start, distance, duration)
    if (elapsed < duration) {
      requestAnimationFrame(step)
    } else {
      $scrollWrapper.scrollLeft = target
      emits('updateArrows')
    }
  }

  requestAnimationFrame(step)
}

const emits = defineEmits<{
  (e: 'scroll'): void
  (e: 'updateArrows'): void
}>()

const emitScroll = (): void => {
  emits('scroll')
  emits('updateArrows')
}

const tagsViewStore = useTagsViewStore()
const visitedViews = computed(() => tagsViewStore.visitedViews)

function moveToTarget(currentTag: any): void {
  const $container = scrollbar.value?.$el
  if (!($container instanceof HTMLElement)) return
  const $containerWidth = $container.offsetWidth
  const $scrollWrapper = scrollWrapper.value
  if (!$scrollWrapper) return

  let firstTag: any = null
  let lastTag: any = null

  if (visitedViews.value.length > 0) {
    firstTag = visitedViews.value[0]
    lastTag = visitedViews.value[visitedViews.value.length - 1]
  }

  if (firstTag === currentTag) {
    smoothScrollTo(0)
  } else if (lastTag === currentTag) {
    smoothScrollTo($scrollWrapper.scrollWidth - $containerWidth)
  } else {
    const tagListDom = document.getElementsByClassName('tags-view-item')
    const currentIndex = visitedViews.value.findIndex((item: any) => item === currentTag)
    let prevTag: HTMLElement | null = null
    let nextTag: HTMLElement | null = null
    for (const k in tagListDom) {
      if (k !== 'length' && Object.hasOwnProperty.call(tagListDom, k)) {
        if ((tagListDom[k] as HTMLElement).dataset?.path === visitedViews.value[currentIndex - 1].path) {
          prevTag = tagListDom[k] as HTMLElement
        }
        const element = tagListDom[k] as HTMLElement
        if (element.dataset?.path === visitedViews.value[currentIndex + 1].path) {
          nextTag = element
        }
      }
    }

    if (prevTag && nextTag) {
      const afterNextTagOffsetLeft = nextTag.offsetLeft + nextTag.offsetWidth + tagAndTagSpacing.value
      const beforePrevTagOffsetLeft = prevTag.offsetLeft - tagAndTagSpacing.value
      if (afterNextTagOffsetLeft > $scrollWrapper.scrollLeft + $containerWidth) {
        smoothScrollTo(afterNextTagOffsetLeft - $containerWidth)
      } else if (beforePrevTagOffsetLeft < $scrollWrapper.scrollLeft) {
        smoothScrollTo(beforePrevTagOffsetLeft)
      }
    }
  }
}

function scrollToStart(): void {
  smoothScrollTo(0)
}

function scrollToEnd(): void {
  const $scrollWrapper = scrollWrapper.value
  if (!$scrollWrapper) return
  smoothScrollTo($scrollWrapper.scrollWidth - $scrollWrapper.clientWidth)
}

function getScrollState(): { canLeft: boolean; canRight: boolean } {
  const $scrollWrapper = scrollWrapper.value
  if (!$scrollWrapper) return {canLeft:false,canRight:false}
  return {
    canLeft: $scrollWrapper.scrollLeft > 0,
    canRight: $scrollWrapper.scrollLeft < $scrollWrapper.scrollWidth - $scrollWrapper.clientWidth - 1
  }
}

defineExpose({
  moveToTarget,
  scrollToStart,
  scrollToEnd,
  getScrollState
})
</script>

<style lang='scss' scoped>
.scroll-container {
  white-space: nowrap;
  position: relative;
  overflow: hidden;
  width: 100%;
  :deep(.el-scrollbar__bar) {
    bottom: 0px;
  }
  :deep(.el-scrollbar__wrap) {
    height: 34px;
    display: flex;
    align-items: center;
  }
}
</style>