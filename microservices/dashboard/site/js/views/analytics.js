import { api, query } from '../api.js';
import {
  h, clear, units, day, percent, card, panel, stat, pageHeader, button, table, form, notice, errorBox, trendChart,
  shareBars, methodLabel, icon,
} from '../ui.js';
import { quickPayment } from './orders.js';
import { openPayment, PAYMENT_COLUMNS } from './payments.js';

function methodShares(byMethod, currency) {
  if (!byMethod.length) return h('p', { class: 'muted' }, 'No captured payments in this period.');
  return shareBars(byMethod.map((m) => ({
    label: methodLabel(m.method), value: m.grossAmountUnits,
    text: `${units(m.grossAmountUnits, currency)} · ${m.capturedCount} ${m.capturedCount === 1 ? 'payment' : 'payments'}`,
  })));
}

function trendPoints(periods) {
  return periods.map((p) => ({
    label: day(p.periodStart), value: p.grossAmountUnits,
    detail: `${p.capturedCount} ${p.capturedCount === 1 ? 'payment' : 'payments'} captured${p.failedCount ? `, ${p.failedCount} failed` : ''}`,
  }));
}

/**
 * What a new merchant still has to do, in the order it matters, each step read from the API rather than remembered.
 * Shown until every step is done.
 */
function setupGuide(profile, keys, endpoints, hasPayment, takePayment) {
  const profileDone = Boolean(profile.businessName && profile.businessType && profile.contactNumber && profile.panId);
  const steps = [
    ['Complete the business profile', 'Business name and type, a contact number and a PAN.', profileDone, '#/account', 'Open account'],
    ['Add a payout account', 'Where settlements are paid. Owner only, and it asks for the password again.', Boolean(profile.settlementBank), '#/account', 'Open account'],
    ['Pass KYC', 'Simulated: a complete profile and a payout account activate the merchant at once.', profile.status === 'ACTIVE', '#/account', 'Open account'],
    ['Create an API key', 'Your backend\'s credential, sent as HTTP Basic.', keys.some((k) => k.enabled), '#/developers', 'API keys'],
    ['Add a webhook endpoint', 'Be told about every change as a signed request.', endpoints.length > 0, '#/webhooks', 'Webhooks'],
    ['Take a test payment', 'Create an order and pay it with one of the test values.', hasPayment, null, 'Create payment'],
  ];
  const done = steps.filter((s) => s[2]).length;
  if (done === steps.length) return null;
  return panel('Finish setting up', h('span', { class: 'muted' }, `${done} of ${steps.length} done`),
    h('ol', { class: 'guide' }, steps.map(([title, text, complete, href, action]) => h('li', { class: complete ? 'done' : '' },
      h('span', { class: 'guide-mark' }, complete ? icon('check', 13) : null),
      h('div', {}, h('div', { class: 'guide-title' }, title, complete ? h('span', { class: 'sr-only' }, ' (done)') : null), h('div', { class: 'muted small' }, text)),
      complete ? null : href ? h('a', { class: 'button small', href }, action) : button(action, takePayment, 'small')))));
}

export async function overview(root, ctx) {
  const today = new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' });
  const reload = () => overview(root, ctx);
  const takePayment = () => quickPayment(reload);
  const header = pageHeader('Home', today,
    button('Refresh', reload, '', 'refresh'),
    button('Create payment', takePayment, 'primary', 'plus'));
  clear(root, header, h('p', { class: 'loading' }, 'Loading…'));
  try {
    // The last two only feed the setup guide: if a login may not read them, the guide just shows those steps undone.
    const [dashboard, profile, recent, keys, endpoints] = await Promise.all([
      api('GET', '/v1/analytics/dashboard'), api('GET', '/v1/merchants/me'), api('GET', '/v1/payments?size=6'),
      api('GET', '/v1/merchants/api-keys').catch(() => []), api('GET', '/v1/merchants/webhooks').catch(() => [])]);
    const now = dashboard.todaySummary, week = dashboard.last7Days, currency = dashboard.currency;

    clear(root, header,
      profile.status === 'SUSPENDED'
        ? notice('bad', h('strong', {}, 'This merchant is suspended. '), 'The platform operator has suspended it: the gateway refuses its API keys, and it is left out of settlement.')
        : setupGuide(profile, keys, endpoints, recent.items.length > 0, takePayment),
      h('div', { class: 'grid-main even' },
        card(null,
          h('div', { class: 'headline' },
            h('div', { class: 'headline-label' }, 'Net volume today'),
            h('div', { class: 'headline-value' }, units(now.netAmountUnits, currency)),
            h('div', { class: 'muted' }, `${units(now.grossAmountUnits, currency)} captured, ${units(now.refundedAmountUnits, currency)} refunded`)),
          h('h3', {}, 'Captured volume, last 7 days'),
          trendChart(trendPoints(dashboard.daily), { name: 'Captured volume per day', currency })),
        h('div', { class: 'stats stack' },
          stat('Payments captured today', now.capturedCount, `${now.paymentsCreated} started, ${now.failedCount} failed`),
          stat('Success rate today', percent(now.successRate), 'Captured out of those that reached an outcome'),
          stat('Net volume, 7 days', units(week.netAmountUnits, currency), `${week.capturedCount} payments captured`))),
      h('div', { class: 'grid-main' },
        panel('Recent payments', h('a', { href: '#/payments' }, 'View all'),
          table(PAYMENT_COLUMNS.filter((c) => c.title !== 'Decline reason'), recent.items, (p) => openPayment(p.id, reload),
            'No payments yet. Use "Create payment" to take the first one.')),
        card('Payment methods, 7 days', methodShares(dashboard.byMethod, currency))));
  } catch (error) {
    clear(root, header, errorBox(error));
  }
}

