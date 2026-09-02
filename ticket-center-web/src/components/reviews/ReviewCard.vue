<script setup lang="ts">
import { computed, ref } from 'vue'
import { Heart, MessageCircle, Trash2 } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { EventReview } from '../../types/api'
import { formatCount, formatDateTime } from '../../utils/format'
import { avatarOrFallback, imageOrFallback, splitImages } from '../../utils/images'

const props = withDefaults(
  defineProps<{
    review: EventReview
    interactive?: boolean
    loading?: boolean
    deletable?: boolean
    deleteLoading?: boolean
  }>(),
  { interactive: true, loading: false, deletable: false, deleteLoading: false },
)

const emit = defineEmits<{
  like: [review: EventReview]
  delete: [review: EventReview]
}>()

const avatarFailed = ref(false)
const reviewImages = computed(() => splitImages(props.review.images).slice(0, 3))
const avatarSource = computed(() => {
  if (avatarFailed.value) return avatarOrFallback('', props.review.userId)
  return avatarOrFallback(props.review.userIcon, props.review.userId)
})
</script>

<template>
  <el-card
    class="review-card"
    shadow="never"
    :body-style="{ padding: 'var(--space-4)', height: '100%' }"
    role="article"
  >
    <div class="review-card__layout">
      <header class="review-card__header">
        <RouterLink
          v-if="interactive"
          class="review-card__author"
          :to="{ name: 'person', params: { id: review.userId } }"
          :aria-label="`查看 ${review.userName} 的主页`"
        >
          <el-avatar
            :size="40"
            :src="avatarSource"
            :alt="`${review.userName}的头像`"
            @error="avatarFailed = true"
          />
          <span class="review-card__author-copy">
            <strong>{{ review.userName }}</strong>
            <time :datetime="review.createTime.replace(' ', 'T')">
              {{ formatDateTime(review.createTime) }}
            </time>
          </span>
        </RouterLink>

        <div v-else class="review-card__author">
          <el-avatar
            :size="40"
            :src="avatarSource"
            :alt="`${review.userName}的头像`"
            @error="avatarFailed = true"
          />
          <span class="review-card__author-copy">
            <strong>{{ review.userName }}</strong>
            <time :datetime="review.createTime.replace(' ', 'T')">
              {{ formatDateTime(review.createTime) }}
            </time>
          </span>
        </div>

        <el-popconfirm
          v-if="deletable"
          title="删除后无法恢复，确定删除吗？"
          confirm-button-text="删除"
          cancel-button-text="取消"
          confirm-button-type="danger"
          @confirm="emit('delete', review)"
        >
          <template #reference>
            <el-button
              class="review-card__delete"
              text
              circle
              :icon="Trash2"
              :loading="deleteLoading"
              aria-label="删除这条动态"
            />
          </template>
        </el-popconfirm>
      </header>

      <div class="review-card__content">
        <RouterLink
          v-if="interactive"
          class="review-card__content-link"
          :to="{ name: 'review-detail', params: { id: review.id } }"
          :aria-label="`查看评价：${review.title}`"
        >
          <h3 class="review-card__title">{{ review.title }}</h3>
          <p class="review-card__text">{{ review.content }}</p>
        </RouterLink>
        <template v-else>
          <h3 class="review-card__title">{{ review.title }}</h3>
          <p class="review-card__text">{{ review.content }}</p>
        </template>
      </div>

      <RouterLink
        v-if="interactive && reviewImages.length"
        class="review-card__gallery"
        :class="`review-card__gallery--${reviewImages.length}`"
        :to="{ name: 'review-detail', params: { id: review.id } }"
        :aria-label="`查看评价图片，共 ${reviewImages.length} 张`"
      >
        <el-image
          v-for="(image, index) in reviewImages"
          :key="`${image}-${index}`"
          class="review-card__gallery-item"
          :src="imageOrFallback(image)"
          :alt="`${review.title}的评价图片 ${index + 1}`"
          fit="cover"
          lazy
        />
      </RouterLink>
      <div
        v-else-if="reviewImages.length"
        class="review-card__gallery"
        :class="`review-card__gallery--${reviewImages.length}`"
      >
        <el-image
          v-for="(image, index) in reviewImages"
          :key="`${image}-${index}`"
          class="review-card__gallery-item"
          :src="imageOrFallback(image)"
          :alt="`${review.title}的评价图片 ${index + 1}`"
          fit="cover"
          lazy
        />
      </div>

      <footer class="review-card__footer">
        <el-button
          class="review-card__like"
          :type="review.isLike ? 'danger' : undefined"
          text
          :loading="loading"
          :disabled="!interactive"
          :aria-pressed="review.isLike"
          :aria-label="`${review.isLike ? '取消点赞' : '点赞'}，当前 ${review.liked} 个赞`"
          @click="emit('like', review)"
        >
          <Heart
            v-if="!loading"
            :size="16"
            :fill="review.isLike ? 'currentColor' : 'none'"
            aria-hidden="true"
          />
          <span>{{ formatCount(review.liked) }}</span>
        </el-button>

        <RouterLink
          v-if="interactive"
          v-slot="{ navigate }"
          custom
          :to="{ name: 'review-detail', params: { id: review.id } }"
        >
          <el-button
            class="review-card__comments"
            text
            :aria-label="`查看 ${review.comments} 条评论`"
            @click="navigate"
          >
            <MessageCircle :size="16" aria-hidden="true" />
            <span>{{ formatCount(review.comments) }}</span>
          </el-button>
        </RouterLink>
        <span v-else class="review-card__comments-static" aria-label="评论数">
          <MessageCircle :size="16" aria-hidden="true" />
          <span>{{ formatCount(review.comments) }}</span>
        </span>
      </footer>
    </div>
  </el-card>
