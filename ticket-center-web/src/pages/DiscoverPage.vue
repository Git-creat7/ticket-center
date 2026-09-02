<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertCircle,
  CalendarDays,
  Flame,
  MapPin,
  RefreshCw,
  Ticket,
} from 'lucide-vue-next'
import { RouterLink, useRouter } from 'vue-router'
import EventCard from '../components/events/EventCard.vue'
import EventCardSkeleton from '../components/events/EventCardSkeleton.vue'
import EventImage from '../components/events/EventImage.vue'
import ReviewCard from '../components/reviews/ReviewCard.vue'
import { categoryApi, eventApi, reviewApi } from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type { ApiId, EventCategory, EventListItem, EventReview } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { formatCount, formatDateTime } from '../utils/format'
import { updateReviewLikeState } from '../utils/reviews'

const PAGE_SIZE = 8
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

// 状态定义
const categories = ref<EventCategory[]>([])
const selectedCategoryId = ref<ApiId | null>(null)
const events = ref<EventListItem[]>([])
const nextPage = ref(1)
const hasMore = ref(true)

const categoriesLoading = ref(true)
const categoriesError = ref('')
const loading = ref(true)
const loadingMore = ref(false)
const listError = ref('')

// 距离筛选：all 表示不限，选择具体距离后才启用定位和 GEO 查询
const locating = ref(false)
const longitude = ref<number | null>(null)
const latitude = ref<number | null>(null)
const locationMessage = ref('')
const radiusKm = ref<'all' | number>('all')
const radiusOptions: Array<{ label: string; value: 'all' | number }> = [
  { label: '不限', value: 'all' },
  { label: '1 km', value: 1 },
  { label: '3 km', value: 3 },
  { label: '5 km', value: 5 },
  { label: '10 km', value: 10 },
  { label: '20 km', value: 20 },
]

// 热门评价流状态
const hotReviews = ref<EventReview[]>([])
const reviewsLoading = ref(true)
const reviewsError = ref('')
const likingReviewId = ref<ApiId | null>(null)

let listRequestId = 0
let locationRequestId = 0

// 当前选中的分类对象
const selectedCategory = computed(() =>
  categories.value.find((category) => sameId(category.id, selectedCategoryId.value)),
)
const nearby = computed(() => typeof radiusKm.value === 'number')

// 列表标题与副标题描述
const listTitle = computed(() => {
  if (nearby.value && selectedCategory.value) return `${selectedCategory.value.name}附近活动`
  if (nearby.value) return '附近全部活动'
  return selectedCategory.value?.name || '探索全部活动'
})
const listDescription = computed(() => {
  if (nearby.value && selectedCategory.value) return `按距离浏览你附近 ${radiusKm.value} 公里内的${selectedCategory.value.name}。`
  if (nearby.value) return `按距离浏览你附近 ${radiusKm.value} 公里内的全部现场演出与活动。`
  if (selectedCategory.value) return `按热度浏览${selectedCategory.value.name}分类下的精彩活动。`
  return '发现最近全城最具人气的音乐节、演唱会与艺术展览。'
})

// 顶部今日焦点演出推荐
const featuredHeroEvent = computed(() => {
  if (!nearby.value && selectedCategoryId.value == null && events.value.length > 0) {
    return events.value[0]
  }
  return null
})
const visibleEvents = computed(() => (featuredHeroEvent.value ? events.value.slice(1) : events.value))

// ID 宽松相等判断工具函数
function sameId(left: ApiId | null, right: ApiId | null): boolean {
  return left == null || right == null ? left === right : String(left) === String(right)
}

// 加载分类列表
async function loadCategories() {
  categoriesLoading.value = true
  categoriesError.value = ''
  try {
    categories.value = await categoryApi.list()
  } catch (error) {
    categoriesError.value = getErrorMessage(error, '活动分类加载失败')
  } finally {
    categoriesLoading.value = false
  }
}

