export const FALLBACK_EVENT_IMAGE = '/imgs/events/fallback.jpg'
export const FALLBACK_USER_AVATAR = 'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80'

export const CURATED_EVENT_IMAGES: Record<string, string> = {
  '演唱会': '/imgs/events/event1.jpg',
  '话剧歌剧': '/imgs/events/event2.jpg',
  '展览': '/imgs/events/event3.jpg',
  '体育赛事': '/imgs/events/event4.jpg',
  '音乐节': '/imgs/events/event5.jpg',
  'default': FALLBACK_EVENT_IMAGE,
}

export const CURATED_USER_AVATARS: string[] = [
  '/imgs/avatar1.jpg'
]

export function splitImages(value: string | null | undefined): string[] {
  if (!value) return []
  return value.split(',').map((url) => url.trim()).filter(Boolean)
}

export function joinImages(images: readonly string[]): string {
  return images.map((url) => url.trim()).filter(Boolean).join(',')
}

export function imageOrFallback(value: string | null | undefined, categoryName?: string): string {
  const trimmed = value?.trim()
  if (trimmed && (trimmed.startsWith('http://') || trimmed.startsWith('https://') || trimmed.startsWith('/'))) {
    return trimmed
  }
  if (categoryName && CURATED_EVENT_IMAGES[categoryName]) {
    return CURATED_EVENT_IMAGES[categoryName]
  }
  return FALLBACK_EVENT_IMAGE
}

export function avatarOrFallback(value: string | null | undefined, seed?: number | string): string {
  const trimmed = value?.trim()
  // 站内绝对路径也有效，上传接口返回的是 /uploads/...
  if (trimmed && (trimmed.startsWith('http://') || trimmed.startsWith('https://') || trimmed.startsWith('/'))) {
    return trimmed
  }
  if (seed != null) {
    const num = typeof seed === 'number' ? seed : seed.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0)
    const index = Math.abs(num) % CURATED_USER_AVATARS.length
    return CURATED_USER_AVATARS[index]
  }
  return FALLBACK_USER_AVATAR
}
