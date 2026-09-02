<script setup lang="ts">
import { CalendarDays, Flame, MapPin, MessageCircle } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { EventListItem } from '../../types/api'
import { formatCount, formatDateTime, formatDistance } from '../../utils/format'
import EventImage from './EventImage.vue'

withDefaults(
  defineProps<{
    event: EventListItem
    eager?: boolean
  }>(),
  { eager: false },
)
</script>

<template>
  <el-card class="event-card" :body-style="{ padding: '0px' }" shadow="never">
    <RouterLink
      class="event-card__link"
      :to="{ name: 'event-detail', params: { id: event.id } }"
      :aria-label="`查看活动：${event.name}`"
    >
      <div class="event-card__media">
        <EventImage
          :src="event.mainImage"
          :alt="`${event.name}活动海报`"
          :category-name="event.categoryName"
          :eager="eager"
        />
      </div>

      <div class="event-card__body">
        <el-tag class="event-card__category-tag" effect="plain" size="small">
          {{ event.categoryName }}
        </el-tag>
        <h3 class="event-card__title">{{ event.name }}</h3>

        <div class="event-card__facts">
          <p class="event-card__fact event-card__fact--time">
            <CalendarDays :size="15" aria-hidden="true" />
            <time :datetime="event.startTime.replace(' ', 'T')">
              {{ formatDateTime(event.startTime) }}
            </time>
          </p>
          <p class="event-card__fact">
            <MapPin :size="15" aria-hidden="true" />
            <span class="event-card__venue">{{ event.venue }}</span>
            <el-tag
              v-if="formatDistance(event.distance)"
              class="event-card__distance-tag"
              size="small"
              type="primary"
            >
              {{ formatDistance(event.distance) }}
            </el-tag>
          </p>
        </div>

        <div class="event-card__stats" aria-label="活动热度与评论数">
          <span class="event-card__stat-item event-card__stat-item--hot">
            <Flame :size="14" aria-hidden="true" />
            {{ formatCount(event.hot) }} 热度
          </span>
          <span class="event-card__stat-item">
            <MessageCircle :size="14" aria-hidden="true" />
            {{ formatCount(event.comments) }} 评论
          </span>
        </div>
      </div>
    </RouterLink>
  </el-card>
</template>

<style scoped lang="scss">
.event-card {
  min-width: 0;
  overflow: hidden;
  border-radius: 8px;

  &__link {
    min-height: 100%;
    display: flex;
    flex-direction: column;
    text-decoration: none;
  }

  &__media {
    position: relative;
    aspect-ratio: 16 / 10;
    overflow: hidden;
    background: var(--color-surface-muted);

  }

  &__category-tag {
    align-self: flex-start;
    font-weight: 600;
  }

  &__body {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    min-height: 10.5rem;
    padding: var(--space-4);
  }

  &__title {
    display: -webkit-box;
    min-height: 2.6em;
    overflow: hidden;
    font-size: var(--text-subheading);
    font-weight: 700;
    line-height: 1.4;
    letter-spacing: 0;
    color: var(--color-ink);
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    transition: color 160ms var(--ease-out);
  }

  &__facts {
    display: grid;
    gap: var(--space-2);
  }

  &__fact {
    min-width: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--color-ink-soft);
    font-size: var(--text-caption);
    line-height: 1.4;

    svg {
      flex: 0 0 auto;
      color: var(--color-primary);
    }

    &--time {
      font-variant-numeric: tabular-nums;
    }
  }

  &__venue {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__distance-tag {
    margin-left: auto;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  &__stats {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    margin-top: auto;
    padding-top: var(--space-3);
    border-top: 1px solid var(--color-border-subtle);
    color: var(--color-ink-muted);
    font-size: var(--text-xs);
    font-variant-numeric: tabular-nums;
  }

  &__stat-item {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);

    &--hot {
      color: var(--color-accent);
      font-weight: 600;
    }
  }

  &__link:hover .event-card__title {
    color: var(--color-primary);
  }
}
</style>
