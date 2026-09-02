<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { imageOrFallback } from '../../utils/images'

// 演出海报图片组件属性定义
const props = withDefaults(
  defineProps<{
    src?: string | null
    alt: string
    eager?: boolean
    categoryName?: string
  }>(),
  { src: '', eager: false, categoryName: '' },
)

// 图片加载失败状态监控
const failed = ref(false)
const resolvedSource = computed(() =>
  failed.value ? imageOrFallback('', props.categoryName) : imageOrFallback(props.src, props.categoryName),
)

// 监听图片源变化并重置错误状态
watch(
  () => props.src,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <img
    class="event-image"
    :src="resolvedSource"
    :alt="alt"
    :loading="eager ? 'eager' : 'lazy'"
    decoding="async"
    width="1200"
    height="900"
    @error="failed = true"
  />
</template>

<style scoped lang="scss">
.event-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  background-color: var(--color-surface-muted);
}
</style>
