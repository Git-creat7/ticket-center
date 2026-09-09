<script setup lang="ts">
import { computed } from 'vue'
import type { TicketReservation } from '../../types/api'
import { formatDateTime } from '../../utils/format'
import { reservationStatus } from '../../utils/reservations'

const props = defineProps<{ reservation: TicketReservation }>()
const status = computed(() => reservationStatus(props.reservation))
const orderFilter = computed(() => {
  if (props.reservation.orderStatus === 0) return 'pending'
  if (props.reservation.orderStatus === 1) return 'issued'
  return 'cancelled'
})
</script>

<template>
  <article class="reservation-record">
    <header class="reservation-record__heading">
      <h3>{{ reservation.ticketTitle || `票档 ${reservation.ticketId}` }}</h3>
      <el-tag :type="status.type">{{ status.label }}</el-tag>
    </header>
    <dl class="reservation-record__meta">
      <div><dt>预约号</dt><dd class="reservation-record__id">{{ reservation.id }}</dd></div>
      <div><dt>预约时间</dt><dd>{{ formatDateTime(reservation.createTime) }}</dd></div>
      <div v-if="reservation.orderId">
        <dt>订单号</dt><dd class="reservation-record__id">{{ reservation.orderId }}</dd>
      </div>
      <div v-if="reservation.orderStatus === 0 && reservation.paymentDeadline">
        <dt>支付截止</dt><dd>{{ formatDateTime(reservation.paymentDeadline) }}</dd>
      </div>
    </dl>
    <p v-if="reservation.status === 0" class="reservation-record__message" role="status">预约正在处理</p>
    <p v-if="reservation.status === 2" class="reservation-record__failure" role="status">
      {{ reservation.failureReason || '预约未成功' }}
    </p>
    <p v-if="reservation.releasePending" class="reservation-record__message" role="status">名额正在释放，完成后可重新预约</p>
    <RouterLink
      v-if="reservation.orderId"
      v-slot="{ navigate }"
      custom
      :to="{ path: '/orders', query: { status: orderFilter } }"
    >
      <el-button :type="reservation.orderStatus === 0 ? 'primary' : 'default'" @click="navigate">
        {{ reservation.orderStatus === 0 ? '前往支付' : '查看订单' }}
      </el-button>
    </RouterLink>
  </article>
</template>

<style scoped>
.reservation-record {
  min-width: 0;
  padding: var(--space-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.reservation-record__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.reservation-record__heading h3 {
  min-width: 0;
  font-size: var(--text-subheading);
  overflow-wrap: anywhere;
}

.reservation-record__heading :deep(.el-tag) {
  flex-shrink: 0;
  color: var(--color-ink);
}

.reservation-record__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3) var(--space-5);
  margin-block: var(--space-4);
  font-size: var(--text-secondary);
}

.reservation-record__meta dt {
  color: var(--color-ink-soft);
}

.reservation-record__meta dd {
  margin: var(--space-1) 0 0;
}

.reservation-record__id {
  font-family: var(--font-mono);
  overflow-wrap: anywhere;
}

.reservation-record__message {
  color: var(--color-ink-soft);
}

.reservation-record__failure {
  color: color-mix(in srgb, var(--color-danger) 65%, var(--color-ink));
}

.reservation-record__message,
.reservation-record__failure {
  margin-block: var(--space-3);
  overflow-wrap: anywhere;
}

@media (max-width: 36rem) {
  .reservation-record__meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
