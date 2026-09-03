<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Radio, RefreshCw } from 'lucide-vue-next'
import ReviewCard from '../components/reviews/ReviewCard.vue'
import { reviewApi } from '../services/api'
import { useNotificationStore } from '../stores/notifications'
import type { EventReview } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { updateReviewLikeState } from '../utils/reviews'

// 关注动态流状态
const notifications = useNotificationStore()
const reviews = ref<EventReview[]>([])
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const hasMore = ref(true)

// Redis Feed 滚动分页游标参数
const nextMax = ref(Date.now())
const nextOffset = ref(0)
const likingId = ref<string | number | null>(null)

async function loadFollowedReviews(reset = false) {
  if (reset) {
    reviews.value = []
    nextMax.value = Date.now()
    nextOffset.value = 0
    hasMore.value = true
    error.value = ''
    loading.value = true
  } else {
    loadingMore.value = true
  }

  try {
    const result = await reviewApi.followed({
      max: nextMax.value,
      offset: nextOffset.value,
      size: 5,
    })
    reviews.value.push(...result.list)
    hasMore.value = result.list.length === 5 && result.minTime != null
    if (result.minTime != null) nextMax.value = Number(result.minTime)
    nextOffset.value = result.offset ?? 0
  } catch (requestError) {
    error.value = getErrorMessage(requestError, '关注动态加载失败，请稍后重试')
  } finally {
    loading.value = false
    loadingMore.value = false
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

onMounted(() => loadFollowedReviews(true))
</script>

<template>
  <div class="page-container page-stack following-page">
    <header class="page-header">
      <div>
        <h1 class="page-heading">关注动态</h1>
        <p class="page-lead">实时查看你关注的观众与乐评人发布的演出新动态。</p>
      </div>
      <el-tooltip content="刷新动态" placement="bottom">
        <el-button
          circle
          :loading="loading"
          :disabled="loadingMore"
          aria-label="刷新关注动态"
          @click="loadFollowedReviews(true)"
        >
          <RefreshCw v-if="!loading" :size="18" aria-hidden="true" />
        </el-button>
      </el-tooltip>
    </header>

    <div v-if="loading" class="feed-skeleton" aria-busy="true" aria-label="正在加载关注动态">
      <el-card v-for="index in 3" :key="index" class="feed-skeleton__item" shadow="never">
        <el-skeleton animated :rows="3" />
      </el-card>
    </div>

    <div v-else-if="error && reviews.length === 0" class="feed-error">
      <el-alert :title="error" type="error" show-icon :closable="false" />
      <el-button type="primary" plain @click="loadFollowedReviews(true)">重新加载</el-button>
    </div>

    <el-empty
      v-else-if="reviews.length === 0"
      description="在探索或评价详情中关注感兴趣的作者，他们的最新现场分享将呈现在这里。"
      :image-size="96"
    >
      <template #description>
        <div class="feed-empty__copy">
          <Radio :size="28" aria-hidden="true" />
          <strong>还没有关注动态</strong>
          <span>关注感兴趣的作者后，他们的最新现场分享会出现在这里。</span>
        </div>
      </template>
      <RouterLink v-slot="{ navigate }" custom to="/discover">
        <el-button type="primary" @click="navigate">去发现精彩演出</el-button>
      </RouterLink>
    </el-empty>

    <section v-else class="feed" aria-label="关注动态列表">
      <ReviewCard
        v-for="review in reviews"
        :key="String(review.id)"
        :review="review"
        interactive
        :loading="String(likingId) === String(review.id)"
        @like="toggleLike"
      />

      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
      />

      <el-button
        v-if="hasMore"
        class="load-more"
        :loading="loadingMore"
        @click="loadFollowedReviews(false)"
      >
        {{ loadingMore ? '正在加载...' : '查看更多动态' }}
      </el-button>
      <el-divider v-else class="feed-end">已展示全部关注动态</el-divider>
    </section>
  </div>
</template>

<style scoped>
.following-page {
  max-width: 48rem;
}

.feed,
.feed-skeleton {
  display: grid;
  gap: var(--space-5);
}

.feed-skeleton__item {
  min-height: 13rem;
  border-radius: var(--radius-sm);
}

.feed-skeleton__item :deep(.el-card__body) {
  padding: var(--space-5);
}

.feed-error {
  display: grid;
  justify-items: start;
  gap: var(--space-4);
}

.feed-empty__copy {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  max-width: 34rem;
  color: var(--color-ink-muted);
}

.feed-empty__copy strong {
  color: var(--color-ink);
  font-size: var(--text-section);
}

.load-more {
  min-width: 12rem;
  justify-self: center;
}

.feed-end {
  color: var(--color-ink-faint);
  font-size: var(--text-secondary);
}
</style>
