/** IDs can be numbers in small demo data and strings for values beyond JS's safe range. */
export type ApiId = string | number

/** Datetimes are returned by Spring as `yyyy-MM-dd HH:mm:ss`. */
export type BackendDateTime = string

export interface ApiEnvelope<T> {
  code: number
  msg: string
  data: T
}

export interface PageQuery {
  current?: number
  size?: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}

export interface ScrollQuery {
  max: number
  offset?: number
  size?: number
}

export interface ScrollResult<T> {
  list: T[]
  minTime?: number
  offset?: number
}

export interface LoginRequest {
  phone: string
  code: string
}

export interface PasswordLoginRequest {
  phone: string
  password: string
}

export interface User {
  id: ApiId
  nickName: string
  icon: string
}

export type FollowListTab = 'followees' | 'fans'

export interface UserInfo {
  userId: ApiId
  city: string | null
  introduce: string | null
  fans: number
  followee: number
  gender: boolean | null
  birthday: string | null
  credits: number
}

export interface UserProfileUpdateRequest {
  nickName?: string
  icon?: string
  city?: string
  introduce?: string
  gender?: boolean | null
  birthday?: string | null
}

export interface EventCategory {
  id: ApiId
  name: string
  icon: string
  sort: number
}

export interface EventListItem {
  id: ApiId
  name: string
  categoryId: ApiId
  categoryName: string
  venue: string
  address: string
  mainImage: string
  startTime: BackendDateTime
  durationMin: number
  hot: number
  comments: number
  distance: number | null
}

export interface EventDetail extends EventListItem {
  x: number | null
  y: number | null
  images: string
  intro: string
  status: number
  tickets?: Ticket[]
}

export interface EventQuery extends PageQuery {
  categoryId: ApiId
  x?: number
  y?: number
  radius?: number
}

export interface NearbyEventQuery extends PageQuery {
  x: number
  y: number
  radius?: number
}

export interface Ticket {
  id: ApiId
  eventId: ApiId
  title: string
  /** Integer amount in cents. */
  price: number
  /** Integer amount in cents. */
  originalPrice: number | null
  type: number
  status: number
  stock: number
  beginTime: BackendDateTime
  endTime: BackendDateTime
}

export type OrderStatus = 0 | 1 | 2

export interface TicketOrder {
  id: ApiId
  userId: ApiId
  ticketId: ApiId
  ticketTitle: string
  eventId: ApiId
  eventName: string
  /** Integer amount in cents. */
  price: number
  status: OrderStatus
  statusDesc: string
  createTime: BackendDateTime
  payTime: BackendDateTime | null
}

export interface EventReview {
  id: ApiId
  eventId: ApiId
  userId: ApiId
  userName: string
  userIcon: string
  title: string
  images: string
  content: string
  liked: number
  isLike: boolean
  comments: number
  createTime: BackendDateTime
}

export interface EventReviewCreateRequest {
  eventId: ApiId
  title: string
  content: string
  images?: string
}

export interface EventReviewComment {
  id: ApiId
  reviewId: ApiId
  userId: ApiId
  userName: string
  userIcon: string
  content: string
  createTime: BackendDateTime
}

export interface SignDay {
  dayName: string
  date: string
  dayOfMonth: number
  isSigned: boolean
  isToday: boolean
  isFuture: boolean
}

export interface SignStatus {
  isTodaySigned: boolean
  continuousDays: number
  monthlyTotalDays: number
  currentMonth: number
  weekDays: SignDay[]
}

export interface CreditLog {
  id: ApiId
  userId: ApiId
  bizType: number
  bizId: string
  changeAmount: number
  balance: number
  description: string
  createTime: BackendDateTime
}
