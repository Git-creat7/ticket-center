<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { MapPin, UserPlus, UserRound } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import ReviewCard from '../components/reviews/ReviewCard.vue'
import { followApi, reviewApi, userApi } from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type { EventReview, FollowListTab, User, UserInfo } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { updateReviewLikeState } from '../utils/reviews'
import { avatarOrFallback } from '../utils/images'
import FollowListDrawer from '../components/profile/FollowListDrawer.vue'

const route = useRoute()
const auth = useAuthStore()
const notifications = useNotificationStore()

const profile = ref<User | null>(null)
const info = ref<UserInfo | null>(null)
const loading = ref(true)
const error = ref('')
const isFollowing = ref(false)
const followLoading = ref(false)
const followDrawerVisible = ref(false)
const followDrawerTab = ref<FollowListTab>('followees')
const reviews = ref<EventReview[]>([])
const reviewsLoading = ref(false)
const reviewsError = ref('')
const reviewTotal = ref(0)
const reviewPage = ref(1)
const likingId = ref<string | number | null>(null)
const deletingReviewId = ref<string | number | null>(null)
const reviewPageSize = 6
let reviewRequestId = 0

const userId = computed(() => String(route.params.id))
const isSelf = computed(
  () => auth.user != null && String(auth.user.id) === userId.value,
)

function openFollowList(tab: FollowListTab) {
  followDrawerTab.value = tab
  followDrawerVisible.value = true
}

async function load() {
  loading.value = true
  error.value = ''
  profile.value = null
  info.value = null
  reviews.value = []
  reviewTotal.value = 0
  reviewRequestId++
  isFollowing.value = false
  try {
    if (!auth.user) await auth.fetchCurrentUser()
    profile.value = await userApi.getById(userId.value)
    const [profileInfo, followState] = await Promise.all([
      userApi.getInfo(userId.value).catch(() => null),
      isSelf.value ? Promise.resolve(false) : followApi.isFollowing(userId.value),
    ])
    info.value = profileInfo
    isFollowing.value = followState
    void loadReviews(1)
  } catch (requestError) {
    error.value = getErrorMessage(requestError, '用户资料加载失败')
  } finally {
    loading.value = false
  }
}

async function loadReviews(page = 1) {
  const currentRequest = ++reviewRequestId
  reviewsLoading.value = true
  reviewsError.value = ''
  try {
    const result = await reviewApi.byUser(userId.value, {
      current: page,
      size: reviewPageSize,
    })
    if (currentRequest !== reviewRequestId) return
    reviews.value = result.records || []
    reviewTotal.value = result.total || 0
    reviewPage.value = result.current || 1
  } catch (requestError) {
    if (currentRequest !== reviewRequestId) return
    reviews.value = []
    reviewsError.value = getErrorMessage(requestError, '动态加载失败，请稍后重试')
  } finally {
    if (currentRequest === reviewRequestId) reviewsLoading.value = false
  }
}

async function toggleLike(review: EventReview) {
  if (likingId.value != null) return
  likingId.value = review.id
  try {
    await reviewApi.toggleLike(review.id)
    updateReviewLikeState(review)
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '点赞操作失败，请重试'), 'error')
  } finally {
    likingId.value = null
  }
}

async function deleteReview(review: EventReview) {
  if (!isSelf.value || deletingReviewId.value != null) return
  deletingReviewId.value = review.id
  try {
    await reviewApi.remove(review.id)
    const nextTotal = Math.max(0, reviewTotal.value - 1)
    const lastPage = Math.max(1, Math.ceil(nextTotal / reviewPageSize))
    await loadReviews(Math.min(reviewPage.value, lastPage))
    notifications.notify('动态已删除', 'success')
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '动态删除失败，请重试'), 'error')
  } finally {
    deletingReviewId.value = null
  }
}

