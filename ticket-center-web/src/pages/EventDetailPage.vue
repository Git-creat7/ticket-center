<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ArrowLeft,
  CalendarDays,
  Clock3,
  Eye,
  MapPin,
  PenLine,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import EventImage from '../components/events/EventImage.vue'
import TicketOption from '../components/tickets/TicketOption.vue'
import CheckoutDialog from '../components/orders/CheckoutDialog.vue'
import { eventApi, orderApi, ticketApi, userApi } from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type { ApiId, EventDetail, Ticket } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { formatCount, formatDateTime, parseBackendDateTime } from '../utils/format'
import { splitImages } from '../utils/images'

// 路由与全局状态
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

// 演出详情与票档状态
const event = ref<EventDetail | null>(null)
const uv = ref(0)
const tickets = ref<Ticket[]>([])
const eventLoading = ref(true)
const ticketsLoading = ref(true)
const eventError = ref('')
const ticketsError = ref('')
const reservingTicketId = ref<ApiId | null>(null)
const reservation = ref<{ orderId: ApiId; ticketTitle: string } | null>(null)
const userCredits = ref(0)
const checkoutVisible = ref(false)
const selectedTicket = ref<Ticket | null>(null)

// 页面 UV 浏览去重记录
const viewedEventIds = new Set<string>()
let loadRequestId = 0
let ticketRequestId = 0

// 计算当前活动 ID
const eventId = computed(() => {
  const value = route.params.id
  return Array.isArray(value) ? value[0] || '' : String(value || '')
})

// 过滤非主图的图集照片
const galleryImages = computed(() => {
  const mainImage = event.value?.mainImage.trim()
  return splitImages(event.value?.images).filter((image) => image !== mainImage)
})

// 格式化演出时长展示
function formatDuration(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes <= 0) return '时长待定'
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} 分钟`
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分钟`
}

// 导航回退
function goBack() {
  if (window.history.length > 1) router.back()
  else void router.push({ name: 'discover' })
}

// 异步记录活动 UV 浏览量（容错保障，不阻断页面加载）
async function recordViewOnce(id: ApiId) {
  const key = String(id)
  if (viewedEventIds.has(key)) {
    try {
      const views = await eventApi.getViews(id)
      if (String(eventId.value) === key) uv.value = views
    } catch {
      // 静默降级
    }
    return
  }
  viewedEventIds.add(key)

  try {
    const views = await eventApi.addView(id)
    if (String(eventId.value) === key) uv.value = views
  } catch {
    // 静默降级
  }
}

// 加载活动详情与全部票档
async function loadDetail() {
  const id = eventId.value
  const requestId = ++loadRequestId
  ticketRequestId += 1

  event.value = null
  tickets.value = []
  reservation.value = null
  eventLoading.value = true
  ticketsLoading.value = true
  eventError.value = ''
  ticketsError.value = ''

  if (!id) {
    eventError.value = '活动地址无效，请返回发现页重新选择。'
    eventLoading.value = false
    ticketsLoading.value = false
    return
  }

  const [eventResult, ticketResult] = await Promise.allSettled([
    eventApi.getById(id),
    ticketApi.listByEvent(id),
  ])

  if (requestId !== loadRequestId) return

  if (eventResult.status === 'fulfilled') {
    event.value = eventResult.value
    void recordViewOnce(id)
  } else {
    eventError.value = getErrorMessage(eventResult.reason, '活动详情加载失败，请稍后重试')
  }

  if (ticketResult.status === 'fulfilled') {
    tickets.value = ticketResult.value
  } else {
    ticketsError.value = getErrorMessage(ticketResult.reason, '票档信息加载失败')
  }

  // 若已登录，拉取当前可用积分
  if (auth.isAuthenticated && auth.user) {
    try {
      const userInfo = await userApi.getInfo(auth.user.id)
      // 这次 await 之后必须重新校验：快速切演出时旧请求会在这里复活，
      // 把积分写成上一个演出的值，并把新请求的 loading 提前关掉
      if (requestId !== loadRequestId) return
      userCredits.value = userInfo?.credits ?? 0
    } catch {
      if (requestId !== loadRequestId) return
      // 静默降级
    }
  }

  eventLoading.value = false
  ticketsLoading.value = false
}

