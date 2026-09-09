import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ticketAvailability, waitlistStatus } from '../src/utils/tickets.ts'

const now = new Date('2026-09-08T10:00:00').getTime()
const ticket = { status: 1, stock: 1, beginTime: '2026-09-08 09:00:00', endTime: '2026-09-08 11:00:00' }

test('available tickets allow reservations and sold-out tickets allow waitlists', () => {
  assert.equal(ticketAvailability(ticket, now).kind, 'reservation')
  assert.equal(ticketAvailability({ ...ticket, stock: 0 }, now).kind, 'waitlist')
})

test('queued users keep priority when stock is released', () => {
  const availability = ticketAvailability({ ...ticket, hasWaitlist: true }, now)
  assert.equal(availability.kind, 'waitlist')
  assert.equal(availability.label, '候补优先')
})

test('sale boundaries apply to both reservation and waitlist entry', () => {
  assert.equal(ticketAvailability({ ...ticket, stock: 0, status: 0 }, now).kind, null)
  assert.equal(ticketAvailability({ ...ticket, stock: 0, beginTime: '2026-09-08 10:00:01' }, now).kind, null)
  assert.equal(ticketAvailability({ ...ticket, stock: 0, endTime: '2026-09-08 10:00:00' }, now).kind, null)
  assert.equal(ticketAvailability({ ...ticket, endTime: 'invalid' }, now).kind, null)
})

test('allocated waitlists show the associated order result', () => {
  assert.equal(waitlistStatus({ status: 0 }).label, '排队中')
  assert.equal(waitlistStatus({ status: 1 }).label, '递补中')
  assert.equal(waitlistStatus({ status: 2, orderStatus: 0 }).label, '待支付')
  assert.equal(waitlistStatus({ status: 2, orderStatus: 1 }).label, '已出票')
  assert.equal(waitlistStatus({ status: 2, orderStatus: 2 }).label, '订单已取消')
  assert.equal(waitlistStatus({ status: 4, statusDesc: '已失效' }).label, '已失效')
})