async function toggleFollow() {
  if (isSelf.value || followLoading.value) return
  followLoading.value = true
  const nextState = !isFollowing.value
  try {
    await followApi.set(userId.value, nextState)
    isFollowing.value = nextState
    if (info.value) {
      info.value.fans = Math.max(0, (info.value.fans ?? 0) + (nextState ? 1 : -1))
    }
    notifications.notify(nextState ? '已关注这位作者' : '已取消关注', 'success')
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '关注操作失败'), 'error')
  } finally {
    followLoading.value = false
  }
}

watch(userId, load, { immediate: true })
</script>

<template>
  <div class="page-container page-stack person-page">
    <el-card v-if="loading" class="person-card" shadow="never" aria-busy="true">
      <el-skeleton animated>
        <template #template>
          <div class="person-loading">
            <el-skeleton-item variant="circle" class="person-loading__avatar" />
            <el-skeleton-item variant="h3" class="person-loading__name" />
            <el-skeleton-item variant="text" class="person-loading__bio" />
          </div>
        </template>
      </el-skeleton>
    </el-card>

    <div v-else-if="error" class="person-error">
      <el-alert :title="error" type="error" show-icon :closable="false" />
      <el-button type="primary" plain @click="load">重新加载</el-button>
    </div>

    <el-empty v-else-if="!profile" description="用户可能已注销或资料暂不可用">
      <template #description>
        <div class="person-empty__copy">
          <strong>找不到这位用户</strong>
          <span>用户可能已注销或资料暂不可用。</span>
        </div>
      </template>
    </el-empty>

    <template v-else>
      <el-card class="person-card" shadow="never">
        <header class="person-hero">
          <el-avatar
            :size="88"
            :src="avatarOrFallback(profile.icon, profile.id)"
            :alt="`${profile.nickName}的头像`"
          >
            <UserRound :size="36" aria-hidden="true" />
          </el-avatar>

          <div class="person-hero__copy">
            <h1>{{ profile.nickName }}</h1>
            <p v-if="info?.introduce">{{ info.introduce }}</p>
            <p v-else>这位用户还没有填写个人简介。</p>
            <span v-if="info?.city" class="person-location">
              <MapPin :size="16" aria-hidden="true" /> {{ info.city }}
            </span>
          </div>

          <RouterLink v-if="isSelf" v-slot="{ navigate }" custom to="/me">
            <el-button @click="navigate">账号中心</el-button>
          </RouterLink>
          <el-button
            v-else
            :type="isFollowing ? 'default' : 'primary'"
            :plain="isFollowing"
            :loading="followLoading"
            @click="toggleFollow"
          >
            <UserPlus v-if="!followLoading" :size="18" aria-hidden="true" />
            {{ followLoading ? '处理中' : isFollowing ? '已关注' : '关注作者' }}
          </el-button>
        </header>

        <section v-if="info" class="person-stats" aria-label="用户数据">
          <button
            type="button"
            class="person-stat-button"
            aria-label="查看关注列表"
            @click="openFollowList('followees')"
          >
            <el-statistic title="关注" :value="info.followee ?? 0" />
          </button>
          <button
            type="button"
            class="person-stat-button"
            aria-label="查看粉丝列表"
            @click="openFollowList('fans')"
          >
            <el-statistic title="关注者" :value="info.fans ?? 0" />
          </button>
          <div class="person-stat-static">
            <el-statistic title="积分" :value="info.credits ?? 0" />
          </div>
        </section>
      </el-card>

      <FollowListDrawer
        v-model:visible="followDrawerVisible"
        v-model:tab="followDrawerTab"
        :user-id="userId"
      />

      <section class="reviews-section" aria-labelledby="person-reviews-title">
        <div class="reviews-heading">
          <h2 id="person-reviews-title" class="section-heading">动态</h2>
          <span v-if="reviewTotal > 0">{{ reviewTotal }} 篇</span>
        </div>

        <div v-if="reviewsLoading" class="reviews-loading" aria-busy="true">
          <el-card v-for="index in 2" :key="index" shadow="never">
            <el-skeleton animated :rows="4" />
          </el-card>
        </div>

        <div v-else-if="reviewsError" class="reviews-error">
          <el-alert :title="reviewsError" type="error" show-icon :closable="false" />
          <el-button type="primary" plain @click="loadReviews(reviewPage)">重新加载</el-button>
        </div>

        <el-empty
          v-else-if="reviews.length === 0"
          :description="isSelf ? '你还没有发布动态' : '这位用户还没有发布动态'"
          :image-size="80"
        />

        <template v-else>
          <div class="person-reviews">
            <ReviewCard
              v-for="review in reviews"
              :key="String(review.id)"
              :review="review"
              :loading="String(likingId) === String(review.id)"
              :deletable="isSelf"
              :delete-loading="String(deletingReviewId) === String(review.id)"
              @like="toggleLike"
              @delete="deleteReview"
            />
          </div>

          <el-pagination
            v-if="reviewTotal > reviewPageSize"
            v-model:current-page="reviewPage"
            class="reviews-pagination"
            :page-size="reviewPageSize"
            :total="reviewTotal"
            layout="prev, pager, next"
            @current-change="loadReviews"
          />
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.person-page {
  max-width: 52rem;
}

