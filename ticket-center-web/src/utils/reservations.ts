import type { ApiId, TicketReservation } from '../types/api'

export interface ReservationRequest {
  requestId: string
  useCredits: boolean
  kind?: 'reservation' | 'waitlist'
}

function requestKey(userId: ApiId, ticketId: ApiId): string {
  return `ticket-center-reservation:${userId}:${ticketId}`
}

export function readReservationRequest(userId: ApiId, ticketId: ApiId): ReservationRequest | null {
  const saved = sessionStorage.getItem(requestKey(userId, ticketId))
  if (!saved) return null
  try {
    const value = JSON.parse(saved) as Partial<ReservationRequest>
    if (typeof value.requestId === 'string' && typeof value.useCredits === 'boolean'
      && (value.kind === undefined || value.kind === 'reservation' || value.kind === 'waitlist')) {
      return { requestId: value.requestId, useCredits: value.useCredits,
        ...(value.kind ? { kind: value.kind } : {}) }
    }
  } catch {
    // 忽略旧的或损坏的本地记录。
  }
  return null
}

export function saveReservationRequest(userId: ApiId, ticketId: ApiId, request: ReservationRequest): void {
  sessionStorage.setItem(requestKey(userId, ticketId), JSON.stringify(request))
}

export function clearReservationRequest(userId: ApiId, ticketId: ApiId): void {
  sessionStorage.removeItem(requestKey(userId, ticketId))
}

export function reservationStatus(reservation: TicketReservation) {
  if (reservation.status === 0) return { label: '处理中', type: 'warning' as const }
  if (reservation.status === 2) return { label: '预约失败', type: 'danger' as const }
  if (reservation.status === 3) return { label: '已取消', type: 'info' as const }
  if (reservation.orderStatus === 0) return { label: '待支付', type: 'warning' as const }
  if (reservation.orderStatus === 1) return { label: '已出票', type: 'success' as const }
  return { label: reservation.statusDesc, type: 'success' as const }
}