// 重新加载票档
async function loadTickets() {
  const id = eventId.value
  if (!id) return

  const requestId = ++ticketRequestId
  ticketsLoading.value = true
  ticketsError.value = ''

  try {
    const result = await ticketApi.listByEvent(id)
    if (requestId === ticketRequestId && id === eventId.value) tickets.value = result
  } catch (error) {
    if (requestId === ticketRequestId) {
      ticketsError.value = getErrorMessage(error, '票档加载失败')
    }
  } finally {
    if (requestId === ticketRequestId) ticketsLoading.value = false
  }
}

// 票档可预约性校验
function ticketCanBeReserved(ticket: Ticket): boolean {
  if (ticket.status !== 1 || ticket.stock <= 0) return false

  const now = Date.now()
  const begin = parseBackendDateTime(ticket.beginTime).getTime()
  const end = parseBackendDateTime(ticket.endTime).getTime()
  return Number.isFinite(begin) && Number.isFinite(end) && now >= begin && now <= end
}

// 打开收银台结算单
function openCheckout(ticket: Ticket) {
  if (reservingTicketId.value != null) return

  if (!ticketCanBeReserved(ticket)) {
    notifications.notify('这个票档当前不可预约，请选择其他票档。', 'error')
    return
  }

  if (!auth.isAuthenticated) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }

  selectedTicket.value = ticket
  checkoutVisible.value = true
}

// 确认收银台结算
async function handleCheckoutConfirm(useCreditsChosen: boolean) {
  if (!selectedTicket.value) return
  const ticket = selectedTicket.value
  reservingTicketId.value = ticket.id
  reservation.value = null

  try {
    const orderId = await orderApi.reserve(ticket.id, useCreditsChosen)
    reservation.value = { orderId, ticketTitle: ticket.title }
    checkoutVisible.value = false
    notifications.notify('预约请求已成功受理，请在 15 分钟内前往票夹完成支付。', 'success')
    // 扣减后刷新本地积分展示
    if (auth.user && useCreditsChosen) {
      const userInfo = await userApi.getInfo(auth.user.id).catch(() => null)
      if (userInfo) userCredits.value = userInfo.credits ?? 0
    }
  } catch (error) {
    notifications.notify(getErrorMessage(error, '预约失败，请稍后重试'), 'error')
  } finally {
    reservingTicketId.value = null
  }
}

watch(eventId, loadDetail, { immediate: true })
</script>

