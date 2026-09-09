<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RefreshCw, ShoppingBag } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'

import OrderTicket from '../components/orders/OrderTicket.vue'
import ReservationList from '../components/orders/ReservationList.vue'
import WaitlistList from '../components/orders/WaitlistList.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import ErrorState from '../components/ui/ErrorState.vue'
import { orderApi } from '../services/api'
import { useNotificationStore } from '../stores/notifications'
import type { ApiId, OrderStatus, PageResult, TicketOrder } from '../types/api'
import { getErrorMessage } from '../utils/errors'

type OrderFilter = 'all' | 'pending' | 'issued' | 'cancelled'
type OrderAction = 'pay' | 'cancel'

const PAGE_SIZE = 8
const filterOptions: Array<{ value: OrderFilter; label: string; status: OrderStatus | null }> = [
  { value: 'all', label: '全部订单', status: null },
  { value: 'pending', label: '待支付', status: 0 },
  { value: 'issued', label: '已出票', status: 1 },
  { value: 'cancelled', label: '已取消', status: 2 },
]

const notifications = useNotificationStore()
const route = useRoute()
const router = useRouter()
const activeView = computed({
  get: () => route.query.view === 'waitlists' ? 'waitlists' : route.query.view === 'reservations' ? 'reservations' : 'orders',
  set: (value: string) => {
    void router.replace({ query: { ...route.query, view: value === 'orders' ? undefined : value } })
  },
})
const page = ref<PageResult<TicketOrder> | null>(null)
const currentPage = ref(1)
const selectedFilter = ref<OrderFilter>('all')
const loading = ref(true)
const loadError = ref('')
const actionError = ref('')
const loadingActions = ref<Record<string, OrderAction | undefined>>({})
const cancelDialogVisible = ref(false)
const pendingCancelOrder = ref<TicketOrder | null>(null)
let loadRequestId = 0

const selectedStatus = computed(() => (
  filterOptions.find((option) => option.value === selectedFilter.value)?.status ?? null
))

// 状态已由后端过滤，这里直接展示当前页结果。
const visibleOrders = computed(() => page.value?.records ?? [])

function orderKey(id: ApiId): string {
  return String(id)
}