// 加载热门评价数据
async function loadHotReviews() {
  reviewsLoading.value = true
  reviewsError.value = ''
  try {
    hotReviews.value = await reviewApi.hot({ current: 1, size: 3 })
  } catch (error) {
    reviewsError.value = getErrorMessage(error, '热门评价加载失败')
  } finally {
    reviewsLoading.value = false
  }
}

// 评价点赞/取消点赞
async function toggleReviewLike(review: EventReview) {
  if (likingReviewId.value != null) return
  if (!auth.isAuthenticated) {
    void router.push({ name: 'login', query: { redirect: '/discover' } })
    return
  }

  likingReviewId.value = review.id
  try {
    await reviewApi.toggleLike(review.id)
    updateReviewLikeState(review)
  } catch (error) {
    notifications.notify(getErrorMessage(error, '点赞失败，请稍后重试'), 'error')
  } finally {
    likingReviewId.value = null
  }
}

// 加载活动列表
async function loadEvents(reset = false) {
  const requestId = ++listRequestId

  if (reset) {
    events.value = []
    nextPage.value = 1
    hasMore.value = true
    loading.value = true
  } else {
    loadingMore.value = true
  }
  listError.value = ''

  const page = nextPage.value

  try {
    let result: EventListItem[]
    if (nearby.value && typeof radiusKm.value === 'number' && longitude.value != null && latitude.value != null) {
      const x = longitude.value
      const y = latitude.value
      const radius = radiusKm.value
      result = selectedCategoryId.value == null
        ? await eventApi.nearby({ current: page, size: PAGE_SIZE, x, y, radius })
        : await eventApi.byCategory({
            categoryId: selectedCategoryId.value,
            current: page,
            size: PAGE_SIZE,
            x,
            y,
            radius,
          })
    } else {
      result = selectedCategoryId.value == null
        ? await eventApi.hot({ current: page, size: PAGE_SIZE })
        : await eventApi.byCategory({
            categoryId: selectedCategoryId.value,
            current: page,
            size: PAGE_SIZE,
          })
    }

    if (requestId !== listRequestId) return

    events.value = reset ? result : [...events.value, ...result]
    hasMore.value = result.length === PAGE_SIZE
    nextPage.value = page + 1
  } catch (error) {
    if (requestId === listRequestId) {
      listError.value = getErrorMessage(error, '活动加载失败，请稍后重试')
    }
  } finally {
    if (requestId === listRequestId) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

// 切换选中的活动分类
function selectCategory(categoryId: ApiId | null) {
  if (sameId(categoryId, selectedCategoryId.value)) return

  locationRequestId += 1
  selectedCategoryId.value = categoryId
  locating.value = false
  locationMessage.value = ''
  if (typeof radiusKm.value === 'number' && (longitude.value == null || latitude.value == null)) {
    requestLocation()
    return
  }
  void loadEvents(true)
}

function changeRadius(value: 'all' | number) {
  locationRequestId += 1
  locating.value = false
  locationMessage.value = ''

  if (value === 'all') {
    radiusKm.value = 'all'
    longitude.value = null
    latitude.value = null
    void loadEvents(true)
    return
  }

  radiusKm.value = value
  if (longitude.value == null || latitude.value == null) {
    requestLocation()
    return
  }

  locationMessage.value = `已按距离展示附近 ${value} 公里内的活动。`
  void loadEvents(true)
}

// 定位失败文案转换
function locationErrorMessage(error: GeolocationPositionError): string {
  if (error.code === error.PERMISSION_DENIED) {
    return '未获得位置权限，可在浏览器设置中允许定位后重试。'
  }
  if (error.code === error.TIMEOUT) {
    return '定位超时，请稍后重试。'
  }
  return '暂时无法获取你的位置。'
}

// 获取当前位置，成功后按当前距离范围加载活动
function requestLocation() {
  if (typeof radiusKm.value !== 'number' || locating.value) return

  if (!navigator.geolocation) {
    radiusKm.value = 'all'
    locationMessage.value = '当前浏览器不支持定位，已恢复不限距离的活动列表。'
    void loadEvents(true)
    return
  }

  const requestId = ++locationRequestId
  const categoryId = selectedCategoryId.value
  const radius = radiusKm.value
  locating.value = true
  locationMessage.value = ''

  navigator.geolocation.getCurrentPosition(
    (position) => {
      if (requestId !== locationRequestId || !sameId(categoryId, selectedCategoryId.value)) return

      longitude.value = position.coords.longitude
      latitude.value = position.coords.latitude
      locating.value = false
      locationMessage.value = `已按距离展示附近 ${radius} 公里内的活动。`
      void loadEvents(true)
    },
    (error) => {
      if (requestId !== locationRequestId || !sameId(categoryId, selectedCategoryId.value)) return

      locating.value = false
      radiusKm.value = 'all'
      longitude.value = null
      latitude.value = null
      locationMessage.value = `${locationErrorMessage(error)}已恢复不限距离的活动列表。`
      void loadEvents(true)
    },
    { enableHighAccuracy: false, timeout: 8_000, maximumAge: 300_000 },
  )
}

onMounted(() => {
  void loadCategories()
  void loadEvents(true)
  void loadHotReviews()
})
</script>

<template>
  <div class="page-container page-stack discover-page">
    <!-- 今日焦点演出推荐大图 -->
    <section v-if="featuredHeroEvent && !selectedCategoryId" aria-label="推荐活动">
      <el-card class="featured-event" shadow="never" :body-style="{ padding: '0' }">
        <div class="featured-event__layout">
          <div class="featured-event__media">
            <EventImage
              :src="featuredHeroEvent.mainImage"
              :alt="`${featuredHeroEvent.name} 海报`"
              :category-name="featuredHeroEvent.categoryName"
              eager
            />
          </div>

          <div class="featured-event__content">
            <el-tag class="featured-event__category" effect="plain">
              {{ featuredHeroEvent.categoryName }}
            </el-tag>
            <h2 class="featured-event__title">{{ featuredHeroEvent.name }}</h2>

            <div class="featured-event__meta">
              <span>
                <CalendarDays :size="16" aria-hidden="true" />
                <time :datetime="featuredHeroEvent.startTime.replace(' ', 'T')">
                  {{ formatDateTime(featuredHeroEvent.startTime) }}
                </time>
              </span>
              <span>
                <MapPin :size="16" aria-hidden="true" />
                {{ featuredHeroEvent.venue }}
              </span>
              <span>
                <Flame :size="16" aria-hidden="true" />
                {{ formatCount(featuredHeroEvent.hot) }} 热度
              </span>
            </div>

            <RouterLink
              v-slot="{ navigate }"
              custom
              :to="{ name: 'event-detail', params: { id: featuredHeroEvent.id } }"
            >
              <el-button type="primary" size="large" @click="navigate">
                <Ticket :size="18" aria-hidden="true" />
                <span>查看活动详情</span>
              </el-button>
            </RouterLink>
          </div>
        </div>
      </el-card>
    </section>

    <!-- 分类筛选与距离范围 -->
    <el-card class="discovery-filters" shadow="never" :body-style="{ padding: 'var(--space-3)' }">
      <div class="category-row">
        <nav class="category-tabs" aria-label="按活动分类筛选">
          <el-button
            class="category-btn"
            :type="selectedCategoryId == null ? 'primary' : 'default'"
            @click="selectCategory(null)"
          >
            全部热门
          </el-button>
          <template v-if="categoriesLoading">
            <el-skeleton :rows="0" animated class="category-skeleton">
              <template #template>
                <el-skeleton-item variant="button" class="category-skeleton__item" />
                <el-skeleton-item variant="button" class="category-skeleton__item" />
                <el-skeleton-item variant="button" class="category-skeleton__item" />
              </template>
            </el-skeleton>
          </template>
          <template v-else>
            <el-button
              v-for="category in categories"
              :key="String(category.id)"
              class="category-btn"
              :type="sameId(category.id, selectedCategoryId) ? 'primary' : 'default'"
              @click="selectCategory(category.id)"
            >
              {{ category.name }}
            </el-button>
          </template>
        </nav>

        <div class="nearby-control">
          <el-select
            v-model="radiusKm"
            class="radius-select"
            aria-label="活动距离范围"
            :disabled="locating"
            @change="changeRadius"
          >
            <el-option
              v-for="radius in radiusOptions"
              :key="radius.label"
              :label="radius.label"
              :value="radius.value"
            />
          </el-select>
        </div>
      </div>

      <el-alert
        v-if="categoriesError"
        :title="categoriesError"
        type="error"
        show-icon
        :closable="false"
        class="filter-alert"
      >
        <template #default>
          <el-button type="danger" link @click="loadCategories">重试</el-button>
        </template>
      </el-alert>

      <el-alert
        v-if="locationMessage"
        :title="locationMessage"
        :type="nearby ? 'success' : 'warning'"
        show-icon
        :closable="false"
        class="filter-alert"
      />
      <p v-else class="filter-hint">选择活动距离范围；选择具体距离后会按当前位置筛选。</p>
    </el-card>

    <!-- 活动卡片流 -->
    <section class="event-section" aria-labelledby="event-list-heading" :aria-busy="loading">
      <div class="section-header">
        <div>
          <h1 id="event-list-heading" class="section-heading">{{ listTitle }}</h1>
          <p class="section-copy">{{ listDescription }}</p>
        </div>
      </div>

      <div v-if="loading" class="event-grid" aria-label="正在加载活动">
        <EventCardSkeleton v-for="index in PAGE_SIZE" :key="index" />
      </div>

      <el-empty
        v-else-if="listError && events.length === 0"
        :description="listError"
      >
        <el-button type="primary" @click="loadEvents(true)">重新加载</el-button>
      </el-empty>

      <el-empty
        v-else-if="events.length === 0"
        :description="nearby ? '附近暂时没有活动，可以选择不限查看全部活动。' : '当前分类暂无活动，换个分类或回到热门活动看看。'"
      >
        <el-button
          v-if="nearby"
          type="primary"
          @click="changeRadius('all')"
        >
          查看全部活动
        </el-button>
        <el-button v-else type="primary" @click="selectCategory(null)">
          返回热门活动
        </el-button>
      </el-empty>

      <template v-else>
        <div class="event-grid">
          <EventCard
            v-for="(event, index) in visibleEvents"
            :key="String(event.id)"
            :event="event"
            :eager="index < 2"
          />
        </div>

        <div class="pagination-status">
          <div v-if="listError" class="load-more-error" role="alert">
            <AlertCircle :size="18" aria-hidden="true" />
            <span>{{ listError }}</span>
            <el-button type="danger" link @click="loadEvents(false)">
              <RefreshCw :size="15" aria-hidden="true" />
              重试
            </el-button>
          </div>
          <el-button
            v-else-if="hasMore"
            type="primary"
            plain
            size="large"
            class="load-more-btn"
            :loading="loadingMore"
            @click="loadEvents(false)"
          >
            {{ loadingMore ? '正在加载...' : '加载更多活动' }}
          </el-button>
          <p v-else class="list-end">已展示该分类下的全部演出与活动</p>
        </div>
      </template>
    </section>

    <!-- 热门现场评价 -->
    <section class="review-section" aria-labelledby="hot-reviews-heading" :aria-busy="reviewsLoading">
      <div class="section-header">
        <div>
          <h2 id="hot-reviews-heading" class="section-heading">现场动态与评价</h2>
          <p class="section-copy">来自真实现场观众的实况反馈与心得分享。</p>
        </div>
      </div>

      <div v-if="reviewsLoading" class="review-grid" aria-label="正在加载热门评价">
        <el-card
          v-for="index in 3"
          :key="index"
          class="review-skeleton"
          shadow="never"
        >
          <el-skeleton :rows="3" animated />
        </el-card>
      </div>

      <el-empty
        v-else-if="reviewsError"
        :description="reviewsError"
      >
        <el-button type="primary" @click="loadHotReviews">重试</el-button>
      </el-empty>

      <el-empty
        v-else-if="hotReviews.length === 0"
        description="还没有现场评价，参加演出后可发布首条现场实况。"
      />

      <div v-else class="review-grid">
        <ReviewCard
          v-for="review in hotReviews"
          :key="String(review.id)"
          :review="review"
          interactive
          :loading="String(likingReviewId) === String(review.id)"
          @like="toggleReviewLike"
        />
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.discover-page {
  gap: var(--space-8);
}

/* 推荐活动 */
.featured-event {
  overflow: hidden;
  border-radius: 8px;

  &__layout {
    display: grid;
    grid-template-columns: minmax(0, 3fr) minmax(18rem, 2fr);
  }

  &__media {
    min-height: 20rem;
    background: var(--color-surface-muted);
  }

  &__content {
    display: grid;
    align-content: center;
    justify-items: start;
    gap: var(--space-4);
    padding: var(--space-8);
  }

  &__category {
    font-weight: 700;
  }

  &__title {
    color: var(--color-ink);
    font-size: var(--text-page);
    font-weight: 800;
    max-width: 18ch;
    line-height: 1.2;
  }

  &__meta {
    display: grid;
    gap: var(--space-2);
    color: var(--color-ink-soft);
    font-size: var(--text-secondary);

    span {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
    }
  }
}

/* 分类筛选器与距离范围 */
.discovery-filters {
  border-radius: 8px;
}

.category-row {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.category-tabs {
  min-width: 0;
  display: flex;
  flex: 1;
  gap: var(--space-2);
  padding: 2px;
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.category-btn {
  font-weight: 600;
  flex: 0 0 auto;
}

.category-skeleton {
  display: flex;
  gap: var(--space-2);

  &__item {
    width: 5.5rem;
    height: 32px;
    border-radius: 4px;
  }
}

.nearby-control {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding-inline: var(--space-2);
}

.radius-select {
  width: 6.5rem;
}

.filter-alert {
  margin-top: var(--space-3);
}

.filter-hint {
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
  padding: var(--space-2) var(--space-1) 0;
}

/* 活动列表 */
.event-section {
  display: grid;
  gap: var(--space-6);
}

.event-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 17.5rem), 1fr));
  gap: var(--space-6);
}

/* 热门评价 */
.review-section {
  display: grid;
  gap: var(--space-6);
  padding-top: var(--space-10);
  border-top: 1px solid var(--color-border);
}

.review-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 22rem), 1fr));
  gap: var(--space-6);
}

.review-skeleton {
  min-height: 15rem;
  border-radius: 8px;
}

.pagination-status {
  min-height: 44px;
  display: grid;
  place-items: center;
  margin-top: var(--space-4);
}

.load-more-btn {
  min-width: 14rem;
}

.load-more-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  color: var(--color-danger);
  font-size: var(--text-secondary);
  text-align: center;
}

.list-end {
  color: var(--color-ink-faint);
  font-size: var(--text-secondary);
  text-align: center;
}

@media (max-width: 48rem) {
  .featured-event {
    &__layout {
      grid-template-columns: minmax(0, 1fr);
    }

    &__media {
      min-height: 0;
      aspect-ratio: 16 / 10;
    }

    &__content {
      padding: var(--space-5);
    }
  }
}

@media (max-width: 38rem) {
  .category-row {
    align-items: stretch;
    flex-direction: column;
  }

  .nearby-control {
    align-self: stretch;
    justify-content: space-between;
  }
}
</style>
