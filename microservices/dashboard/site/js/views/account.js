import { api, query, currentSession } from '../api.js';
import {
  h, clear, dateTime, shortDate, idCell, badge, mono, json, card, panel, pageHeader, button, table, pagedList, details,
  dialog, form, notice, errorBox, toast, toastError, confirmDialog, showSecret, statusLabel,
} from '../ui.js';
import { testValuesCard } from './orders.js';

const BUSINESS_TYPES = ['PRIVATE_LIMITED', 'PUBLIC_LIMITED', 'PROPRIETORSHIP', 'PARTNERSHIP', 'LLP', 'TRUST'].map((t) => [t, statusLabel(t)]);

// ---- developers: API keys and how to call the API

export function developers(root) {
  const keys = h('div', {}, h('p', { class: 'loading' }, 'Loading…'));

  async function load() {
    try {
      const list = await api('GET', '/v1/merchants/api-keys');
      clear(keys, table([
        { title: 'Key ID', cell: (k) => mono(k.keyId) },
        { title: 'Status', cell: (k) => badge(k.enabled ? 'ENABLED' : 'REVOKED') },
        { title: 'Mode', cell: (k) => statusLabel(k.environment) },
        { title: 'Created', cell: (k) => shortDate(k.createdAt) },
        {
          title: '', align: 'right', cell: (k) => (k.enabled ? h('div', { class: 'row-actions' },
            button('Roll secret', () => openRotate(k), 'ghost small'),
            button('Revoke', () => confirmDialog('Revoke this key?', `${k.keyId} stops working at once.`, 'Revoke key',
              () => api('DELETE', `/v1/merchants/api-keys/${k.id}`).then(load)), 'ghost small')) : null),
        },
      ], list, null, 'No API keys yet. Create one to call the API from your backend.'));
    } catch (error) {
      clear(keys, errorBox(error));
    }
  }

  function showKey(title, key) {
    showSecret(title, [['Key ID', key.keyId], ['Key secret', key.keySecret]],
      [h('h3', {}, 'Use it as HTTP Basic from your backend'),
        h('pre', { class: 'code' }, `curl -u '${key.keyId}:<key secret>' ${location.origin}/v1/orders \\\n  -H 'Content-Type: application/json' -H 'X-Idempotency-Key: order-1001' \\\n  -d '{"amount":{"amountUnits":49900,"currency":"INR"},"receipt":"order-1001"}'`)]);
  }

  function openCreate() {
    const modal = dialog('Create an API key', form([
      { name: 'environment', label: 'Mode', required: true, options: [['TEST', 'Test'], ['LIVE', 'Live']], value: 'TEST',
        hint: 'Both behave the same today: every payment goes to the simulated acquirer.' },
    ], async (v) => {
      const created = await api('POST', '/v1/merchants/api-keys', { environment: v.environment });
      modal.close();
      load();
      showKey('API key created', created);
    }, { submit: 'Create key' }));
  }

  function openRotate(key) {
    const modal = dialog('Roll the key secret', form([
      { name: 'gracePeriodHours', label: 'Keep the old secret working for (hours)', type: 'number', min: '0', max: '24', value: '24', required: true,
        hint: '0 after a leak: the old secret stops at once. Up to 24 so an integration can switch without downtime.' },
    ], async (v) => {
      const rotated = await api('POST', `/v1/merchants/api-keys/${key.id}/rotate`, { gracePeriodHours: Number(v.gracePeriodHours) });
      modal.close();
      showKey('New key secret', rotated);
    }, { submit: 'Roll secret' }));
  }

  clear(root,
    pageHeader('API keys', 'A merchant backend\'s credential, sent as HTTP Basic. This dashboard uses a login (a JWT) instead; the gateway verifies either one.',
      h('a', { class: 'button', href: '/docs.html', target: '_blank', rel: 'noopener' }, 'API reference'),
      button('Create key', openCreate, 'primary', 'plus')),
    panel('Standard keys', null, keys),
    testValuesCard());
  load();
}

// ---- account: profile, payout account, KYC, team, password

