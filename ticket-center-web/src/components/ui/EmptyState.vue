<script setup lang="ts">
import type { Component } from 'vue'
import { CalendarSearch } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    title: string
    description: string
    icon?: Component
  }>(),
  { icon: CalendarSearch },
)
</script>

<template>
  <el-empty class="app-empty-state">
    <template #image>
      <el-icon :size="36" color="var(--el-text-color-secondary)">
        <component :is="icon" aria-hidden="true" />
      </el-icon>
    </template>
    <template #description>
      <h2 class="section-heading">{{ title }}</h2>
      <p class="app-empty-state__description">{{ description }}</p>
    </template>
    <slot />
  </el-empty>
</template>

<style scoped>
.app-empty-state {
  min-height: 16rem;
}

.app-empty-state__description {
  max-width: 42ch;
  margin-top: var(--space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--text-secondary);
}
</style>
