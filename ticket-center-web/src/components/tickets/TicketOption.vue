<script setup lang="ts">
import { computed } from 'vue'
import { Clock3, Flame, Ticket as TicketIcon } from 'lucide-vue-next'
import type { Ticket } from '../../types/api'
import { formatDateTime, formatPrice, parseBackendDateTime } from '../../utils/format'

const props = withDefaults(
  defineProps<{
    ticket: Ticket
    loading?: boolean
  }>(),
  { loading: false },
)

const emit = defineEmits<{
  reserve: [ticket: Ticket]
}>()

const availability = computed(() => {
  const now = Date.now()
  const beginAt = parseBackendDateTime(props.ticket.beginTime).getTime()
  const endAt = parseBackendDateTime(props.ticket.endTime).getTime()

  if (props.ticket.status !== 1) return { label: '已下架', available: false, isUpcoming: false }
  if (props.ticket.stock <= 0) return { label: '已售罄', available: false, isUpcoming: false }
  if (Number.isFinite(beginAt) && now < beginAt) return { label: '未开售', available: false, isUpcoming: true }
  if (Number.isFinite(endAt) && now > endAt) return { label: '已结束', available: false, isUpcoming: false }
  return { label: '可预约', available: true, isUpcoming: false }
})

const availabilityType = computed<'success' | 'warning' | 'info'>(() => {
  if (availability.value.available) return 'success'
  return availability.value.isUpcoming ? 'warning' : 'info'
})

const isLowStock = computed(() => props.ticket.stock > 0 && props.ticket.stock <= 15)

const actionLabel = computed(() => {
  if (props.loading) return '预约中'
  return availability.value.available ? '立即预约' : availability.value.label
})
</script>

<template>
  <el-card
    class="ticket-option"
    :class="{ 'ticket-option--unavailable': !availability.available }"
    shadow="never"
    :body-style="{ padding: '0' }"
    role="article"
  >
    <div class="ticket-option__main">
      <div class="ticket-option__heading">
        <TicketIcon class="ticket-option__icon" :size="20" aria-hidden="true" />
        <div class="ticket-option__title-wrap">
          <h3 class="ticket-option__title">{{ ticket.title }}</h3>
          <el-tag :type="availabilityType" effect="plain" size="small">
            {{ availability.label }}
          </el-tag>
        </div>
      </div>

      <div class="ticket-option__details">
        <p class="ticket-option__price">
          <strong>{{ formatPrice(ticket.price) }}</strong>
          <del v-if="ticket.originalPrice && ticket.originalPrice > ticket.price">
            {{ formatPrice(ticket.originalPrice) }}
          </del>
        </p>
        <p
          class="ticket-option__stock"
          :class="{ 'ticket-option__stock--urgent': isLowStock }"
        >
          <Flame v-if="isLowStock" :size="13" aria-hidden="true" />
          {{ isLowStock ? `仅剩 ${ticket.stock} 张` : `剩余 ${Math.max(0, ticket.stock)} 张` }}
        </p>
      </div>

      <p class="ticket-option__window">
        <Clock3 :size="14" aria-hidden="true" />
        <span>预约至 {{ formatDateTime(ticket.endTime) }}</span>
      </p>
    </div>

    <div class="ticket-option__action-wrap">
      <el-button
        class="ticket-option__action"
        type="primary"
        :loading="loading"
        :disabled="!availability.available"
        @click="emit('reserve', ticket)"
      >
        {{ actionLabel }}
      </el-button>
    </div>
  </el-card>
</template>

<style scoped>
.ticket-option {
  min-width: 0;
  overflow: hidden;
  border-radius: 8px;
}

.ticket-option :deep(.el-card__body) {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(7.5rem, auto);
}

.ticket-option__main {
  min-width: 0;
  display: grid;
  gap: var(--space-3);
  padding: var(--space-4);
}

.ticket-option__heading {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.ticket-option__icon {
  flex: 0 0 auto;
  color: var(--color-primary);
}

.ticket-option__title-wrap {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.ticket-option__title {
  min-width: 0;
  overflow: hidden;
  font-size: var(--text-body);
  font-weight: 700;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-option__details {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
}

.ticket-option__price {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-2);
  font-variant-numeric: tabular-nums;
  line-height: 1.25;
}

.ticket-option__price strong {
  color: var(--color-primary);
  font-size: var(--text-section);
  font-weight: 700;
}

.ticket-option__price del,
.ticket-option__stock,
.ticket-option__window {
  color: var(--color-ink-muted);
  font-size: var(--text-caption);
}

.ticket-option__stock {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.ticket-option__stock--urgent {
  color: var(--color-danger);
  font-weight: 700;
}

.ticket-option__window {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-variant-numeric: tabular-nums;
  line-height: 1.4;
}

.ticket-option__action-wrap {
  display: grid;
  place-items: center;
  padding: var(--space-4);
  border-left: 1px solid var(--color-border);
  background: var(--color-surface-hover);
}

.ticket-option__action {
  width: 100%;
  min-width: 6.5rem;
}

.ticket-option--unavailable .ticket-option__icon {
  color: var(--color-ink-muted);
}

@media (max-width: 32rem) {
  .ticket-option :deep(.el-card__body) {
    grid-template-columns: minmax(0, 1fr);
  }

  .ticket-option__title-wrap,
  .ticket-option__details {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-1);
  }

  .ticket-option__action-wrap {
    padding-top: 0;
    border-left: 0;
    background: var(--color-surface);
  }

  .ticket-option__action {
    min-width: 0;
  }
}
</style>
