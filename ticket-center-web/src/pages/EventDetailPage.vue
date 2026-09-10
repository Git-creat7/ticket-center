<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  ArrowLeft,
  CalendarDays,
  Clock3,
  Eye,
  MapPin,
  PenLine,
  RefreshCw,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import EventImage from '../components/events/EventImage.vue'
import TicketOption from '../components/tickets/TicketOption.vue'
import CheckoutDialog from '../components/orders/CheckoutDialog.vue'
import ReservationRecord from '../components/orders/ReservationRecord.vue'
import { usePolling } from '../composables/usePolling'
import { eventApi, orderApi, reservationApi, ticketApi, userApi, waitlistApi } from '../services/api'
import { ApiError } from '../services/http'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type { ApiId, EventDetail, Ticket } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { formatCount, formatDateTime } from '../utils/format'
import { ticketAvailability } from '../utils/tickets'
import { splitImages } from '../utils/images'
import {
  clearReservationRequest,
  readReservationRequest,
  saveReservationRequest,
  type ReservationRequest,
} from '../utils/reservations'

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
const userCredits = ref(0)
const checkoutVisible = ref(false)
const selectedTicket = ref<Ticket | null>(null)
const checkoutRequest = ref<ReservationRequest | null>(null)
const checkoutError = ref('')
const checkoutKind = ref<'reservation' | 'waitlist'>('reservation')

// 页面 UV 浏览去重记录
const viewedEventIds = new Set<string>()
let loadRequestId = 0
let ticketRequestId = 0

// 计算当前活动 ID
const eventId = computed(() => {
  const value = route.params.id
  return Array.isArray(value) ? value[0] || '' : String(value || '')
})

