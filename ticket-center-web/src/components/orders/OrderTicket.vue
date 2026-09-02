<script setup lang="ts">
import { computed } from 'vue'
import { CircleX, Clock3, TicketCheck } from 'lucide-vue-next'
import type { TicketOrder } from '../../types/api'
import { formatDateTime, formatPrice } from '../../utils/format'

const props = withDefaults(
  defineProps<{
    order: TicketOrder
    loadingAction?: 'pay' | 'cancel' | null
  }>(),
  { loadingAction: null },
)

const emit = defineEmits<{
  pay: [order: TicketOrder]
  cancel: [order: TicketOrder]
}>()

const status = computed(() => {
  if (props.order.status === 0) {
    return { label: props.order.statusDesc || '待支付', type: 'warning' as const, icon: Clock3 }
  }
  if (props.order.status === 1) {
    return { label: props.order.statusDesc || '已出票', type: 'success' as const, icon: TicketCheck }
  }
  return { label: props.order.statusDesc || '已取消', type: 'info' as const, icon: CircleX }
})
</script>

<template>
  <el-card class="order-ticket" shadow="never" :body-style="{ padding: '0' }">
    <article class="order-ticket__layout">
      <div class="order-ticket__main">
        <div class="order-ticket__heading">
          <div class="order-ticket__heading-copy">
            <h3>{{ order.eventName }}</h3>
            <p>{{ order.ticketTitle }}</p>
          </div>
          <strong class="order-ticket__price">{{ formatPrice(order.price) }}</strong>
        </div>

        <el-descriptions :column="3" size="small" class="order-ticket__meta">
          <el-descriptions-item label="订单号">
            <span class="order-ticket__id">{{ order.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="预约时间">
            {{ formatDateTime(order.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.payTime" label="支付时间">
            {{ formatDateTime(order.payTime) }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <aside class="order-ticket__stub">
        <el-tag :type="status.type" effect="light">
          <component :is="status.icon" :size="14" aria-hidden="true" />
          {{ status.label }}
        </el-tag>

        <div v-if="order.status === 0" class="order-ticket__actions">
          <el-button
            type="primary"
            :loading="loadingAction === 'pay'"
            :disabled="loadingAction !== null"
            @click="emit('pay', order)"
          >
            立即支付
          </el-button>
          <el-button
            type="danger"
            plain
            :loading="loadingAction === 'cancel'"
            :disabled="loadingAction !== null"
            @click="emit('cancel', order)"
          >
            取消预约
          </el-button>
        </div>
      </aside>
    </article>
  </el-card>
</template>

<style scoped>
.order-ticket {
  border-radius: var(--radius-sm);
}

.order-ticket__layout {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 10rem;
}

.order-ticket__main {
  min-width: 0;
  display: grid;
  gap: var(--space-5);
  padding: var(--space-5);
}

.order-ticket__heading {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.order-ticket__heading-copy {
  min-width: 0;
}

.order-ticket__heading h3 {
  overflow: hidden;
  font-size: var(--text-subheading);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-ticket__heading p {
  margin-top: var(--space-1);
  color: var(--color-ink-soft);
  font-size: var(--text-secondary);
}

.order-ticket__price {
  flex: 0 0 auto;
  color: var(--color-danger);
  font-size: var(--text-section);
  font-variant-numeric: tabular-nums;
}

.order-ticket__id {
  overflow-wrap: anywhere;
  font-family: var(--font-mono);
}

.order-ticket__stub {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: center;
  gap: var(--space-4);
  padding: var(--space-4);
  border-left: 1px dashed var(--color-border-strong);
  background: var(--color-surface-muted);
}

.order-ticket__stub::before,
.order-ticket__stub::after {
  content: '';
  position: absolute;
  left: -7px;
  width: 12px;
  height: 12px;
  border: 1px solid var(--color-border-strong);
  border-radius: 50%;
  background: var(--color-canvas);
}

.order-ticket__stub::before { top: -7px; }
.order-ticket__stub::after { bottom: -7px; }

.order-ticket__stub :deep(.el-tag) {
  align-self: center;
  gap: var(--space-1);
}

.order-ticket__actions {
  display: grid;
  gap: var(--space-2);
}

.order-ticket__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

@media (max-width: 42rem) {
  .order-ticket__layout {
    grid-template-columns: 1fr;
  }

  .order-ticket__stub {
    border-top: 1px dashed var(--color-border-strong);
    border-left: 0;
  }

  .order-ticket__stub::before,
  .order-ticket__stub::after {
    display: none;
  }

  .order-ticket__actions {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 30rem) {
  .order-ticket__heading {
    flex-direction: column;
  }

  .order-ticket__meta :deep(.el-descriptions__body) {
    overflow-x: auto;
  }
}
</style>