export async function account(root) {
  const session = currentSession();
  const header = pageHeader('Account', `Signed in as ${session.email} (${statusLabel(session.role || '')}).`);
  clear(root, header, h('p', { class: 'loading' }, 'Loading…'));

  let profile;
  try {
    profile = await api('GET', '/v1/merchants/me');
  } catch (error) {
    clear(root, header, errorBox(error));
    return;
  }
  const reload = () => account(root);
  const bank = profile.settlementBank;

  const profileForm = form([
    { name: 'name', label: 'Name', value: profile.name, maxlength: 50 },
    { name: 'businessName', label: 'Business name', value: profile.businessName, maxlength: 50 },
    { name: 'businessType', label: 'Business type', options: [['', 'Not set'], ...BUSINESS_TYPES], value: profile.businessType || '' },
    { name: 'contactNumber', label: 'Contact number', value: profile.contactNumber, placeholder: '+91 98765 43210' },
    { name: 'websiteUrl', label: 'Website', value: profile.websiteUrl, placeholder: 'https://…' },
    { name: 'panId', label: 'PAN', placeholder: profile.panId ? `${profile.panId} (on file)` : 'ABCDE1234F', hint: 'Shown masked once saved. AAAAA0000A is the KYC rejection test value.' },
    { name: 'gstId', label: 'GSTIN', value: profile.gstId, placeholder: '27ABCDE1234F1Z5' },
  ], async (v) => {
    await api('PUT', '/v1/merchants/me', Object.fromEntries(Object.entries(v).filter(([, value]) => value !== '')));
    toast('Profile saved');
    reload();
  }, { submit: 'Save profile' });

  const bankForm = form([
    { name: 'accountHolderName', label: 'Account holder', required: true, value: bank && bank.accountHolderName, maxlength: 200 },
    { name: 'accountNumber', label: 'Account number', required: true, inputmode: 'numeric', pattern: '[0-9]{9,18}', placeholder: bank ? `${bank.accountNumber} (on file)` : '9 to 18 digits',
      hint: bank ? 'Shown masked. Type the full number to change it.' : null },
    { name: 'ifsc', label: 'IFSC', required: true, value: bank && bank.ifsc, placeholder: 'HDFC0001234' },
    { name: 'currentPassword', label: 'Your password, again', type: 'password', required: true, autocomplete: 'current-password',
      hint: 'Changing where the money goes needs more than a valid login. Owner only.' },
  ], async (v) => {
    await api('PUT', '/v1/merchants/me/settlement-bank', v);
    toast('Payout account saved');
    reload();
  }, { submit: 'Save payout account' });

  const kyc = h('div', {},
    details([['Status', badge(profile.status)]]),
    profile.status === 'ACTIVE'
      ? h('p', { class: 'muted' }, 'Verified. This merchant is included in settlement runs.')
      : [h('p', { class: 'muted' }, 'KYC is simulated: with a complete profile and a payout account the merchant is activated at once. No document is checked.'),
        button('Submit for KYC', async () => {
          try {
            const result = await api('POST', '/v1/merchants/me/kyc');
            toast(`KYC done: the merchant is ${statusLabel(result.status).toLowerCase()}`);
            reload();
          } catch (error) {
            toastError(error);
          }
        }, 'primary')]);
  kyc.classList.add('kyc');

  const passwordForm = form([
    { name: 'currentPassword', label: 'Current password', type: 'password', required: true, autocomplete: 'current-password' },
    { name: 'newPassword', label: 'New password', type: 'password', required: true, minlength: 8, autocomplete: 'new-password', hint: 'At least 8 characters.' },
  ], async (v) => {
    await api('POST', '/v1/auth/password', v);
    toast('Password changed. Every session of this user has ended: sign in again.');
    await api('GET', '/v1/merchants/me').catch(() => {});          // the old token is now refused, which returns to the login page
  }, { submit: 'Change password' });

  clear(root, header,
    h('div', { class: 'grid-2' },
      card('Business profile', profileForm),
      h('div', {}, card('Verification', kyc), card('Payout account', bankForm))),
    team(),
    h('div', { class: 'grid-2' }, card('Password', passwordForm), h('div', {})));
}

