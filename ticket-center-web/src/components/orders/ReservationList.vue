<script setup lang="ts">
import { computed, ref } from 'vue'
import { ClipboardList, RefreshCw } from 'lucide-vue-next'
import ReservationRecord from './ReservationRecord.vue'
import EmptyState from '../ui/EmptyState.vue'
import ErrorState from '../ui/ErrorState.vue'
import { usePolling } from '../../composables/usePolling'
import { reservationApi } from '../../services/api'
import { useAuthStore } from '../../stores/auth'
import { getErrorMessage } from '../../utils/errors'

const PAGE_SIZE = 8
const auth = useAuthStore()
const currentPage = ref(1)
const { data: page, error, loading, refreshing, refresh } = usePolling(
  () => auth.token ? `${auth.token}:${currentPage.value}` : null,
  (signal) => reservationApi.mine({ current: currentPage.value, size: PAGE_SIZE }, signal),
  (result) => result.records.some((item) => item.status === 0 || item.releasePending || item.orderStatus === 0),
)
const loadError = computed(() => error.value ? getErrorMessage(error.value, '预约记录加载失败') : '')
</script>

<template>
  <section class="reservation-list" aria-label="预约记录">
    <div class="reservation-list__toolbar">
      <span v-if="page">共 {{ page.total }} 笔预约</span>
      <el-tooltip content="刷新预约记录">
        <el-button :loading="refreshing" :disabled="refreshing" aria-label="刷新预约记录" @click="refresh">
          <RefreshCw v-if="!refreshing" :size="18" aria-hidden="true" />
        </el-button>
      </el-tooltip>
    </div>
    <el-skeleton v-if="loading" :rows="5" animated />
    <ErrorState v-else-if="loadError && !page" :message="loadError" @retry="refresh" />
    <template v-else-if="page">
      <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false" show-icon>
        <el-button link @click="refresh">重新查询</el-button>
      </el-alert>
      <EmptyState v-if="page.records.length === 0" :icon="ClipboardList" title="暂无预约记录" description="">
        <RouterLink to="/discover"><el-button type="primary">浏览活动</el-button></RouterLink>
      </EmptyState>
      <ReservationRecord v-for="item in page.records" :key="String(item.id)" :reservation="item" />
      <el-pagination
        v-if="page.pages > 1"
        v-model:current-page="currentPage"
        class="reservation-list__pagination"
        background
        layout="prev, pager, next"
        :pager-count="5"
        :page-size="PAGE_SIZE"
        :total="page.total"
        :disabled="loading"
      />
    </template>
  </section>
</template>

<style scoped>
.reservation-list {
  display: grid;
  gap: var(--space-4);
}

.reservation-list__toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
}

.reservation-list__toolbar span {
  margin-right: auto;
  color: var(--color-ink-soft);
}

.reservation-list__toolbar :deep(.el-button) {
  width: 44px;
  height: 44px;
  padding: 0;
}

.reservation-list__pagination {
  justify-content: center;
}
</style>
