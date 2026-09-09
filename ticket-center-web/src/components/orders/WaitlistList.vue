<script setup lang="ts">
import { computed, ref } from 'vue'
import { ListOrdered, RefreshCw } from 'lucide-vue-next'
import EmptyState from '../ui/EmptyState.vue'
import ErrorState from '../ui/ErrorState.vue'
import { usePolling } from '../../composables/usePolling'
import { waitlistApi } from '../../services/api'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'
import type { TicketWaitlist } from '../../types/api'
import { getErrorMessage } from '../../utils/errors'
import { formatDateTime, formatPrice } from '../../utils/format'
import { waitlistStatus } from '../../utils/tickets'

const PAGE_SIZE = 8
const auth = useAuthStore()
const notifications = useNotificationStore()
const currentPage = ref(1)
const pendingCancel = ref<TicketWaitlist | null>(null)
const cancelVisible = ref(false)
const cancelling = ref(false)
const actionError = ref('')
const { data: page, error, loading, refreshing, refresh } = usePolling(
  () => auth.token ? `${auth.token}:${currentPage.value}` : null,
  (signal) => waitlistApi.mine({ current: currentPage.value, size: PAGE_SIZE }, signal),
  (result) => result.records.some((item) => item.status === 0 || item.status === 1 || item.orderStatus === 0),
)
const loadError = computed(() => error.value ? getErrorMessage(error.value, '候补记录加载失败') : '')

function requestCancel(item: TicketWaitlist) {
  pendingCancel.value = item
  actionError.value = ''
  cancelVisible.value = true
}

async function confirmCancel() {
  if (!pendingCancel.value || cancelling.value) return
  cancelling.value = true
  try {
    await waitlistApi.cancel(pendingCancel.value.id)
    notifications.notify('候补已取消', 'success')
    cancelVisible.value = false
  } catch (error) {
    actionError.value = getErrorMessage(error, '取消候补失败')
  } finally {
    cancelling.value = false
    refresh()
  }
}
</script>

<template>
  <section class="waitlist-list" aria-label="候补记录">
    <div class="waitlist-toolbar">
      <span v-if="page">共 {{ page.total }} 笔候补</span>
      <el-tooltip content="刷新候补记录">
        <el-button :loading="refreshing" :disabled="refreshing" aria-label="刷新候补记录" @click="refresh">
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
      <EmptyState v-if="page.records.length === 0" :icon="ListOrdered" title="暂无候补记录" description="">
        <RouterLink to="/discover"><el-button type="primary">浏览活动</el-button></RouterLink>
      </EmptyState>
      <article v-for="item in page.records" :key="String(item.id)" class="waitlist-record">
        <header class="waitlist-record__heading">
          <h3>{{ item.ticketTitle || `票档 ${item.ticketId}` }}</h3>
          <el-tag :type="waitlistStatus(item).type">{{ waitlistStatus(item).label }}</el-tag>
        </header>
        <dl class="waitlist-record__meta">
          <div><dt>候补号</dt><dd>{{ item.id }}</dd></div>
          <div><dt>入队时间</dt><dd>{{ formatDateTime(item.createTime) }}</dd></div>
          <div v-if="item.position && !item.orderId"><dt>当前顺序</dt><dd>第 {{ item.position }} 位</dd></div>
          <div><dt>入队票价</dt><dd>{{ formatPrice(item.price) }}</dd></div>
          <div v-if="item.orderStatus === 0 && item.paymentDeadline">
            <dt>支付截止</dt><dd>{{ formatDateTime(item.paymentDeadline) }}</dd>
          </div>
        </dl>
        <p v-if="item.failureReason" class="waitlist-record__message" role="status">{{ item.failureReason }}</p>
        <el-button v-if="item.status === 0" @click="requestCancel(item)">取消候补</el-button>
        <RouterLink
          v-if="item.orderId"
          v-slot="{ navigate }"
          custom
          :to="{ path: '/orders', query: { status: item.orderStatus === 0 ? 'pending' : item.orderStatus === 1 ? 'issued' : 'cancelled' } }"
        >
          <el-button :type="item.orderStatus === 0 ? 'primary' : 'default'" @click="navigate">
            {{ item.orderStatus === 0 ? '前往支付' : '查看订单' }}
          </el-button>
        </RouterLink>
      </article>
      <el-pagination
        v-if="page.pages > 1"
        v-model:current-page="currentPage"
        class="waitlist-pagination"
        background
        layout="prev, pager, next"
        :pager-count="5"
        :page-size="PAGE_SIZE"
        :total="page.total"
        :disabled="loading"
      />
    </template>
    <el-dialog
      v-model="cancelVisible"
      title="取消候补"
      width="min(28rem, calc(100% - 2rem))"
      :close-on-click-modal="!cancelling"
      :close-on-press-escape="!cancelling"
      :show-close="!cancelling"
    >
      <p>确认取消“{{ pendingCancel?.ticketTitle }}”的候补吗？重新加入将排在队尾。</p>
      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
      <template #footer>
        <el-button :disabled="cancelling" @click="cancelVisible = false">保留候补</el-button>
        <el-button type="danger" :loading="cancelling" @click="confirmCancel">确认取消</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.waitlist-list {
  display: grid;
  gap: var(--space-4);
}

.waitlist-toolbar,
.waitlist-record__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.waitlist-toolbar :deep(.el-button) {
  width: 44px;
  height: 44px;
  margin-left: auto;
  padding: 0;
}

.waitlist-record {
  min-width: 0;
  padding: var(--space-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.waitlist-record__heading {
  align-items: flex-start;
}

.waitlist-record__heading h3 {
  min-width: 0;
  font-size: var(--text-subheading);
  overflow-wrap: anywhere;
}

.waitlist-record__heading :deep(.el-tag) {
  flex-shrink: 0;
  color: var(--color-ink);
}

.waitlist-record__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3) var(--space-5);
  margin-block: var(--space-4);
  font-size: var(--text-secondary);
}

.waitlist-toolbar span,
.waitlist-record__meta dt,
.waitlist-record__message {
  color: var(--color-ink-soft);
}

.waitlist-record__meta dd {
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.waitlist-record__message {
  margin-block: var(--space-3);
}

.waitlist-record :deep(.el-button) {
  min-height: 44px;
}

.waitlist-pagination {
  justify-content: center;
}

@media (max-width: 36rem) {
  .waitlist-record__meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
