<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Coins, ShieldCheck, TicketCheck } from 'lucide-vue-next'
import type { EventDetail, Ticket } from '../../types/api'
import { formatPrice } from '../../utils/format'

const props = defineProps<{
  visible: boolean
  event: EventDetail | null
  ticket: Ticket | null
  userCredits: number
  loading?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: [useCredits: boolean]
}>()

const useCredits = ref(true)

// 当弹窗打开时，默认开启抵扣（如果用户有积分）
watch(
  () => props.visible,
  (val) => {
    if (val) {
      useCredits.value = props.userCredits > 0
    }
  },
)

// 计算最高可抵扣分值 (100积分 = 1元 = 100分，最多抵扣 1000 分，且不超过票价)
const maxDeductFen = computed(() => {
  if (!props.ticket) return 0
  return Math.min(1000, props.ticket.price)
})

// 实际抵扣的积分数 (也是抵扣分值)
const actualDeductFen = computed(() => {
  if (!useCredits.value || props.userCredits <= 0) return 0
  return Math.min(props.userCredits, maxDeductFen.value)
})

// 最终应付金额（分）
const finalPriceFen = computed(() => {
  if (!props.ticket) return 0
  return Math.max(0, props.ticket.price - actualDeductFen.value)
})

function close() {
  emit('update:visible', false)
}

function handleConfirm() {
  emit('confirm', useCredits.value && actualDeductFen.value > 0)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="确认订单与结算"
    width="480px"
    class="checkout-dialog"
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-if="ticket && event" class="checkout-body">
      <!-- 演出与票档概要 -->
      <section class="checkout-ticket-summary">
        <div class="ticket-badge">
          <TicketCheck :size="18" aria-hidden="true" />
        </div>
        <div class="ticket-info">
          <h3 class="ticket-event-name">{{ event.name }}</h3>
          <p class="ticket-title-row">
            <span>{{ ticket.title }}</span>
            <span class="ticket-original-price">{{ formatPrice(ticket.price) }}</span>
          </p>
        </div>
      </section>

      <!-- 积分抵扣控制区域 -->
      <section class="checkout-credits-section">
        <div class="credits-control">
          <div class="credits-info">
            <div class="credits-title-row">
              <Coins :size="16" class="credits-icon" aria-hidden="true" />
              <strong>积分抵扣</strong>
              <span class="credits-balance">(当前可用 {{ userCredits }} 积分)</span>
            </div>
            <p v-if="userCredits > 0" class="credits-hint">
              100 积分抵 1 元，本单最多可立减 {{ formatPrice(maxDeductFen) }}
            </p>
            <p v-else class="credits-hint credits-hint--empty">
              当前暂无可抵扣积分，每日打卡可获取积分
            </p>
          </div>

          <el-switch
            v-if="userCredits > 0"
            v-model="useCredits"
            :disabled="loading"
          />
        </div>

        <div v-if="useCredits && actualDeductFen > 0" class="deduct-detail">
          <span>已立减 {{ formatPrice(actualDeductFen) }}</span>
          <span class="deduct-coins">消耗 {{ actualDeductFen }} 积分</span>
        </div>
      </section>

      <!-- 费用明细清单 -->
      <section class="checkout-breakdown">
        <div class="breakdown-row">
          <span>门票原价</span>
          <span class="price-val">{{ formatPrice(ticket.price) }}</span>
        </div>
        <div v-if="useCredits && actualDeductFen > 0" class="breakdown-row breakdown-row--discount">
          <span>积分立减</span>
          <span class="price-val">-{{ formatPrice(actualDeductFen) }}</span>
        </div>
        <div class="breakdown-divider" />
        <div class="breakdown-row breakdown-row--total">
          <span>应付总额</span>
          <strong class="total-price">{{ formatPrice(finalPriceFen) }}</strong>
        </div>
      </section>

      <!-- 安全与锁定提示 -->
      <div class="checkout-security-note">
        <ShieldCheck :size="14" aria-hidden="true" />
        <span>预约成功后将锁定座位 15 分钟，请及时前往票夹支付</span>
      </div>
    </div>

    <template #footer>
      <div class="checkout-footer">
        <el-button :disabled="loading" @click="close">取消</el-button>
        <el-button
          type="primary"
          :loading="loading"
          class="confirm-btn"
          @click="handleConfirm"
        >
          <span>{{ loading ? '正在锁定名额...' : '立即确认预约' }}</span>
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.checkout-body {
  display: grid;
  gap: var(--space-4);
  padding-top: var(--space-2);
}

.checkout-ticket-summary {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--color-surface-muted);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.ticket-badge {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  background: var(--color-primary-soft, rgba(37, 99, 235, 0.1));
  color: var(--color-primary);
}

.ticket-info {
  min-width: 0;
  flex: 1;
}

.ticket-event-name {
  font-size: var(--text-body);
  font-weight: 600;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
  font-size: var(--text-secondary);
  color: var(--color-ink-soft);
}

.ticket-original-price {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--color-ink);
}

/* 积分配置 */
.checkout-credits-section {
  padding: var(--space-3) var(--space-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.credits-control {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
}

.credits-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-body);
  color: var(--color-ink);
}

.credits-icon {
  color: var(--color-primary);
}

.credits-balance {
  font-size: var(--text-secondary);
  color: var(--color-ink-muted);
  font-weight: normal;
}

.credits-hint {
  margin-top: 4px;
  font-size: 0.75rem;
  color: var(--color-ink-soft);
}

.credits-hint--empty {
  color: var(--color-ink-muted);
}

.deduct-detail {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px dashed var(--color-border);
  font-size: 0.8125rem;
  color: var(--color-success);
}

.deduct-coins {
  color: var(--color-ink-muted);
  font-size: 0.75rem;
}

/* 明细清单 */
.checkout-breakdown {
  padding: var(--space-3) var(--space-4);
  background: var(--color-bg);
  border-radius: var(--radius-sm);
  display: grid;
  gap: var(--space-2);
}

.breakdown-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--text-secondary);
  color: var(--color-ink-soft);
}

.price-val {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
  color: var(--color-ink);
}

.breakdown-row--discount {
  color: var(--color-success);
}

.breakdown-row--discount .price-val {
  color: var(--color-success);
}

.breakdown-divider {
  height: 1px;
  background: var(--color-border);
  margin-block: 2px;
}

.breakdown-row--total {
  font-size: var(--text-body);
  color: var(--color-ink);
}

.total-price {
  font-size: var(--text-subheading);
  font-weight: 700;
  color: var(--color-danger);
  font-variant-numeric: tabular-nums;
}

.checkout-security-note {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 0.75rem;
  color: var(--color-ink-muted);
}

.checkout-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
}

.confirm-btn {
  min-width: 130px;
}
</style>
