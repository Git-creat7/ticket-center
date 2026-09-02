<template>
  <el-card
    class="event-card-skeleton"
    shadow="never"
    :body-style="{ padding: '0' }"
    role="status"
    aria-label="活动加载中"
  >
    <el-skeleton animated>
      <template #template>
        <el-skeleton-item variant="image" class="event-card-skeleton__media" />
        <div class="event-card-skeleton__body" aria-hidden="true">
          <el-skeleton-item variant="h3" class="event-card-skeleton__title" />
          <el-skeleton-item variant="text" class="event-card-skeleton__line" />
          <el-skeleton-item variant="text" class="event-card-skeleton__line event-card-skeleton__line--wide" />
          <div class="event-card-skeleton__footer">
            <el-skeleton-item variant="text" class="event-card-skeleton__stat" />
            <el-skeleton-item variant="text" class="event-card-skeleton__stat" />
          </div>
        </div>
      </template>
    </el-skeleton>
  </el-card>
</template>

<style scoped>
.event-card-skeleton {
  min-width: 0;
  overflow: hidden;
  border-radius: 8px;
}

.event-card-skeleton__media {
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 10;
  border-radius: 0;
}

.event-card-skeleton__body {
  min-height: 10.5rem;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
}

.event-card-skeleton__title {
  width: 82%;
}

.event-card-skeleton__line {
  width: 68%;
}

.event-card-skeleton__line--wide {
  width: 88%;
}

.event-card-skeleton__footer {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: auto;
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.event-card-skeleton__stat {
  width: 4.5rem;
}
</style>
