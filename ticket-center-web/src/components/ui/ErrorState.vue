<script setup lang="ts">
import { RefreshCw } from 'lucide-vue-next'

defineProps<{
  title?: string
  message: string
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <el-result class="app-error-state" icon="error" role="alert" aria-live="assertive">
    <template #title>
      <h2 class="section-heading">{{ title || '暂时无法加载' }}</h2>
    </template>
    <template #sub-title>
      <p>{{ message }}</p>
    </template>
    <template #extra>
      <el-button type="primary" :icon="RefreshCw" @click="$emit('retry')">
        重新加载
      </el-button>
    </template>
  </el-result>
</template>

<style scoped>
.app-error-state {
  min-height: 16rem;
}
</style>