function isoDate(date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function daysAgo(days) {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return isoDate(date);
}

// The ranges people reach for first, each with the grouping that reads well at that length.
const PRESETS = [['Last 7 days', 6, 'DAY'], ['Last 30 days', 29, 'DAY'], ['Last 90 days', 89, 'WEEK'], ['Last 12 months', 364, 'MONTH']];

export function reports(root) {
  const result = h('div', {});
  const presets = h('div', { class: 'presets' });
  let selected = 'Last 30 days';

  async function run(values) {
    result.classList.add('stale');          // the old figures stay, dimmed, until the new ones arrive
    let report;
    try {
      report = await api('GET', '/v1/analytics/report' + query(values));
    } finally {
      result.classList.remove('stale');
    }
    const t = report.totals, currency = report.currency;
    const unit = report.granularity.toLowerCase();
    clear(result,
      h('div', { class: 'stats' },
        stat('Captured', units(t.grossAmountUnits, currency), `${t.capturedCount} payments`),
        stat('Refunded', units(t.refundedAmountUnits, currency)),
        stat('Net volume', units(t.netAmountUnits, currency)),
        stat('Success rate', percent(t.successRate), `${t.paymentsCreated} started, ${t.failedCount} failed`)),
      h('div', { class: 'grid-main' },
        card(`Captured volume per ${unit}`, trendChart(trendPoints(report.periods), { name: `Captured volume per ${unit}`, currency })),
        card('Payment methods', methodShares(report.byMethod, currency))),
      panel(`By ${unit}`, null, table([
        { title: unit === 'day' ? 'Day' : `${unit.charAt(0).toUpperCase()}${unit.slice(1)} starting`, cell: (p) => day(p.periodStart) },
        { title: 'Captured', align: 'right', cell: (p) => units(p.grossAmountUnits, currency) },
        { title: 'Refunded', align: 'right', cell: (p) => units(p.refundedAmountUnits, currency) },
        { title: 'Net', align: 'right', cell: (p) => h('strong', {}, units(p.netAmountUnits, currency)) },
        { title: 'Payments', align: 'right', cell: (p) => p.capturedCount },
        { title: 'Started', align: 'right', cell: (p) => p.paymentsCreated },
        { title: 'Failed', align: 'right', cell: (p) => p.failedCount },
        { title: 'Success rate', align: 'right', cell: (p) => percent(p.successRate) },
      ], report.periods.filter((p) => p.paymentsCreated || p.capturedCount || p.refundedAmountUnits).reverse(),
      null, 'Nothing happened in this range.')));
  }

  const filter = form([
    { name: 'from', label: 'From', type: 'date', required: true, value: daysAgo(29) },
    { name: 'to', label: 'To', type: 'date', required: true, value: daysAgo(0) },
    { name: 'granularity', label: 'Group by', required: true, options: [['DAY', 'Day'], ['WEEK', 'Week'], ['MONTH', 'Month']], value: 'DAY' },
  ], (values) => { selected = null; drawPresets(); return run(values); }, { submit: 'Run report' });
  filter.classList.add('inline');

  function drawPresets() {
    clear(presets, PRESETS.map(([label, days, granularity]) => h('button', {
      type: 'button', class: label === selected ? 'chip active' : 'chip', 'aria-pressed': String(label === selected),
      onclick: () => {
        selected = label;
        filter.inputs.from.value = daysAgo(days);
        filter.inputs.to.value = daysAgo(0);
        filter.inputs.granularity.value = granularity;
        drawPresets();
        run({ from: daysAgo(days), to: daysAgo(0), granularity }).catch((error) => clear(result, errorBox(error)));
      },
    }, label)));
  }

  drawPresets();
  clear(root,
    pageHeader('Reports', 'Any range up to 366 days, grouped by day, week or month. The table lists only the periods in which something happened.'),
    card(null, presets, filter), result);
  run({ from: daysAgo(29), to: daysAgo(0), granularity: 'DAY' }).catch((error) => clear(result, errorBox(error)));
}
