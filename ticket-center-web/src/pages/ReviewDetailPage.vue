<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, Heart, UserPlus } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/ui/EmptyState.vue'
import ErrorState from '../components/ui/ErrorState.vue'
import ReviewComments from '../components/reviews/ReviewComments.vue'
import { followApi, reviewApi } from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type { EventReview, User } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { formatCount, formatDateTime } from '../utils/format'
import { avatarOrFallback, imageOrFallback, splitImages } from '../utils/images'
import { updateReviewLikeState } from '../utils/reviews'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

const review = ref<EventReview | null>(null)
const likedUsers = ref<User[]>([])
const loading = ref(true)
const error = ref('')
const likeLoading = ref(false)
const followLoading = ref(false)
const isFollowing = ref(false)

const reviewId = computed(() => String(route.params.id))
const images = computed(() => splitImages(review.value?.images).map((image) => imageOrFallback(image)))
const isSelf = computed(
  () => review.value != null && auth.user != null && String(review.value.userId) === String(auth.user.id),
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    review.value = await reviewApi.getById(reviewId.value)
    likedUsers.value = await reviewApi.likes(reviewId.value)
    if (auth.isAuthenticated && !auth.user) await auth.fetchCurrentUser()
    if (auth.isAuthenticated && !isSelf.value) {
      isFollowing.value = await followApi.isFollowing(review.value.userId)
    }
  } catch (requestError) {
    error.value = getErrorMessage(requestError, '评价详情加载失败')
  } finally {
    loading.value = false
  }
}

function requireLogin(): boolean {
  if (auth.isAuthenticated) return true
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

async function toggleLike() {
  if (!review.value || !requireLogin() || likeLoading.value) return
  likeLoading.value = true
  try {
    await reviewApi.toggleLike(review.value.id)
    updateReviewLikeState(review.value)
    likedUsers.value = await reviewApi.likes(review.value.id)
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '点赞失败'), 'error')
  } finally {
    likeLoading.value = false
  }
}

async function toggleFollow() {
  if (!review.value || !requireLogin() || followLoading.value || isSelf.value) return
  followLoading.value = true
  const nextState = !isFollowing.value
  try {
    await followApi.set(review.value.userId, nextState)
    isFollowing.value = nextState
    notifications.notify(nextState ? '已关注这位作者' : '已取消关注', 'success')
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '关注操作失败'), 'error')
  } finally {
    followLoading.value = false
  }
}

function updateCommentCount(count: number) {
  if (review.value) review.value.comments = count
}

onMounted(load)
</script>

<template>
  <div class="page-container review-detail">
    <el-button text :icon="ArrowLeft" class="review-detail__back" @click="router.back()">返回</el-button>

    <el-skeleton v-if="loading" :rows="6" animated class="review-loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState v-else-if="!review" title="评价不存在" description="这条内容可能已经被删除。" />

    <article v-else class="review-article">
      <header class="review-author">
        <RouterLink class="review-author__identity" :to="`/people/${review.userId}`">
          <el-avatar :size="44" :src="avatarOrFallback(review.userIcon, review.userId)" />
          <span>
            <strong>{{ review.userName }}</strong>
            <small>{{ formatDateTime(review.createTime) }}</small>
          </span>
        </RouterLink>
        <el-button
          v-if="!isSelf"
          :type="isFollowing ? 'default' : 'primary'"
          :plain="isFollowing"
          :loading="followLoading"
          :icon="UserPlus"
          @click="toggleFollow"
        >
          {{ isFollowing ? '已关注' : '关注作者' }}
        </el-button>
      </header>

      <h1>{{ review.title }}</h1>
      <p class="review-article__content">{{ review.content }}</p>

      <div v-if="images.length" class="review-gallery">
        <el-image
          v-for="(image, index) in images"
          :key="image"
          :src="image"
          :alt="`${review.title}配图 ${index + 1}`"
          :preview-src-list="images"
          :initial-index="index"
          fit="cover"
          lazy
          preview-teleported
        />
      </div>

      <footer class="review-actions">
        <el-button
          :type="review.isLike ? 'danger' : 'default'"
          :plain="!review.isLike"
          :loading="likeLoading"
          @click="toggleLike"
        >
          <Heart :size="18" :fill="review.isLike ? 'currentColor' : 'none'" aria-hidden="true" />
          {{ review.isLike ? '已赞' : '点赞' }} {{ formatCount(review.liked) }}
        </el-button>

        <div v-if="likedUsers.length" class="liked-users" aria-label="最近点赞用户">
          <el-tooltip v-for="user in likedUsers.slice(0, 8)" :key="String(user.id)" :content="user.nickName">
            <el-avatar :size="30" :src="avatarOrFallback(user.icon, user.id)" />
          </el-tooltip>
        </div>
      </footer>
    </article>

    <ReviewComments
      v-if="review && !loading && !error"
      :review-id="review.id"
      @count-change="updateCommentCount"
    />
  </div>
</template>

<style scoped>
.review-detail {
  max-width: 52rem;
  padding-block: var(--space-4) var(--space-16);
}

.review-detail__back {
  margin-bottom: var(--space-4);
}

.review-loading {
  padding: var(--space-6);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.review-article {
  display: grid;
  gap: var(--space-6);
}

.review-author,
.review-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.review-author__identity {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.review-author__identity > span {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.review-author__identity strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-author__identity small {
  color: var(--color-ink-muted);
}

.review-article h1 {
  font-size: var(--text-page);
}

.review-article__content {
  max-width: 72ch;
  white-space: pre-wrap;
}

.review-gallery {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

.review-gallery :deep(.el-image) {
  width: 100%;
  aspect-ratio: 4 / 3;
  border-radius: var(--radius-sm);
  background: var(--color-surface-muted);
}

.review-gallery :deep(img) {
  width: 100%;
  height: 100%;
}

.review-gallery :deep(.el-image:first-child:last-child),
.review-gallery :deep(.el-image:nth-last-child(odd):first-child) {
  grid-column: 1 / -1;
}

.review-actions {
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.review-actions :deep(.el-button) {
  gap: var(--space-2);
}

.liked-users {
  display: flex;
  padding-left: var(--space-3);
}

.liked-users :deep(.el-avatar) {
  margin-left: -8px;
  border: 2px solid var(--color-surface);
}

@media (min-width: 48rem) {
  .review-detail {
    padding-block: var(--space-8) var(--space-16);
  }
}

@media (max-width: 36rem) {
  .review-author {
    align-items: flex-start;
  }

  .review-gallery {
    grid-template-columns: 1fr;
  }

  .review-gallery :deep(.el-image) {
    grid-column: auto;
  }
}
</style>