.person-card {
  border-radius: var(--radius-sm);
}

.person-card :deep(.el-card__body) {
  padding: var(--space-6);
}

.person-loading {
  min-height: 14rem;
  display: grid;
  place-items: center;
  align-content: center;
  gap: var(--space-4);
}

.person-loading__avatar {
  width: 88px;
  height: 88px;
}

.person-loading__name {
  width: 10rem;
}

.person-loading__bio {
  width: min(100%, 24rem);
}

.person-error {
  display: grid;
  justify-items: start;
  gap: var(--space-4);
}

.person-empty__copy {
  display: grid;
  gap: var(--space-2);
  color: var(--color-ink-muted);
}

.person-empty__copy strong {
  color: var(--color-ink);
  font-size: var(--text-section);
}

.person-hero {
  display: grid;
  justify-items: center;
  gap: var(--space-4);
  text-align: center;
}

.person-hero__copy {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
}

.person-hero__copy h1 {
  font-size: var(--text-page);
  letter-spacing: 0;
}

.person-hero__copy p {
  max-width: 48ch;
  color: var(--color-ink-soft);
}

.person-location {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
}

.person-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin-top: var(--space-6);
  padding-top: var(--space-5);
  border-top: 1px solid var(--color-border);
}

.person-stat-button,
.person-stat-static {
  min-width: 0;
  padding: var(--space-2);
}

.person-stat-button {
  border: 0;
  background: transparent;
  transition: background-color 120ms ease;
}

.person-stat-button:hover {
  background: var(--color-surface-hover);
}

.person-stats :deep(.el-statistic) {
  text-align: center;
}

.person-stats > * + * {
  border-left: 1px solid var(--color-border);
}

.person-stats :deep(.el-statistic__head) {
  margin-bottom: var(--space-1);
  color: var(--color-ink-muted);
}

.person-stats :deep(.el-statistic__number) {
  color: var(--color-ink);
  font-size: var(--text-section);
  font-weight: 700;
}

.reviews-section,
.reviews-loading,
.person-reviews,
.reviews-error {
  display: grid;
  gap: var(--space-4);
}

.reviews-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.reviews-heading span {
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
}

.reviews-loading :deep(.el-card__body) {
  padding: var(--space-5);
}

.reviews-error {
  justify-items: start;
}

.reviews-section :deep(.el-empty) {
  padding-block: var(--space-6);
}

.reviews-pagination {
  justify-self: center;
}

@media (min-width: 48rem) {
  .person-hero {
    grid-template-columns: auto 1fr auto;
    justify-items: start;
    text-align: left;
  }

  .person-hero__copy {
    align-self: center;
    justify-items: start;
  }

  .person-hero > .el-button {
    align-self: center;
  }
}

@media (max-width: 36rem) {
  .person-card :deep(.el-card__body) {
    padding: var(--space-5);
  }
}
</style>
