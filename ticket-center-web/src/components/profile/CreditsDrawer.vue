<script setup lang="ts">
import { ref, watch } from 'vue'
import { Coins, HelpCircle } from 'lucide-vue-next'
import { userApi } from '../../services/api'
import type { CreditLog, PageResult } from '../../types/api'
import { getErrorMessage } from '../../utils/errors'
import { formatDateTime } from '../../utils/format'

const props = defineProps<{
  visible: boolean
  totalCredits: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const logs = ref<CreditLog[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const currentPage = ref(1)
const pageSize = 15

async function loadLogs(page = 1) {
  loading.value = true
  error.value = ''
  try {
    const res: PageResult<CreditLog> = await userApi.creditLogs({ current: page, size: pageSize })
    logs.value = res.records || []
    total.value = res.total || 0
    currentPage.value = res.current || 1
  } catch (requestError) {
    error.value = getErrorMessage(requestError, '积分明细加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.visible,
  (val) => {
    if (val) {
      void loadLogs(1)
    }
  },
)
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="积分账单明细"
    size="420px"
    class="credits-drawer"
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="credits-drawer__content">
      <!-- 积分总览卡片 -->
      <section class="credits-overview-card">
        <div class="overview-left">
          <span class="overview-label">当前可用积分</span>
          <strong class="overview-number">{{ totalCredits }}</strong>
        </div>
        <div class="overview-badge">
          <Coins :size="22" aria-hidden="true" />
        </div>
      </section>

      <!-- 规则说明说明栏 -->
      <section class="credits-rule-card">
        <div class="rule-title">
          <HelpCircle :size="14" aria-hidden="true" />
          <span>积分规则说明</span>
        </div>
        <ul class="rule-list">
          <li><strong>获取</strong>：每日打卡签到固定获得 10 积分。</li>
          <li><strong>抵扣</strong>：100 积分 = 1 元，单笔购票订单最高可立减 10 元。</li>
          <li><strong>退还</strong>：未支付订单取消或超时关单时，抵扣的积分将自动原路退还。</li>
        </ul>
      </section>

      <!-- 积分变动明细列表 -->
      <section class="credits-log-section">
        <div class="section-subheading">
          <h3>收支记录</h3>
          <span v-if="total > 0" class="log-count">共 {{ total }} 条</span>
        </div>

        <div v-if="loading" class="log-loading">
          <el-skeleton :rows="4" animated />
        </div>

        <!-- 必须排在空态之前：请求失败时 logs 恰好也是空的，两个分支会同时成立 -->
        <el-alert
          v-else-if="error"
          :title="error"
          type="error"
          show-icon
          :closable="false"
        />

        <div v-else-if="logs.length === 0" class="log-empty">
          <p>暂无积分收支变动记录</p>
        </div>

        <ul v-else class="log-list">
          <li v-for="item in logs" :key="String(item.id)" class="log-item">
            <div class="log-item__main">
              <span class="log-item__desc">{{ item.description }}</span>
              <time class="log-item__time">{{ formatDateTime(item.createTime) }}</time>
            </div>
            <div class="log-item__side">
              <strong
                class="log-item__amount"
                :class="{
                  'log-item__amount--positive': item.changeAmount > 0,
                  'log-item__amount--negative': item.changeAmount < 0,
                }"
              >
                {{ item.changeAmount > 0 ? `+${item.changeAmount}` : item.changeAmount }}
              </strong>
              <span class="log-item__balance">结余 {{ item.balance }}</span>
            </div>
          </li>
        </ul>

        <!-- 分页 -->
        <div v-if="total > pageSize" class="log-pagination">
          <el-pagination
            v-model:current-page="currentPage"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            small
            @current-change="loadLogs"
          />
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.credits-drawer__content {
  display: grid;
  gap: var(--space-4);
}

.credits-overview-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-5);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.overview-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.overview-label {
  font-size: var(--text-secondary);
  color: var(--color-ink-muted);
}

.overview-number {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.overview-badge {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--color-primary-soft, rgba(37, 99, 235, 0.08));
  color: var(--color-primary);
}

.credits-rule-card {
  padding: var(--space-3) var(--space-4);
  background: var(--color-bg);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  font-size: 0.8125rem;
}

.rule-title {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: var(--space-2);
}

.rule-list {
  display: grid;
  gap: 4px;
  padding-left: var(--space-4);
  color: var(--color-ink-soft);
  line-height: 1.5;
  margin: 0;
}

.credits-log-section {
  display: grid;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.section-subheading {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-subheading h3 {
  font-size: var(--text-body);
  font-weight: 600;
  color: var(--color-ink);
}

.log-count {
  font-size: var(--text-secondary);
  color: var(--color-ink-muted);
}

.log-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 1px;
  background: var(--color-border);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.log-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-3) var(--space-4);
  background: var(--color-surface);
}

.log-item__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.log-item__desc {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-ink);
}

.log-item__time {
  font-size: 0.75rem;
  color: var(--color-ink-muted);
}

.log-item__side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.log-item__amount {
  font-size: 0.9375rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.log-item__amount--positive {
  color: var(--color-success);
}

.log-item__amount--negative {
  color: var(--color-ink);
}

.log-item__balance {
  font-size: 0.75rem;
  color: var(--color-ink-muted);
  font-variant-numeric: tabular-nums;
}

.log-empty {
  padding: var(--space-8);
  text-align: center;
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
}

.log-pagination {
  display: flex;
  justify-content: center;
  margin-top: var(--space-2);
}
</style>
