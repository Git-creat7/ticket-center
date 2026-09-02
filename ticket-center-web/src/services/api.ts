import { apiRequest } from './http'
import type {
  ApiId,
  CreditLog,
  EventCategory,
  EventDetail,
  EventListItem,
  EventQuery,
  NearbyEventQuery,
  EventReview,
  EventReviewComment,
  EventReviewCreateRequest,
  LoginRequest,
  PasswordLoginRequest,
  OrderStatus,
  PageQuery,
  PageResult,
  ScrollQuery,
  ScrollResult,
  SignStatus,
  Ticket,
  TicketOrder,
  User,
  UserInfo,
} from '../types/api'

export const authApi = {
  sendCode: (phone: string) => apiRequest<void>({
    method: 'POST',
    url: '/user/code',
    params: { phone },
  }),
  login: (payload: LoginRequest) => apiRequest<string>({
    method: 'POST',
    url: '/user/login',
    data: payload,
  }),
  loginByPassword: (payload: PasswordLoginRequest) => apiRequest<string>({
    method: 'POST',
    url: '/user/login/password',
    data: payload,
  }),
  logout: () => apiRequest<void>({ method: 'POST', url: '/user/logout' }),
}

export const userApi = {
  me: () => apiRequest<User>({ method: 'GET', url: '/user/me' }),
  getById: (id: ApiId) => apiRequest<User>({ method: 'GET', url: `/user/${id}` }),
  getInfo: (id: ApiId) => apiRequest<UserInfo>({ method: 'GET', url: `/user/info/${id}` }),
  sign: () => apiRequest<void>({ method: 'POST', url: '/user/sign' }),
  signStatus: () => apiRequest<SignStatus>({ method: 'GET', url: '/user/sign/status' }),
  creditLogs: (params: PageQuery = {}) => apiRequest<PageResult<CreditLog>>({
    method: 'GET',
    url: '/user/credits/logs',
    params,
  }),
  updateProfile: (payload: import('../types/api').UserProfileUpdateRequest) => apiRequest<void>({
    method: 'PUT',
    url: '/user/profile',
    data: payload,
  }),
}

export const categoryApi = {
  list: () => apiRequest<EventCategory[]>({ method: 'GET', url: '/event-category/list' }),
}

export const eventApi = {
  getById: (id: ApiId) => apiRequest<EventDetail>({ method: 'GET', url: `/event/${id}` }),
  hot: (params: PageQuery = {}) => apiRequest<EventListItem[]>({
    method: 'GET',
    url: '/event/hot',
    params,
  }),
  byCategory: (params: EventQuery) => apiRequest<EventListItem[]>({
    method: 'GET',
    url: '/event/of/category',
    params,
  }),
  nearby: (params: NearbyEventQuery) => apiRequest<EventListItem[]>({
    method: 'GET',
    url: '/event/nearby',
    params,
  }),
  addView: (id: ApiId) => apiRequest<number>({ method: 'PUT', url: `/event/uv/${id}` }),
  getViews: (id: ApiId) => apiRequest<number>({ method: 'GET', url: `/event/uv/${id}` }),
}

export const ticketApi = {
  listByEvent: (eventId: ApiId) => apiRequest<Ticket[]>({
    method: 'GET',
    url: `/ticket/of/event/${eventId}`,
  }),
}

export const orderApi = {
  reserve: (ticketId: ApiId, useCredits?: boolean) => apiRequest<ApiId>({
    method: 'POST',
    url: `/ticket-orders/reserve/${ticketId}`,
    params: useCredits ? { useCredits: true } : undefined,
  }),
  pay: (orderId: ApiId) => apiRequest<void>({
    method: 'POST',
    url: `/ticket-orders/pay/${orderId}`,
  }),
  cancel: (orderId: ApiId) => apiRequest<void>({
    method: 'POST',
    url: `/ticket-orders/cancel/${orderId}`,
  }),
  mine: (params: PageQuery & { status?: OrderStatus } = {}) => apiRequest<PageResult<TicketOrder>>({
    method: 'GET',
    url: '/ticket-orders/me',
    params,
  }),
}

export const reviewApi = {
  create: (payload: EventReviewCreateRequest) => apiRequest<ApiId>({
    method: 'POST',
    url: '/event-review',
    data: payload,
  }),
  remove: (id: ApiId) => apiRequest<void>({ method: 'DELETE', url: `/event-review/${id}` }),
  toggleLike: (id: ApiId) => apiRequest<void>({ method: 'PUT', url: `/event-review/like/${id}` }),
  hot: (params: PageQuery = {}) => apiRequest<EventReview[]>({
    method: 'GET',
    url: '/event-review/hot',
    params,
  }),
  byUser: (userId: ApiId, params: PageQuery = {}) => apiRequest<PageResult<EventReview>>({
    method: 'GET',
    url: `/event-review/of/user/${userId}`,
    params,
  }),
  getById: (id: ApiId) => apiRequest<EventReview>({ method: 'GET', url: `/event-review/${id}` }),
  likes: (id: ApiId) => apiRequest<User[]>({ method: 'GET', url: `/event-review/likes/${id}` }),
  comments: (id: ApiId, params: PageQuery = {}) => apiRequest<PageResult<EventReviewComment>>({
    method: 'GET',
    url: `/event-review/${id}/comments`,
    params,
  }),
  createComment: (id: ApiId, content: string) => apiRequest<ApiId>({
    method: 'POST',
    url: `/event-review/${id}/comments`,
    data: { content },
  }),
  removeComment: (id: ApiId) => apiRequest<void>({
    method: 'DELETE',
    url: `/event-review/comments/${id}`,
  }),
  followed: (params: ScrollQuery) => apiRequest<ScrollResult<EventReview>>({
    method: 'GET',
    url: '/event-review/of/follow',
    params,
  }),
}

export const followApi = {
  set: (userId: ApiId, isFollow: boolean) => apiRequest<void>({
    method: 'PUT',
    url: `/follow/${userId}/${isFollow}`,
  }),
  isFollowing: (userId: ApiId) => apiRequest<boolean>({
    method: 'GET',
    url: `/follow/or/not/${userId}`,
  }),
  followees: (userId: ApiId, params: PageQuery = {}) => apiRequest<PageResult<User>>({
    method: 'GET',
    url: `/follow/followees/${userId}`,
    params,
  }),
  fans: (userId: ApiId, params: PageQuery = {}) => apiRequest<PageResult<User>>({
    method: 'GET',
    url: `/follow/fans/${userId}`,
    params,
  }),
}

export const uploadApi = {
  image: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return apiRequest<string>({ method: 'POST', url: '/upload/image', data })
  },
  deleteImage: (name: string) => apiRequest<void>({
    method: 'DELETE',
    url: '/upload/image',
    params: { name },
  }),
}