const reservationId = computed(() => {
  const value = route.query.reservation
  return typeof value === 'string' && /^\d+$/.test(value) ? value : null
})
const { data: reservation, error: reservationError, refreshing, refresh: refreshReservation } = usePolling(
  () => auth.token && reservationId.value ? `${auth.token}:${eventId.value}:${reservationId.value}` : null,
  (signal) => reservationApi.get(reservationId.value!, signal),
  (result) => result.status === 0 || result.releasePending || result.orderStatus === 0,
)
const reservationErrorMessage = computed(() => (
  reservationError.value ? getErrorMessage(reservationError.value, '预约结果查询失败') : ''
))

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
  checkoutVisible.value = false
  selectedTicket.value = null
  checkoutRequest.value = null
  checkoutError.value = ''
  reservingTicketId.value = null
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
    restoreCheckoutRequest()
  } else {
    ticketsError.value = getErrorMessage(ticketResult.reason, '票档信息加载失败')
  }

  // 若已登录，拉取当前可用积分
  if (auth.isAuthenticated && auth.user) {
    try {
      const userInfo = await userApi.getInfo(auth.user.id)
      // 防止快速切换演出时旧请求覆盖新状态。
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

function restoreCheckoutRequest() {
  if (!auth.user || reservingTicketId.value !== null) return
  for (const ticket of tickets.value) {
    const saved = readReservationRequest(auth.user.id, ticket.id)
    if (!saved) continue
    selectedTicket.value = ticket
    checkoutRequest.value = saved
    checkoutKind.value = saved.kind ?? 'reservation'
    checkoutError.value = '上次提交结果尚未确认'
    break
  }
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

// 打开收银台结算单
function openCheckout(ticket: Ticket, kind: 'reservation' | 'waitlist' = 'reservation') {
  if (reservingTicketId.value != null) return

  if (!auth.isAuthenticated) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }

  if (!auth.user) {
    notifications.notify('登录信息尚未加载，请稍后重试', 'error')
    return
  }

  const saved = readReservationRequest(auth.user.id, ticket.id)
  if (!saved && ticketAvailability(ticket).kind !== kind) {
    notifications.notify('票档状态已变化，请刷新票档后重试。', 'error')
    void loadTickets()
    return
  }

  selectedTicket.value = ticket
  checkoutRequest.value = saved
  checkoutKind.value = saved?.kind ?? (saved ? 'reservation' : kind)
  checkoutError.value = saved ? '上次提交结果尚未确认' : ''
  checkoutVisible.value = true
}

// 确认收银台结算
async function handleCheckoutConfirm(useCreditsChosen: boolean) {
  if (!selectedTicket.value || !auth.user || reservingTicketId.value !== null) return
  const ticket = selectedTicket.value
  const userId = auth.user.id
  const token = auth.token
  const context = loadRequestId
  reservingTicketId.value = ticket.id
  checkoutError.value = ''

  try {
    // 网络异常后继续使用原请求号和积分选项。
    const request = readReservationRequest(userId, ticket.id) ?? {
      requestId: crypto.randomUUID(),
      useCredits: useCreditsChosen,
      kind: checkoutKind.value,
    }
    saveReservationRequest(userId, ticket.id, request)
    checkoutRequest.value = request
    const id = request.kind === 'waitlist'
      ? await waitlistApi.join(ticket.id, request.useCredits, request.requestId)
      : await orderApi.reserve(ticket.id, request.useCredits, request.requestId)
    if (readReservationRequest(userId, ticket.id)?.requestId === request.requestId) {
      clearReservationRequest(userId, ticket.id)
    }
    if (context !== loadRequestId || token !== auth.token) return
    checkoutRequest.value = null
    checkoutVisible.value = false
    if (request.kind === 'waitlist') {
      notifications.notify('已加入候补', 'success')
      await router.push({ path: '/orders', query: { view: 'waitlists' } })
      return
    }
    await router.replace({ query: { ...route.query, reservation: String(id) } })
    refreshReservation()
    notifications.notify('预约已受理', 'success')
  } catch (error) {
    if (context !== loadRequestId || token !== auth.token) return
    if (error instanceof ApiError && error.code >= 400 && error.code < 500) {
      clearReservationRequest(userId, ticket.id)
      checkoutRequest.value = null
      void loadTickets()
    }
    checkoutError.value = getErrorMessage(error, '提交结果尚未确认，请重试或查看记录')
  } finally {
    if (context === loadRequestId && token === auth.token) reservingTicketId.value = null
  }
}

watch(eventId, loadDetail, { immediate: true })
watch(() => auth.user?.id, restoreCheckoutRequest)
watch(() => auth.token, () => {
  checkoutVisible.value = false
  checkoutRequest.value = null
  checkoutError.value = ''
  reservingTicketId.value = null
})
watch(() => [reservation.value?.status, reservation.value?.orderStatus, reservation.value?.releasePending], async (state, previousState) => {
  if (state[0] === undefined || state[0] === 0
    || state.every((value, index) => value === previousState[index])) return
  void loadTickets()
  const userId = auth.user?.id
  if (!userId) return
  const userInfo = await userApi.getInfo(userId).catch(() => null)
  if (auth.user?.id === userId && userInfo) userCredits.value = userInfo.credits ?? 0
})
onBeforeUnmount(() => {
  loadRequestId += 1
  ticketRequestId += 1
})
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
            <span>{{ formatCount(uv) }} 人浏览过</span>
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

        <!-- 本次预约结果 -->
        <div v-if="reservationId && auth.isAuthenticated" class="reservation-result">
          <div class="reservation-result__heading">
            <h3 class="section-heading">本次预约</h3>
            <el-tooltip content="刷新预约结果">
              <el-button :loading="refreshing" :disabled="refreshing" aria-label="刷新预约结果" @click="refreshReservation">
                <RefreshCw v-if="!refreshing" :size="18" aria-hidden="true" />
              </el-button>
            </el-tooltip>
          </div>
          <el-alert v-if="reservationErrorMessage" :title="reservationErrorMessage" type="warning" show-icon :closable="false" />
          <ReservationRecord v-if="reservation" :reservation="reservation" />
          <p v-else-if="!reservationErrorMessage" role="status">正在查询预约 {{ reservationId }}</p>
          <RouterLink :to="{ path: '/orders', query: { view: 'reservations' } }">全部预约记录</RouterLink>
        </div>

        <el-alert
          v-if="checkoutRequest && checkoutError && !checkoutVisible"
          :title="checkoutError"
          type="warning"
          show-icon
          :closable="false"
        >
          <el-button :loading="reservingTicketId !== null" @click="handleCheckoutConfirm(checkoutRequest.useCredits)">重试提交</el-button>
          <RouterLink v-slot="{ navigate }" custom :to="{ path: '/orders', query: { view: checkoutKind === 'waitlist' ? 'waitlists' : 'reservations' } }">
            <el-button @click="navigate">查看记录</el-button>
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
            @waitlist="openCheckout($event, 'waitlist')"
          />
        </div>

        <!-- 弹窗收银台结算单 -->
        <CheckoutDialog
          v-model:visible="checkoutVisible"
          :event="event"
          :ticket="selectedTicket"
          :user-credits="userCredits"
          :loading="reservingTicketId !== null"
          :locked-use-credits="checkoutRequest?.useCredits"
          :error="checkoutError"
          :kind="checkoutKind"
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
  display: grid;
  gap: var(--space-3);
}

.reservation-result__heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
}

.reservation-result__heading :deep(.el-button) {
  width: 44px;
  height: 44px;
  padding: 0;
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
