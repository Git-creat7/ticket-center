import type { BackendDateTime } from '../types/api'

const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: 'short',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
const dateTimeFormatterWithYear = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

/** Parses Spring's local datetime without treating it as UTC. */
export function parseBackendDateTime(value: BackendDateTime): Date {
  return new Date(value.replace(' ', 'T'))
}

export function formatDateTime(value: BackendDateTime | Date): string {
  const date = value instanceof Date ? value : parseBackendDateTime(value)
  if (Number.isNaN(date.getTime())) return '--'
  const formatter = date.getFullYear() === new Date().getFullYear()
    ? dateTimeFormatter
    : dateTimeFormatterWithYear
  return formatter.format(date)
}

export function toBackendDateTime(value: Date): BackendDateTime {
  const pad = (part: number) => String(part).padStart(2, '0')
  return [
    value.getFullYear(),
    '-',
    pad(value.getMonth() + 1),
    '-',
    pad(value.getDate()),
    ' ',
    pad(value.getHours()),
    ':',
    pad(value.getMinutes()),
    ':',
    pad(value.getSeconds()),
  ].join('')
}

/** Backend prices are integer cents, not yuan. */
export function formatPrice(cents: number): string {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: cents % 100 === 0 ? 0 : 2,
  }).format(cents / 100)
}

export function formatDistance(kilometers: number | null | undefined): string {
  if (kilometers == null || !Number.isFinite(kilometers)) return ''
  if (kilometers < 1) return `${Math.max(0, Math.round(kilometers * 1000))} m`
  return `${kilometers.toFixed(kilometers < 10 ? 1 : 0)} km`
}

export function formatCount(value: number): string {
  if (value < 10_000) return String(value)
  return `${(value / 10_000).toFixed(value < 100_000 ? 1 : 0)}万`
}
