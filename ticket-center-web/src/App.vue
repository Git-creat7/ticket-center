<script setup lang="ts">
import { nextTick, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppShell from './components/layout/AppShell.vue'

const route = useRoute()

watch(
  () => route.fullPath,
  async () => {
    await nextTick()
    document.querySelector<HTMLElement>('#main-content')?.focus({ preventScroll: true })
  },
)
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <AppShell>
    <RouterView />
  </AppShell>
</template>
