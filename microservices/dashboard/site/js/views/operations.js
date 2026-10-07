import { api, query } from '../api.js';
import {
  h, clear, money, amountCell, dateTime, shortDate, shortId, idCell, badge, mono, copyable, json, panel, pageHeader,
  button, table, pagedList, details, hero, dialog, form, notice, errorBox, toast, toastError, confirmDialog, showSecret,
} from '../ui.js';
import { openPayment } from './payments.js';

// ---- settlements

export function settlements(root) {
  const list = pagedList({
    load: (page, filters, size) => api('GET', '/v1/settlements' + query({ status: filters.status, page, size })),
    tabs: { name: 'status', options: ['INITIATED', 'TRANSFER_PENDING', 'PROCESSED', 'FAILED'] },
    columns: [
      { title: 'Paid out', cell: (s) => amountCell(s.netAmount) },
      { title: 'Status', cell: (s) => badge(s.status) },
      { title: 'Captured', align: 'right', cell: (s) => money(s.grossAmount) },
      { title: 'Refunds', align: 'right', cell: (s) => money(s.refundAmount) },
      { title: 'Fee', align: 'right', cell: (s) => money(s.feeAmount) },
      { title: 'GST', align: 'right', cell: (s) => money(s.gstAmount) },
      { title: 'Settlement', cell: (s) => idCell(s.id) },
      { title: 'Created', cell: (s) => shortDate(s.createdAt) },
    ],
    onRow: openSettlement,
    emptyMessage: 'No payouts yet. A merchant is settled once it is active and has captured payments.',
  });
  clear(root,
    pageHeader('Settlements', 'Payouts to the merchant\'s bank account: captured payments, minus refunds, the platform fee and GST on the fee. The nightly job runs at 23:00; the operator console can run one now.',
      button('Refresh', () => list.refresh(), '', 'refresh')),
    panel(null, null, list));
}

async function openSettlement(settlement) {
  const modal = dialog('Settlement', h('p', { class: 'loading' }, 'Loading…'), { drawer: true });
  try {
    const [s, paymentIds] = await Promise.all([
      api('GET', `/v1/settlements/${settlement.id}`), api('GET', `/v1/settlements/${settlement.id}/payments`)]);
    clear(modal.body,
      hero(money(s.netAmount), badge(s.status), `Paid to the merchant's bank account · ${dateTime(s.createdAt)}`),
      s.failureReason ? notice('bad', h('strong', {}, s.failureReason), h('div', {}, 'The payments stay captured and are paid out in a later run.')) : null,
      h('h3', {}, 'How the amount is reached'),
      h('table', { class: 'sum' }, h('tbody', {},
        h('tr', {}, h('td', {}, 'Captured payments'), h('td', { class: 'right' }, money(s.grossAmount))),
        h('tr', {}, h('td', {}, 'Refunds the bank completed'), h('td', { class: 'right' }, `− ${money(s.refundAmount)}`)),
        h('tr', {}, h('td', {}, 'Platform fee on what was kept'), h('td', { class: 'right' }, `− ${money(s.feeAmount)}`)),
        h('tr', {}, h('td', {}, 'GST on the fee'), h('td', { class: 'right' }, `− ${money(s.gstAmount)}`)),
        h('tr', { class: 'total' }, h('td', {}, 'Paid out'), h('td', { class: 'right' }, money(s.netAmount))))),
      h('h3', {}, 'Details'),
      details([
        ['Settlement ID', copyable(s.id)],
        ['Bank reference', s.bankReference ? copyable(s.bankReference) : '–'],
        ['Created', dateTime(s.createdAt)],
        ['Completed', dateTime(s.processedAt)],
      ]),
      h('h3', {}, `Payments covered (${paymentIds.length})`),
      h('div', { class: 'chips' }, paymentIds.slice(0, 60).map((id) =>
        h('button', { type: 'button', class: 'chip mono', onclick: () => openPayment(id) }, shortId(id)))),
      paymentIds.length > 60 ? h('p', { class: 'muted small' }, `and ${paymentIds.length - 60} more`) : null);
  } catch (error) {
    clear(modal.body, errorBox(error));
  }
}