<template>
  <div class="page-container event-detail-page">
    <!-- 返回按钮 -->
    <el-button class="detail-back" text aria-label="返回上一页" @click="goBack">
      <ArrowLeft :size="22" aria-hidden="true" />
    </el-button>

    <!-- 详情加载骨架屏 -->
    <el-card
      v-if="eventLoading"
      class="detail-loading"
      shadow="never"
      :body-style="{ padding: '0' }"
      aria-label="正在加载活动详情"
    >
      <el-skeleton animated>
        <template #template>
          <div class="detail-loading__layout">
            <el-skeleton-item variant="image" class="detail-loading__media" />
            <div class="detail-loading__summary">
              <el-skeleton-item variant="text" class="detail-loading__tag" />
              <el-skeleton-item variant="h1" class="detail-loading__title" />
              <el-skeleton-item variant="text" class="detail-loading__line" />
              <el-skeleton-item variant="text" class="detail-loading__line detail-loading__line--short" />
            </div>
          </div>
        </template>
      </el-skeleton>
    </el-card>

    <!-- 详情异常状态 -->
    <el-empty v-else-if="eventError || !event" :description="eventError || '活动未找到'">
      <el-button type="primary" @click="loadDetail">重新加载</el-button>
    </el-empty>

    <!-- 演出详情主要内容 -->
    <template v-else>
      <article class="event-overview">
        <div class="event-overview__media">
          <EventImage :src="event.mainImage" :alt="`${event.name}活动海报`" eager />
        </div>

        <div class="event-overview__summary">
          <el-tag class="event-category" effect="plain">{{ event.categoryName }}</el-tag>
          <h1 class="event-title">{{ event.name }}</h1>

          <dl class="event-facts">
            <div>
              <dt><CalendarDays :size="20" aria-hidden="true" /><span class="visually-hidden">开始时间</span></dt>
              <dd>
                <time :datetime="event.startTime.replace(' ', 'T')">{{ formatDateTime(event.startTime) }}</time>
              </dd>
            </div>
            <div>
              <dt><Clock3 :size="20" aria-hidden="true" /><span class="visually-hidden">活动时长</span></dt>
              <dd>{{ formatDuration(event.durationMin) }}</dd>
            </div>
            <div>
              <dt><MapPin :size="20" aria-hidden="true" /><span class="visually-hidden">活动地点</span></dt>
              <dd>
                <strong>{{ event.venue }}</strong>
                <span v-if="event.address">{{ event.address }}</span>
              </dd>
            </div>
          </dl>

          <p class="event-interest">
            <Eye :size="18" aria-hidden="true" />
            <span>{{ formatCount(uv) }} 人关注浏览</span>
          </p>
        </div>
      </article>

      <!-- 票档预订区域 -->
      <section id="tickets" class="detail-section ticket-section" aria-labelledby="tickets-heading">
        <div class="section-header">
          <div>
            <h2 id="tickets-heading" class="section-heading">选择票档</h2>
            <p class="section-copy">库存和售卖时间以各票档当前状态为准。</p>
          </div>
        </div>

        <!-- 预约成功浮层提示 -->
        <el-alert
          v-if="reservation"
          class="reservation-result"
          type="success"
          show-icon
          :closable="false"
          role="status"
        >
          <template #title>
            <strong>预约请求已成功受理</strong>
          </template>
          <p>
            已锁定 <strong>{{ reservation.ticketTitle }}</strong> 门票，请在 15 分钟内前往票夹完成支付。
          </p>
          <RouterLink v-slot="{ navigate }" custom to="/orders">
            <el-button type="success" @click="navigate">前往票夹查看</el-button>
          </RouterLink>
        </el-alert>

        <!-- 票档加载态 -->
        <div v-if="ticketsLoading" class="ticket-list" aria-label="正在加载票档">
          <el-card v-for="index in 2" :key="index" class="ticket-skeleton" shadow="never">
            <el-skeleton :rows="3" animated />
          </el-card>
        </div>

        <!-- 票档异常 -->
        <el-alert
          v-else-if="ticketsError"
          :title="ticketsError"
          type="error"
          show-icon
          :closable="false"
        >
          <template #default>
            <el-button type="danger" link @click="loadTickets">重试</el-button>
          </template>
        </el-alert>

        <!-- 票档为空 -->
        <el-empty
          v-else-if="tickets.length === 0"
          description="该演出暂未开售或票档尚未公布，请稍后关注。"
        />

        <!-- 票档列表 -->
        <div v-else class="ticket-list">
          <TicketOption
            v-for="ticket in tickets"
            :key="String(ticket.id)"
            :ticket="ticket"
            :loading="String(reservingTicketId) === String(ticket.id)"
            @reserve="openCheckout"
          />
        </div>

        <!-- 弹窗收银台结算单 -->
        <CheckoutDialog
          v-model:visible="checkoutVisible"
          :event="event"
          :ticket="selectedTicket"
          :user-credits="userCredits"
          :loading="reservingTicketId !== null"
          @confirm="handleCheckoutConfirm"
        />
      </section>

      <!-- 演出介绍说明 -->
      <section class="detail-section" aria-labelledby="intro-heading">
        <h2 id="intro-heading" class="section-heading">演出简介</h2>
        <p v-if="event.intro" class="event-intro">{{ event.intro }}</p>
        <p v-else class="missing-copy">主办方暂未上传详细介绍。</p>
      </section>

      <!-- 现场图集画廊 -->
      <section v-if="galleryImages.length" class="detail-section" aria-labelledby="gallery-heading">
        <h2 id="gallery-heading" class="section-heading">演出图集</h2>
        <div class="event-gallery">
          <div v-for="(image, index) in galleryImages" :key="`${image}-${index}`" class="event-gallery__item">
            <EventImage :src="image" :alt="`${event.name}现场图片 ${index + 1}`" />
          </div>
        </div>
      </section>

      <!-- 评价入口引导 -->
      <section class="detail-section review-prompt" aria-labelledby="review-heading">
        <div class="section-header">
          <div>
            <h2 id="review-heading" class="section-heading">现场评价</h2>
            <p class="section-copy">观演归来？欢迎分享你的现场体验与真实见解。</p>
          </div>
          <RouterLink
            v-slot="{ navigate }"
            custom
            :to="{ name: 'review-create', query: { eventId: event.id } }"
          >
            <el-button class="review-link" @click="navigate">
              <PenLine :size="18" aria-hidden="true" />
              <span>写现场评价</span>
            </el-button>
          </RouterLink>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped lang="scss">