async function loadOrders(): Promise<void> {
  const requestId = ++loadRequestId
  loading.value = true
  loadError.value = ''
  try {
    const result = await orderApi.mine({
      current: currentPage.value,
      size: PAGE_SIZE,
      ...(selectedStatus.value !== null ? { status: selectedStatus.value } : {}),
    })
    if (requestId === loadRequestId) page.value = result
  } catch (error) {
    if (requestId === loadRequestId) loadError.value = getErrorMessage(error, '订单加载失败，请稍后重试')
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}

// 筛选值可能由组件或按钮触发，统一重置分页并重新请求。
async function selectFilter(filter: OrderFilter): Promise<void> {
  selectedFilter.value = filter
  actionError.value = ''
  // 切换筛选回到第 1 页重新请求。
  currentPage.value = 1
  await router.replace({ query: { ...route.query, status: filter === 'all' ? undefined : filter } })
}

async function goToPage(target: number): Promise<void> {
  if (target < 1 || target === currentPage.value) return
  currentPage.value = target
  await loadOrders()
  const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
  window.scrollTo({ top: 0, behavior })
}

async function pay(order: TicketOrder): Promise<void> {
  const key = orderKey(order.id)
  if (loadingActions.value[key]) return

  actionError.value = ''
  loadingActions.value[key] = 'pay'
  try {
    await orderApi.pay(order.id)
    notifications.notify('支付完成，电子门票已出票', 'success')
    await loadOrders()
  } catch (error) {
    actionError.value = getErrorMessage(error, '支付失败，请稍后重试')
  } finally {
    delete loadingActions.value[key]
  }
}

function requestCancel(order: TicketOrder): void {
  pendingCancelOrder.value = order
  actionError.value = ''
  cancelDialogVisible.value = true
}

function closeCancelDialog(): void {
  cancelDialogVisible.value = false
}

function clearPendingCancel(): void {
  pendingCancelOrder.value = null
}

async function confirmCancel(): Promise<void> {
  const order = pendingCancelOrder.value
  if (!order) return

  const key = orderKey(order.id)
  loadingActions.value[key] = 'cancel'
  try {
    await orderApi.cancel(order.id)
    cancelDialogVisible.value = false
    notifications.notify('预约已取消', 'success')
    await loadOrders()
  } catch (error) {
    cancelDialogVisible.value = false
    actionError.value = getErrorMessage(error, '取消预约失败，请稍后重试')
  } finally {
    delete loadingActions.value[key]
  }
}

watch(() => [route.query.view, route.query.status], () => {
  loadRequestId += 1
  if (activeView.value !== 'orders') return
  selectedFilter.value = filterOptions.find((option) => option.value === route.query.status)?.value ?? 'all'
  currentPage.value = 1
  void loadOrders()
}, { immediate: true })
onBeforeUnmount(() => { loadRequestId += 1 })
</script>

<template>
  <div class="page-container page-stack orders-page">
    <header class="page-header orders-header">
      <div>
        <h1 class="page-heading">我的票夹</h1>
      </div>
      <div v-if="activeView === 'orders'" class="orders-header__actions">
        <el-tag v-if="page && !loading" effect="plain">共 {{ page.total }} 笔订单</el-tag>
        <el-tooltip content="刷新订单">
          <el-button :loading="loading" :disabled="loading" aria-label="刷新订单" @click="loadOrders">
            <RefreshCw v-if="!loading" :size="18" aria-hidden="true" />
          </el-button>
        </el-tooltip>
      </div>
    </header>

    <el-tabs v-model="activeView">
      <el-tab-pane label="订单" name="orders">
        <div v-if="activeView === 'orders'" class="orders-view">
          <el-segmented
            v-model="selectedFilter"
            :options="filterOptions"
            aria-label="订单状态筛选"
            @change="selectFilter"
          />

          <el-skeleton v-if="loading" :rows="5" animated class="orders-loading" />
          <ErrorState v-else-if="loadError" :message="loadError" @retry="loadOrders" />

          <template v-else>
            <el-alert
              v-if="actionError"
              :title="actionError"
              type="error"
              show-icon
              closable
              @close="actionError = ''"
            />

            <EmptyState
              v-if="visibleOrders.length === 0"
              :icon="ShoppingBag"
              :title="selectedFilter === 'all' ? '票夹还是空的' : '当前没有该状态的订单'"
              :description="selectedFilter === 'all' ? '预约门票后，订单会保存在这里。' : '切换状态查看其他订单。'"
            >
              <RouterLink v-if="selectedFilter === 'all'" to="/discover">
                <el-button type="primary">浏览活动</el-button>
              </RouterLink>
              <el-button v-else @click="selectFilter('all')">查看全部订单</el-button>
            </EmptyState>

            <section v-else class="order-list" aria-label="订单列表">
              <OrderTicket
                v-for="order in visibleOrders"
                :key="orderKey(order.id)"
                :order="order"
                :loading-action="loadingActions[orderKey(order.id)]"
                @pay="pay(order)"
                @cancel="requestCancel(order)"
              />
            </section>

            <el-pagination
              v-if="page && page.pages > 1"
              class="orders-pagination"
              background
              layout="prev, pager, next"
              :current-page="currentPage"
              :page-size="PAGE_SIZE"
              :pager-count="5"
              :total="page.total"
              :disabled="loading"
              @current-change="goToPage"
            />
          </template>
        </div>
      </el-tab-pane>
      <el-tab-pane label="预约记录" name="reservations">
        <ReservationList v-if="activeView === 'reservations'" />
      </el-tab-pane>
      <el-tab-pane label="候补记录" name="waitlists">
        <WaitlistList v-if="activeView === 'waitlists'" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="cancelDialogVisible"
      title="取消预约"
      width="min(28rem, calc(100% - 2rem))"
      destroy-on-close
      @closed="clearPendingCancel"
    >
      <p class="cancel-copy">确认取消“{{ pendingCancelOrder?.eventName }} · {{ pendingCancelOrder?.ticketTitle }}”吗？</p>
      <template #footer>
        <el-button @click="closeCancelDialog">暂不取消</el-button>
        <el-button
          type="danger"
          :loading="Boolean(pendingCancelOrder && loadingActions[orderKey(pendingCancelOrder.id)] === 'cancel')"
          @click="confirmCancel"
        >
          确认取消
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.orders-page {
  max-width: 56rem;
  gap: var(--space-6);
}

.orders-header {
  align-items: center;
}

.orders-header__actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.orders-header__actions :deep(.el-button) {
  width: 44px;
  height: 44px;
  padding: 0;
}

.orders-view {
  display: grid;
  gap: var(--space-4);
}

.orders-page :deep(.el-segmented) {
  width: fit-content;
  max-width: 100%;
  overflow-x: auto;
}

.orders-loading {
  padding: var(--space-6);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.order-list {
  display: grid;
  gap: var(--space-4);
}

.orders-pagination {
  justify-content: center;
  margin-top: var(--space-4);
}

.cancel-copy {
  color: var(--color-ink-soft);
  line-height: 1.6;
}

@media (max-width: 36rem) {
  .orders-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
