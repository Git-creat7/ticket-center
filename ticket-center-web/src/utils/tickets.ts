import type { Ticket, TicketWaitlist } from '../types/api'
import { parseBackendDateTime } from './format.ts'

export function ticketAvailability(ticket: Ticket, now = Date.now()) {
  const begin = parseBackendDateTime(ticket.beginTime).getTime()
  const end = parseBackendDateTime(ticket.endTime).getTime()
  if (ticket.status !== 1) return { label: '已下架', kind: null }
  if (!Number.isFinite(begin) || !Number.isFinite(end)) return { label: '暂不可预约', kind: null }
  if (now < begin) return { label: '未开售', kind: null }
  if (now >= end) return { label: '已结束', kind: null }
  if (ticket.hasWaitlist || ticket.stock <= 0) {
    return { label: ticket.hasWaitlist ? '候补优先' : '已售罄', kind: 'waitlist' as const }
  }
  return { label: '可预约', kind: 'reservation' as const }
}

export function waitlistStatus(entry: TicketWaitlist) {
  if (entry.orderStatus === 0) return { label: '待支付', type: 'warning' as const }
  if (entry.orderStatus === 1) return { label: '已出票', type: 'success' as const }
  if (entry.orderStatus === 2) return { label: '订单已取消', type: 'info' as const }
  if (entry.status === 0) return { label: '排队中', type: 'warning' as const }
  if (entry.status === 1) return { label: '递补中', type: 'warning' as const }
  return { label: entry.statusDesc, type: 'info' as const }
}
