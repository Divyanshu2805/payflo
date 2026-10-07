import { api, query, newIdempotencyKey } from '../api.js';
import {
  h, clear, money, units, amountCell, dateTime, shortDate, idCell, badge, mono, copyable, json, panel, pageHeader, button,
  table, pagedList, details, hero, timeline, notice, dialog, form, errorBox, toast, toastError, methodLabel, sleep,
} from '../ui.js';
// orders.js imports this file too. That is fine for functions, which exist before either module's body runs;
// don't import a const from it here.
import { openOrder } from './orders.js';

// The statuses a merchant filters by. The ones in passing (authorizing, capturing) are under "All".
const PAYMENT_TABS = ['CAPTURED', 'AUTHORIZED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'SETTLED'];

/** "UPI · buyer@okaxis": the method and the one detail that identifies how it was paid. A card shows no number. */
export function methodCell(payment) {
  const d = payment.methodDetails || {};
  const how = payment.method === 'UPI' ? d.vpa : payment.method === 'NETBANKING' ? d.bank : payment.method === 'WALLET' ? d.wallet : null;
  return h('span', { class: 'nowrap' }, methodLabel(payment.method), how ? h('span', { class: 'muted' }, ` · ${how}`) : null);
}

export const PAYMENT_COLUMNS = [
  { title: 'Amount', cell: (p) => amountCell(p.amount) },
  { title: 'Status', cell: (p) => badge(p.status) },
  { title: 'Method', cell: methodCell },
  { title: 'Decline reason', cell: (p) => (p.errorCode ? mono(p.errorCode) : h('span', { class: 'muted' }, '–')) },
  { title: 'Payment', cell: (p) => idCell(p.id) },
  { title: 'Created', cell: (p) => shortDate(p.createdAt) },
];

export function payments(root) {
  const list = pagedList({
    load: (page, filters, size) => api('GET', '/v1/payments' + query({ status: filters.status, page, size })),
    tabs: { name: 'status', options: PAYMENT_TABS },
    columns: PAYMENT_COLUMNS,
    onRow: (p) => openPayment(p.id, () => list.refresh()),
    emptyMessage: 'No payments here. Create an order and pay it to see one.',
  });
  clear(root,
    pageHeader('Payments', 'Every attempt to pay an order. A status changes only through the validated state machine in payment-service.',
      button('Refresh', () => list.refresh(), '', 'refresh')),
    panel(null, null, list));
}

// What is known to have happened to a payment, from its own fields and its refunds. The API has no transition log
// to read, so a step with no timestamp of its own (a failure, a payout) is listed without one rather than guessed.
function paymentTimeline(payment, refunds) {
  const events = [{ title: `Payment started by ${methodLabel(payment.method)}`, time: payment.createdAt, tone: 'info' }];
  if (payment.capturedAt) events.push({ title: 'Captured', detail: 'The order is paid.', time: payment.capturedAt, tone: 'good' });
  for (const refund of refunds) {
    events.push({ title: `Refund of ${money(refund.amount)} requested`, time: refund.createdAt, tone: 'wait' });
    if (refund.status === 'PROCESSED') events.push({ title: `Refund of ${money(refund.amount)} completed by the bank`, time: refund.processedAt, tone: 'good' });
    if (refund.status === 'FAILED') events.push({ title: `Refund of ${money(refund.amount)} declined`, detail: refund.errorCode, time: refund.processedAt, tone: 'bad' });
  }
  events.sort((a, b) => String(a.time || '').localeCompare(String(b.time || '')));
  if (payment.status === 'FAILED' || payment.status === 'AUTH_EXPIRED') events.push({ title: payment.status === 'FAILED' ? 'Failed' : 'Authorization expired', detail: payment.errorCode, tone: 'bad' });
  if (payment.status === 'AUTHORIZED' && payment.errorCode) events.push({ title: 'The bank refused the capture', detail: 'The authorization is still held. Capture it again.', tone: 'wait' });
  if (payment.status === 'AUTHORIZING' || payment.status === 'CAPTURING') events.push({ title: 'Waiting for the bank', tone: 'wait' });
  if (payment.status === 'SETTLED') events.push({ title: 'Paid out to the merchant', detail: 'Included in a settlement.', tone: 'good' });
  return timeline(events);
}

export async function openPayment(paymentId, onChange = () => {}) {
  const modal = dialog('Payment', h('p', { class: 'loading' }, 'Loading…'), { drawer: true });

  async function load() {
    try {
      const [payment, refunds] = await Promise.all([
        api('GET', `/v1/payments/${paymentId}`), api('GET', `/v1/payments/${paymentId}/refunds`)]);
      const refunded = refunds.filter((r) => r.status !== 'FAILED').reduce((sum, r) => sum + r.amount.amountUnits, 0);
      const left = payment.amount.amountUnits - refunded;
      const refundable = ['CAPTURED', 'PARTIALLY_REFUNDED'].includes(payment.status) && left > 0;

      clear(modal.body,
        hero(money(payment.amount), badge(payment.status), [methodCell(payment), ' · ', dateTime(payment.createdAt)]),
        payment.errorCode ? notice(payment.status === 'AUTHORIZED' ? 'wait' : 'bad', h('strong', {}, payment.errorCode), h('div', {}, payment.errorDescription || '')) : null,
        payment.status === 'AUTHORIZED' ? h('div', { class: 'form-actions start' },
          button('Capture payment', async () => {
            try {
              const result = await api('POST', `/v1/payments/${paymentId}/capture`);
              toast(result.status === 'CAPTURED' ? 'Payment captured' : `Capture refused: ${result.errorCode}`, result.status === 'CAPTURED' ? 'good' : 'bad');
              load();
              onChange();
            } catch (error) {
              toastError(error);
            }
          }, 'primary')) : null,
        h('h3', {}, 'Timeline'),
        paymentTimeline(payment, refunds),
        h('h3', {}, 'Details'),
        details([
          ['Payment ID', copyable(payment.id)],
          ['Order', [copyable(payment.orderId), ' ', button('View order', () => openOrder(payment.orderId, () => { load(); onChange(); }), 'ghost small')]],
          ['Amount', money(payment.amount)],
          refunded ? ['Refunded', `${units(refunded, payment.amount.currency)} of ${money(payment.amount)}`] : null,
          ['Method', methodCell(payment)],
          ['Created', dateTime(payment.createdAt)],
          ['Captured', dateTime(payment.capturedAt)],
          payment.methodDetails ? ['Method details', json(payment.methodDetails)] : null,
        ]),
        refunds.length ? [h('h3', {}, 'Refunds'), table([
          { title: 'Amount', cell: (r) => amountCell(r.amount) },
          { title: 'Status', cell: (r) => badge(r.status) },
          { title: 'Reason', cell: (r) => (r.errorCode ? mono(r.errorCode) : (r.notes && r.notes.reason) || '–') },
          { title: 'Requested', cell: (r) => shortDate(r.createdAt) },
        ], refunds)] : null,
        refundable ? refundForm(payment, left) : payment.status === 'SETTLED'
          ? notice('flat', 'This payment has been paid out to the merchant, so it can no longer be refunded.') : null);
    } catch (error) {
      clear(modal.body, errorBox(error));
    }
  }

  function refundForm(payment, left) {
    const idempotencyKey = newIdempotencyKey();
    return h('div', {}, h('h3', {}, 'Refund this payment'), form([
      { name: 'amount', label: 'Amount (₹)', type: 'number', min: '0.01', step: '0.01', inputmode: 'decimal',
        placeholder: `Everything that is left: ${units(left, payment.amount.currency)}`, hint: `Up to ${units(left, payment.amount.currency)}. Leave empty to refund all of it.` },
      { name: 'reason', label: 'Reason', maxlength: 200 },
    ], async (v) => {
      const body = {};
      if (v.amount) body.amountUnits = Math.round(parseFloat(v.amount) * 100);
      if (v.reason) body.notes = { reason: v.reason };
      const refund = await api('POST', `/v1/payments/${paymentId}/refunds`, body, { idempotencyKey });
      toast(`Refund of ${money(refund.amount)} requested. The bank answers in a few seconds.`);
      await load();
      onChange();
      // The simulated bank answers after about three seconds: show its answer without a manual refresh.
      for (let i = 0; i < 6 && modal.body.isConnected; i++) {
        await sleep(1500);
        const latest = await api('GET', `/v1/refunds/${refund.id}`);
        if (latest.status !== 'PENDING') {
          toast(latest.status === 'PROCESSED' ? 'Refund completed by the bank' : `Refund declined: ${latest.errorCode}`, latest.status === 'PROCESSED' ? 'good' : 'bad');
          load();
          onChange();
          break;
        }
      }
    }, { submit: 'Refund' }));
  }

  load();
}

export function refunds(root) {
  const list = pagedList({
    load: (page, filters, size) => api('GET', '/v1/refunds' + query({ status: filters.status, page, size })),
    tabs: { name: 'status', options: ['PENDING', 'PROCESSED', 'FAILED'] },
    columns: [
      { title: 'Amount', cell: (r) => amountCell(r.amount) },
      { title: 'Status', cell: (r) => badge(r.status) },
      { title: 'Reason', cell: (r) => (r.errorCode ? mono(r.errorCode) : (r.notes && r.notes.reason) || h('span', { class: 'muted' }, '–')) },
      { title: 'Payment', cell: (r) => idCell(r.paymentId) },
      { title: 'Requested', cell: (r) => shortDate(r.createdAt) },
      { title: 'Completed', cell: (r) => shortDate(r.processedAt) },
    ],
    onRow: (r) => openPayment(r.paymentId, () => list.refresh()),
    emptyMessage: 'No refunds yet. Open a captured payment to refund it.',
  });
  clear(root,
    pageHeader('Refunds', 'Money given back on a captured payment. A refund starts as pending, and the simulated bank approves about 95% of them.',
      button('Refresh', () => list.refresh(), '', 'refresh')),
    panel(null, null, list));
}