</template>

<style scoped lang="scss">
.review-card {
  min-width: 0;
  border-radius: 8px;

  &__layout {
    min-height: 100%;
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  &__header {
    min-width: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
  }

  &__author {
    width: fit-content;
    max-width: 100%;
    min-height: 44px;
    display: flex;
    align-items: center;
    gap: var(--space-3);

    &:hover strong {
      color: var(--color-primary);
    }
  }

  &__author-copy {
    min-width: 0;
    display: grid;
    gap: 2px;

    strong {
      overflow: hidden;
      color: var(--color-ink);
      font-size: var(--text-secondary);
      font-weight: 700;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    time {
      color: var(--color-ink-muted);
      font-size: var(--text-caption);
      font-variant-numeric: tabular-nums;
    }
  }

  &__content-link {
    display: block;

    &:hover .review-card__title {
      color: var(--color-primary);
    }
  }

  &__title {
    color: var(--color-ink);
    font-size: var(--text-subheading);
    font-weight: 700;
    line-height: 1.4;
  }

  &__text {
    display: -webkit-box;
    margin-top: var(--space-2);
    overflow: hidden;
    color: var(--color-ink-soft);
    font-size: var(--text-secondary);
    line-height: 1.65;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
  }

  &__gallery {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--space-2);

    &--1 {
      grid-template-columns: minmax(0, 1fr);
    }

    &--2 {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  &__gallery-item {
    width: 100%;
    aspect-ratio: 4 / 3;
    overflow: hidden;
    border-radius: 6px;
    background: var(--color-surface-muted);
  }

  &__footer {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-top: auto;
    padding-top: var(--space-3);
    border-top: 1px solid var(--color-border-subtle);
  }

  &__like,
  &__comments {
    min-width: 44px;
    min-height: 44px;
    margin: 0;
    font-variant-numeric: tabular-nums;
  }

  &__comments-static {
    min-width: 44px;
    min-height: 44px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    color: var(--color-ink-muted);
    font-size: var(--text-caption);
    font-variant-numeric: tabular-nums;
  }

  &__delete {
    flex: 0 0 auto;
    color: var(--color-ink-muted);

    &:hover {
      color: var(--color-danger);
    }
  }
}
</style>
