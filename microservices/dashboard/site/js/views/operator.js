// The platform operator's console. It talks to /v1/admin/** with the admin key and nothing else: a merchant login
// never opens these paths, and the admin key opens no merchant path.
import { adminApi, query } from '../api.js';
import {
  h, clear, dateTime, shortDate, idCell, badge, copyable, card, panel, stat, pageHeader, pagedList, details, hero,
  dialog, form, notice, toast,
} from '../ui.js';
import { AUDIT_ACTIONS, auditColumns, openAuditEntry } from './account.js';

export function merchants(root) {
  const list = pagedList({
    load: (page, filters, size) => adminApi('GET', '/v1/admin/merchants' + query({ status: filters.status, page, size })),
    tabs: { name: 'status', options: ['ACTIVE', 'PENDING_KYC', 'SUSPENDED'] },
    columns: [
      { title: 'Business', cell: (m) => h('strong', {}, m.businessName || m.name) },
      { title: 'Status', cell: (m) => badge(m.status) },
      { title: 'Owner', cell: (m) => m.email },
      { title: 'Merchant', cell: (m) => idCell(m.id) },
      { title: 'Signed up', cell: (m) => shortDate(m.createdAt) },
    ],
    onRow: (m) => openMerchant(m, () => list.refresh()),
    emptyMessage: 'No merchants match.',
  });
  clear(root,
    pageHeader('Merchants', 'Every merchant on the platform. The operator sees no payout account, PAN or GSTIN: it doesn\'t need them to act.'),
    panel(null, null, list));
}

function openMerchant(merchant, onChange) {
  const suspended = merchant.status === 'SUSPENDED';
  const modal = dialog('Merchant', [
    hero(merchant.businessName || merchant.name, badge(merchant.status), `${merchant.name} · ${merchant.email}`),
    suspended ? notice('bad', h('strong', {}, `Suspended ${dateTime(merchant.suspendedAt)}`), h('div', {}, merchant.suspensionReason || '')) : null,
    details([
      ['Merchant ID', copyable(merchant.id)],
      ['Signed up', dateTime(merchant.createdAt)],
    ]),
    h('h3', {}, suspended ? 'Reactivate this merchant' : 'Suspend this merchant'),
    h('p', { class: 'muted' }, suspended
      ? 'Puts the merchant back to the status it had before the suspension.'
      : 'Takes effect at once: the gateway refuses the merchant\'s API keys and sessions with 403 MERCHANT_SUSPENDED, and it is left out of settlement. Payments in flight finish.'),
    form([{ name: 'reason', label: 'Reason', required: !suspended, maxlength: 255, hint: 'Recorded in the audit log.' }], async (v) => {
      await adminApi('POST', `/v1/admin/merchants/${merchant.id}/${suspended ? 'reactivate' : 'suspend'}`, v.reason ? { reason: v.reason } : {});
      toast(suspended ? 'Merchant reactivated' : 'Merchant suspended');
      modal.close();
      onChange();
    }, { submit: suspended ? 'Reactivate merchant' : 'Suspend merchant', danger: !suspended }),
  ], { drawer: true });
}

export function settlementRun(root) {
  const result = h('div', {});
  const runForm = form([
    { name: 'merchantId', label: 'Merchant ID', placeholder: 'Every active merchant',
      pattern: '[0-9a-fA-F-]{36}', hint: 'Leave empty to settle every active merchant, or paste one ID from the Merchants page. The merchant must be active and have a payout account.' },
  ], async (v) => {
    clear(result, h('p', { class: 'loading' }, 'Running…'));
    try {
      const run = await adminApi('POST', '/v1/admin/settlements/run', v.merchantId ? { merchantId: v.merchantId } : {});
      clear(result,
        h('div', { class: 'stats' },
          stat('Merchants tried', run.merchants),
          stat('Failed', run.failedMerchants),
          stat('Payouts started', run.settlementsCreated),
          stat('Took', `${((new Date(run.finishedAt) - new Date(run.startedAt)) / 1000).toFixed(1)} s`)),
        notice('good', 'The bank answers each payout a few seconds later. Each merchant sees it under Settlements, and gets a SETTLEMENT_PROCESSED or SETTLEMENT_FAILED webhook.'));
    } catch (error) {
      clear(result);
      throw error;
    }
  }, { submit: 'Run settlement now' });

  clear(root,
    pageHeader('Settlement run', 'The nightly 23:00 job, started by hand: the same code under the same lock, so the two can never overlap.'),
    h('div', { class: 'grid-2' },
      card('Run now', notice('flat', 'The run is written to the audit log before it starts. If that fails, nothing runs.'), runForm),
      h('div', {})),
    result);
}

export function platformAudit(root) {
  clear(root,
    pageHeader('Audit log', 'Sensitive actions across every merchant, including the operator\'s own.'),
    panel(null, null, pagedList({
      load: (page, filters, size) => adminApi('GET', '/v1/admin/audit-log' + query({ action: filters.action, page, size })),
      filters: [{ name: 'action', label: 'Action', options: AUDIT_ACTIONS }],
      columns: auditColumns(true),
      onRow: openAuditEntry,
      emptyMessage: 'No audit entries match.',
    })));
}
