/**
 * 精选高清网络图库资源与默认占位图配置
 */
export const FALLBACK_EVENT_IMAGE = '/imgs/events/fallback.jpg'
export const FALLBACK_USER_AVATAR = 'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80'

/**
 * 精选分类高清网络海报图库（当图片为空或加载失败时按分类/索引提供拟真场景图）
 */
export const CURATED_EVENT_IMAGES: Record<string, string> = {
  '演唱会': '/imgs/events/event1.jpg',
  '话剧歌剧': '/imgs/events/event2.jpg',
  '展览': '/imgs/events/event3.jpg',
  '体育赛事': '/imgs/events/event4.jpg',
  '音乐节': '/imgs/events/event5.jpg',
  'default': FALLBACK_EVENT_IMAGE,
}

/**
 * 精选乐迷观众高清网络头像库
 */
export const CURATED_USER_AVATARS: string[] = [
  '/imgs/avatar1.jpg'
]

/**
 * 拆分后端存储的逗号分隔图片 URL 字符串
 * @param value 后端返回的图片字段（如 "img1.jpg,img2.jpg"）
 */
export function splitImages(value: string | null | undefined): string[] {
  if (!value) return []
  return value.split(',').map((url) => url.trim()).filter(Boolean)
}

/**
 * 将多张图片 URL 数组合并为后端所需的逗号分隔格式
 * @param images 图片 URL 列表
 */
export function joinImages(images: readonly string[]): string {
  return images.map((url) => url.trim()).filter(Boolean).join(',')
}

/**
 * 获取有效活动海报图片路径，若为空或为相对失效路径则使用精选网络海报图
 * @param value 后端返回的图片路径
 * @param categoryName 可选分类名称
 */
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

/**
 * 获取有效用户头像图片路径，空值或失效时自动匹配精选用户网图
 * @param value 用户头像地址
 * @param seed 可选随机或用户 ID 种子
 */
export function avatarOrFallback(value: string | null | undefined, seed?: number | string): string {
  const trimmed = value?.trim()
  // 任意站内绝对路径都视为有效：上传接口返回的是 /uploads/...，
  // 只放行 /static/ 会把用户刚传的头像判为无效，静默退回默认图，看起来像"上传没生效"
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