// ---- webhooks

const EVENTS = 'ORDER_CREATED, ORDER_CANCELLED, ORDER_EXPIRED, PAYMENT_CREATED, PAYMENT_STATUS_CHANGED, PAYMENT_AUTHORIZATION_COMPENSATED, REFUND_CREATED, REFUND_PROCESSED, REFUND_FAILED, SETTLEMENT_PROCESSED, SETTLEMENT_FAILED';

// operations-service's own test receiver, which answers 204 to anything. It is on the gateway's port plus four,
// wherever the stack was started (the demo's --port-offset moves both).
function testReceiver(configs) {
  const known = configs.map((c) => c.targetUrl).find((url) => url.endsWith('/webhook/success'));
  return known || 'http://localhost:8084/webhook/success';
}

// The event's name exactly as the API sends it: a receiver matches on this string.
function eventName(type) {
  return h('span', { class: 'mono' }, type);
}

export function webhooks(root) {
  const endpoints = h('div', {}, h('p', { class: 'loading' }, 'Loading…'));
  let configs = [];

  async function loadEndpoints() {
    try {
      configs = await api('GET', '/v1/merchants/webhooks');
      clear(endpoints, table([
        { title: 'Endpoint URL', cell: (c) => mono(c.targetUrl) },
        { title: 'Status', cell: (c) => badge(c.enabled ? 'ENABLED' : 'PAUSED') },
        { title: 'Events', cell: (c) => (!c.eventTypes || c.eventTypes === 'ALL' ? 'All events' : c.eventTypes.split(',').length === 1 ? eventName(c.eventTypes) : `${c.eventTypes.split(',').length} events`) },
        {
          title: '', align: 'right', cell: (c) => h('div', { class: 'row-actions' },
            button(c.enabled ? 'Pause' : 'Resume', () => act(() => api('PUT', `/v1/merchants/webhooks/${c.id}`,
              { targetUrl: c.targetUrl, eventTypes: c.eventTypes, enabled: !c.enabled }), c.enabled ? 'Endpoint paused' : 'Endpoint resumed'), 'ghost small'),
            button('Roll secret', () => confirmDialog('Roll the signing secret?',
              'The new secret is effective at once; deliveries signed with the old one stop verifying.', 'Roll secret', async () => {
                const rotated = await api('POST', `/v1/merchants/webhooks/${c.id}/rotate-secret`);
                showSecret('New signing secret', [['Secret', rotated.webhookSecret]]);
              }, { danger: false }), 'ghost small'),
            button('Delete', () => confirmDialog('Delete this endpoint?', c.targetUrl, 'Delete endpoint',
              () => api('DELETE', `/v1/merchants/webhooks/${c.id}`).then(loadEndpoints)), 'ghost small')),
        },
      ], configs, null, 'No endpoints yet. Add one to be told about every change.'));
    } catch (error) {
      clear(endpoints, errorBox(error));
    }
  }

  async function act(action, message) {
    try {
      await action();
      toast(message);
      loadEndpoints();
    } catch (error) {
      toastError(error);
    }
  }

  function openNewEndpoint() {
    const modal = dialog('Add an endpoint', form([
      { name: 'targetUrl', label: 'Endpoint URL', required: true, value: testReceiver(configs), maxlength: 255,
        hint: 'The default is operations-service\'s own test receiver, which answers 204 to anything. Locally, private addresses and http are allowed.' },
      { name: 'eventTypes', label: 'Events to send', value: 'ALL', hint: `ALL, or a comma-separated list of: ${EVENTS}` },
    ], async (v) => {
      const created = await api('POST', '/v1/merchants/webhooks', { targetUrl: v.targetUrl, eventTypes: v.eventTypes || 'ALL' });
      modal.close();
      loadEndpoints();
      showSecret('Signing secret', [['Secret', created.webhookSecret]],
        h('p', { class: 'muted small' }, 'Each delivery carries X-PayFlo-Timestamp and X-PayFlo-Signature: the hex HMAC-SHA256, keyed with this secret, of "<timestamp>.<raw body>".'));
    }, { submit: 'Add endpoint' }));
  }

  const deliveries = pagedList({
    load: (page, filters, size) => api('GET', '/v1/webhook-deliveries' + query({ status: filters.status, page, size })),
    tabs: { name: 'status', options: ['DELIVERED', 'PENDING', 'FAILED', 'DEAD'] },
    columns: [
      { title: 'Event', cell: (d) => eventName(d.eventType) },
      { title: 'Status', cell: (d) => badge(d.status) },
      { title: 'Endpoint', cell: (d) => h('span', { class: 'muted' }, d.targetUrl.replace(/^https?:\/\//, '')) },
      { title: 'Response', align: 'right', cell: (d) => d.lastResponseCode ?? '–' },
      { title: 'Attempts', align: 'right', cell: (d) => d.attempts },
      { title: 'Next retry', cell: (d) => shortDate(d.nextRetryAt) },
      { title: 'Created', cell: (d) => shortDate(d.createdAt) },
    ],
    onRow: (d) => openDelivery(d.id, () => deliveries.refresh()),
    emptyMessage: 'No deliveries here.',
  });

  clear(root,
    pageHeader('Webhooks', 'Every change is announced to the merchant\'s own server as a signed, timestamped POST, retried for 24 hours and then dead-lettered.',
      button('Add endpoint', openNewEndpoint, 'primary', 'plus')),
    panel('Endpoints', null, endpoints),
    panel('Deliveries', button('Refresh', () => deliveries.refresh(), 'small', 'refresh'), deliveries));
  loadEndpoints();
}

async function openDelivery(deliveryId, onChange) {
  const modal = dialog('Webhook delivery', h('p', { class: 'loading' }, 'Loading…'), { drawer: true });
  try {
    const d = await api('GET', `/v1/webhook-deliveries/${deliveryId}`);
    clear(modal.body,
      hero(eventName(d.eventType), badge(d.status), mono(d.targetUrl)),
      d.status === 'FAILED' ? notice('wait', `Attempt ${d.attempts} failed. The next one is due ${dateTime(d.nextRetryAt)}; attempts follow 1 min, 5 min, 30 min, 2 h, 8 h and 24 h apart.`) : null,
      d.status === 'DEAD' ? notice('bad', 'Seven attempts failed. It stays here until it is sent again.') : null,
      d.status === 'PENDING' ? notice('flat', 'Queued for its next attempt.') : h('div', { class: 'form-actions start' },
        button('Send again', async () => {
          try {
            await api('POST', `/v1/webhook-deliveries/${deliveryId}/replay`);
            toast('Queued again: the same body, a fresh timestamp and signature');
            modal.close();
            onChange();
          } catch (error) {
            toastError(error);
          }
        }, 'primary', 'refresh')),
      h('h3', {}, 'Details'),
      details([
        ['Event ID', copyable(d.eventId)],
        ['Attempts', d.attempts],
        ['Last attempt', dateTime(d.lastAttemptAt)],
        ['Last response', d.lastResponseCode ? `HTTP ${d.lastResponseCode}` : '–'],
        d.lastResponseBody ? ['Response body', h('pre', { class: 'code' }, d.lastResponseBody)] : null,
        ['Delivered', dateTime(d.deliveredAt)],
        ['Created', dateTime(d.createdAt)],
      ]),
      h('h3', {}, 'Payload'), json(d.payload));
  } catch (error) {
    clear(modal.body, errorBox(error));
  }
}