function team() {
  const list = h('div', {}, h('p', { class: 'loading' }, 'Loading…'));
  const roleOptions = [['ADMIN', 'Admin: everything but the payout account, KYC and users'], ['TEAM', 'Team: read only']];

  async function load() {
    try {
      const users = await api('GET', '/v1/merchants/users');
      clear(list, table([
        { title: 'Email', cell: (u) => u.email },
        { title: 'Role', cell: (u) => statusLabel(u.role) },
        { title: 'Added', cell: (u) => shortDate(u.createdAt) },
        {
          title: '', align: 'right', cell: (u) => (u.role === 'OWNER' ? null : h('div', { class: 'row-actions' },
            button(u.role === 'ADMIN' ? 'Make team' : 'Make admin', async () => {
              try {
                await api('PUT', `/v1/merchants/users/${u.id}/role`, { role: u.role === 'ADMIN' ? 'TEAM' : 'ADMIN' });
                toast('Role changed. Their sessions have ended.');
                load();
              } catch (error) {
                toastError(error);
              }
            }, 'ghost small'),
            button('Remove', () => confirmDialog('Remove this user?', `${u.email} is signed out at once.`, 'Remove user',
              () => api('DELETE', `/v1/merchants/users/${u.id}`).then(load)), 'ghost small'))),
        },
      ], users));
    } catch (error) {
      clear(list, errorBox(error));
    }
  }

  function openAdd() {
    const modal = dialog('Add a team member', form([
      { name: 'email', label: 'Email', type: 'email', required: true },
      { name: 'password', label: 'Password', type: 'password', required: true, minlength: 8, autocomplete: 'new-password', hint: 'At least 8 characters.' },
      { name: 'role', label: 'Role', required: true, options: roleOptions, value: 'TEAM' },
    ], async (v) => {
      await api('POST', '/v1/merchants/users', v);
      modal.close();
      toast('User added');
      load();
    }, { submit: 'Add user' }));
  }

  load();
  return panel('Team', button('Add member', openAdd, 'small', 'plus'),
    h('p', { class: 'muted' }, 'The owner manages users. A team login is read-only: the gateway refuses anything but a GET with 403 ROLE_FORBIDDEN.'),
    list);
}

// ---- audit log

export const AUDIT_ACTIONS = ['SETTLEMENT_BANK_CHANGED', 'KYC_VERIFIED', 'PASSWORD_CHANGED', 'API_KEY_CREATED', 'API_KEY_REVOKED',
  'API_KEY_ROTATED', 'WEBHOOK_CONFIG_CREATED', 'WEBHOOK_CONFIG_UPDATED', 'WEBHOOK_CONFIG_DELETED', 'WEBHOOK_SECRET_ROTATED',
  'USER_ADDED', 'USER_ROLE_CHANGED', 'USER_REMOVED', 'MERCHANT_SUSPENDED', 'MERCHANT_REACTIVATED', 'SETTLEMENT_RUN_TRIGGERED'];

function summary(data) {
  const entries = Object.entries(data || {});
  if (!entries.length) return h('span', { class: 'muted' }, '–');
  return h('span', { class: 'muted' }, entries.map(([key, value]) => `${key}: ${value}`).join(' · '));
}

export function auditColumns(withMerchant) {
  return [
    { title: 'Action', cell: (e) => h('strong', {}, statusLabel(e.action)) },
    { title: 'By', cell: (e) => [e.actor, h('span', { class: 'muted' }, ` · ${statusLabel(e.actorType)}`)] },
    withMerchant ? { title: 'Merchant', cell: (e) => idCell(e.merchantId) } : null,
    { title: 'Details', cell: (e) => summary(e.details) },
    { title: 'From', cell: (e) => (e.clientIp ? mono(e.clientIp) : '–') },
    { title: 'When', cell: (e) => shortDate(e.occurredAt) },
  ].filter(Boolean);
}

export function openAuditEntry(entry) {
  dialog('Audit entry', [
    h('div', { class: 'hero' }, h('div', { class: 'hero-line' }, h('span', { class: 'hero-value' }, statusLabel(entry.action))),
      h('p', { class: 'muted' }, `${entry.actor} · ${dateTime(entry.occurredAt)}`)),
    details([
      ['Action', mono(entry.action)],
      ['Actor', `${entry.actor} (${statusLabel(entry.actorType)})`],
      ['Merchant', mono(entry.merchantId)],
      ['Target', entry.targetType ? `${entry.targetType} ${entry.targetId || ''}` : '–'],
      ['Client address', entry.clientIp ? mono(entry.clientIp) : '–'],
      ['When', dateTime(entry.occurredAt)],
    ]),
    h('h3', {}, 'Details'), json(entry.details || {})], { drawer: true });
}

export function audit(root) {
  clear(root,
    pageHeader('Audit log', 'Sensitive actions on this account, written in the same transaction as the change. The table is append-only and holds no secret.'),
    notice('flat', 'Owner and admin logins only: it names the team\'s emails and what each did.'),
    panel(null, null, pagedList({
      load: (page, filters, size) => api('GET', '/v1/merchants/audit-log' + query({ action: filters.action, page, size })),
      filters: [{ name: 'action', label: 'Action', options: AUDIT_ACTIONS }],
      columns: auditColumns(false),
      onRow: openAuditEntry,
      emptyMessage: 'No audit entries match.',
    })));
}