.event-detail-page {
  padding-block: var(--space-6) var(--space-16);
}

.detail-back {
  margin-bottom: var(--space-4);
}

.detail-loading {
  overflow: hidden;
  border-radius: 8px;

  &__layout {
    display: grid;
    gap: var(--space-6);
  }

  &__media {
    width: 100%;
    height: auto;
    aspect-ratio: 4 / 3;
    border-radius: 8px;
  }

  &__summary {
    align-content: center;
    display: grid;
    gap: var(--space-4);
    padding: var(--space-4);
  }

  &__tag {
    width: 5rem;
  }

  &__title {
    width: min(100%, 30rem);
  }

  &__line {
    width: min(100%, 24rem);

    &--short {
      width: min(65%, 16rem);
    }
  }
}

.event-overview {
  display: grid;
  gap: var(--space-6);

  &__media {
    width: 100%;
    aspect-ratio: 4 / 3;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid var(--color-border);
    background: var(--color-surface-muted);
  }

  &__summary {
    align-content: center;
    display: grid;
    gap: var(--space-4);
  }
}

.event-category {
  justify-self: start;
  font-weight: 700;
}

.event-title {
  font-size: var(--text-title);
  font-weight: 800;
  letter-spacing: 0;
  color: var(--color-ink);
  line-height: 1.25;
}

.event-facts {
  display: grid;
  gap: var(--space-3);
  margin: 0;

  > div {
    display: grid;
    grid-template-columns: 24px minmax(0, 1fr);
    align-items: start;
    gap: var(--space-3);
  }

  dt {
    display: grid;
    place-items: center;
    padding-top: 2px;
    color: var(--color-primary);
  }

  dd {
    min-width: 0;
    display: grid;
    gap: 2px;
    margin: 0;
    line-height: 1.5;

    strong {
      color: var(--color-ink);
    }

    span {
      color: var(--color-ink-soft);
      font-size: var(--text-secondary);
    }
  }
}

.event-interest {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.detail-section {
  display: grid;
  gap: var(--space-4);
  padding-top: var(--space-8);
  margin-top: var(--space-8);
  border-top: 1px solid var(--color-border);
}

.ticket-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 20rem), 1fr));
  gap: var(--space-4);
}

.ticket-skeleton {
  min-height: 12rem;
  border-radius: 8px;
}

.reservation-result {
  align-items: flex-start;
  border-radius: 8px;

  p {
    color: var(--color-ink-soft);
    font-size: var(--text-secondary);
  }

  .el-button {
    margin-top: var(--space-3);
  }
}

.event-intro {
  max-width: 75ch;
  color: var(--color-ink-soft);
  white-space: pre-wrap;
  line-height: 1.7;
}

.missing-copy {
  color: var(--color-ink-muted);
}

.review-link {
  flex: 0 0 auto;
}

.event-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 17rem), 1fr));
  gap: var(--space-3);

  &__item {
    aspect-ratio: 4 / 3;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid var(--color-border);
    background: var(--color-surface-muted);
  }
}

.credits-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-4);
}

.credits-bar__copy {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.credits-bar__icon {
  color: var(--color-primary);
  flex-shrink: 0;
}

.credits-bar__text {
  font-size: var(--text-secondary);
  color: var(--color-ink-soft);
}

.credits-bar__text strong {
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}

@media (min-width: 48rem) {
  .event-detail-page {
    padding-block: var(--space-8) var(--space-16);
  }

  .detail-loading__layout,
  .event-overview {
    grid-template-columns: minmax(0, 7fr) minmax(18rem, 5fr);
    align-items: center;
    gap: var(--space-8);
  }

  .event-title {
    font-size: var(--text-page);
  }
}
</style>
