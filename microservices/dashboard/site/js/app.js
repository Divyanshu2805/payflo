import {
  api, currentSession, isAdmin, login, signup, logout, adminLogin, adminLogout, onSessionEnded, apiCalls, onApiCall,
} from './api.js';
import { h, clear, form, toast, toastError, icon, logoMark, statusLabel } from './ui.js';
import { overview, reports } from './views/analytics.js';
import { orders, openOrder } from './views/orders.js';
import { payments, refunds, openPayment } from './views/payments.js';
import { settlements, webhooks } from './views/operations.js';
import { developers, account, audit } from './views/account.js';
import { merchants, settlementRun, platformAudit } from './views/operator.js';

const app = document.getElementById('app');

// [group title, [[route, label, icon, view], ...]]
const MERCHANT_NAV = [
  [null, [['overview', 'Home', 'home', overview], ['payments', 'Payments', 'payments', payments], ['orders', 'Orders', 'orders', orders],
    ['refunds', 'Refunds', 'refunds', refunds], ['settlements', 'Settlements', 'settlements', settlements], ['reports', 'Reports', 'reports', reports]]],
  ['Developers', [['webhooks', 'Webhooks', 'webhooks', webhooks], ['developers', 'API keys', 'developers', developers]]],
  ['Settings', [['account', 'Account', 'account', account], ['audit', 'Audit log', 'audit', audit]]],
];
const OPERATOR_NAV = [
  ['Platform', [['merchants', 'Merchants', 'merchants', merchants], ['settlement-run', 'Settlement run', 'run', settlementRun],
    ['platform-audit', 'Audit log', 'audit', platformAudit]]],
];

function themeToggle() {
  const control = h('button', { class: 'icon-button', type: 'button' });
  const draw = () => {
    const dark = document.documentElement.dataset.theme === 'dark';
    const label = dark ? 'Switch to the light theme' : 'Switch to the dark theme';
    clear(control, icon(dark ? 'sun' : 'moon', 17));
    control.setAttribute('aria-label', label);
    control.title = label;
  };
  control.addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem('payflo.theme', next);
    } catch {
      // Not remembered in a private window; the theme still changes for this visit.
    }
    draw();
  });
  draw();
  return control;
}

// ---- the login page

function loginPage() {
  let tab = 'login';
  const box = h('div', { class: 'login-box' });
  const TABS = [['login', 'Sign in'], ['signup', 'Create account'], ['operator', 'Operator']];

  function draw() {
    let title, lead, content;
    if (tab === 'login') {
      title = 'Sign in to PayFlo';
      lead = 'Use a merchant\'s dashboard login.';
      content = [form([
        { name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'username' },
        { name: 'password', label: 'Password', type: 'password', required: true, autocomplete: 'current-password' },
      ], async (v) => { await login(v.email, v.password); start(); }, { submit: 'Sign in', block: true }),
      h('p', { class: 'muted small' }, 'Started with demo/demo.py? It printed the demo merchant\'s login.')];
    } else if (tab === 'signup') {
      title = 'Create your account';
      lead = 'A new merchant starts as "Pending KYC" with one owner login.';
      content = [form([
        { name: 'name', label: 'Your name', required: true, maxlength: 50 },
        { name: 'businessName', label: 'Business name', maxlength: 50 },
        { name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'username' },
        { name: 'password', label: 'Password', type: 'password', required: true, minlength: 8, autocomplete: 'new-password', hint: 'At least 8 characters.' },
      ], async (v) => {
        await signup({ name: v.name, email: v.email, password: v.password, businessName: v.businessName || undefined });
        // Signup answers the same for an email that is already registered, so the login is what tells.
        await login(v.email, v.password);
        start();
      }, { submit: 'Create account', block: true }),
      h('p', { class: 'muted small' }, 'Signup never reveals whether an email is already registered.')];
    } else {
      title = 'Operator console';
      lead = 'Suspend merchants, run settlements and read every audit entry. No merchant login opens these endpoints.';
      content = [form([
        { name: 'key', label: 'Admin key', type: 'password', required: true, autocomplete: 'off',
          hint: 'The gateway\'s ADMIN_API_KEY, sent as X-Admin-Key. Its development default is in docs/api/admin.md.' },
      ], async (v) => { await adminLogin(v.key); start(); }, { submit: 'Open the console', block: true })];
    }
    clear(box, h('h1', {}, title), h('p', {}, lead),
      h('div', { class: 'segmented', role: 'tablist' }, TABS.map(([name, label]) => h('button', {
        type: 'button', role: 'tab', class: name === tab ? 'active' : '', 'aria-selected': String(name === tab),
        onclick: () => { tab = name; draw(); },
      }, label))),
      content);
  }

  draw();
  // The figures are the measured ones from docs/load-testing/results.md and docs/reliability.
  const facts = [['1,272 req/s', 'On one laptop, p99 171 ms, 0 errors'], ['99.85%', 'Of webhooks delivered within 30 s'],
    ['0 lost', 'Payments across 7 crash and outage tests'], ['3.0×', 'Throughput on 3 Kubernetes replicas']];
  const sample = [['₹1,299.00', 'good', 'Captured', 'Card'], ['₹499.00', 'good', 'Captured', 'UPI · buyer@okaxis'],
    ['₹2,500.00', 'bad', 'Failed', 'Net banking · HDFC'], ['₹750.00', 'wait', 'Authorized', 'UPI · capture refused, retry']];
  clear(app, h('main', { class: 'login' },
    h('section', { class: 'login-main' },
      h('div', { class: 'brand' }, logoMark(28), 'PayFlo'),
      box,
      h('div', { class: 'login-foot' },
        h('a', { href: '/docs.html', target: '_blank', rel: 'noopener' }, 'API reference'),
        h('span', {}, 'The bank is simulated: test data only'),
        themeToggle())),
    h('section', { class: 'login-aside' },
      h('h2', {}, 'The part behind the Pay now button.'),
      h('p', {}, 'Orders, payments, a card vault, signed webhooks and nightly payouts, as four services behind a gateway.'),
      h('div', { class: 'sample', 'aria-hidden': 'true' },
        h('div', { class: 'sample-head' }, 'Payments', h('span', {}, 'Sample')),
        sample.map(([amount, tone, status, how]) => h('div', { class: 'sample-row' },
          h('strong', {}, amount), h('span', {}, h('span', { class: `badge ${tone}` }, status)), h('span', {}, how)))),
      h('dl', { class: 'facts' }, facts.map(([figure, label]) => h('div', {}, h('dt', {}, figure), h('dd', {}, label)))))));
}

// ---- the shell: navigation, a top bar, the current view, and the list of API requests

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The top bar's search: a pasted payment, order or refund id opens it. */
function jumpBox() {
  const input = h('input', { type: 'search', placeholder: 'Open a payment, order or refund by ID', 'aria-label': 'Open a payment, order or refund by ID', autocomplete: 'off', spellcheck: 'false' });
  input.addEventListener('keydown', async (event) => {
    if (event.key !== 'Enter') return;
    const id = input.value.trim();
    if (!UUID.test(id)) {
      toast('Paste a full ID: the 36-character one from a detail view or an API response.', 'wait');
      return;
    }
    const lookups = [[`/v1/payments/${id}`, () => openPayment(id)], [`/v1/orders/${id}`, () => openOrder(id)],
      [`/v1/refunds/${id}`, (refund) => openPayment(refund.paymentId)]];
    for (const [path, open] of lookups) {
      try {
        const found = await api('GET', path);
        input.value = '';
        open(found);
        return;
      } catch (error) {
        if (error.status !== 404) return toastError(error);
      }
    }
    toast('No payment, order or refund of yours has that ID.', 'bad');
  });
  // "/" puts the cursor in the box from anywhere on the page, unless something is already being typed into.
  document.onkeydown = (event) => {
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
    if (event.key !== '/' || typing || event.ctrlKey || event.metaKey || event.altKey || document.querySelector('dialog[open]')) return;
    event.preventDefault();
    input.focus();
  };
  return h('div', { class: 'search' }, icon('search', 15), input, h('kbd', { 'aria-hidden': 'true' }, '/'));
}

function shell(nav, { name, sub, identity, search, readOnly, onLogout }) {
  const view = h('main', { class: 'view', id: 'view' });
  const table = Object.fromEntries(nav.flatMap(([, items]) => items.map(([path, , , render]) => [path, render])));
  const titles = Object.fromEntries(nav.flatMap(([, items]) => items.map(([path, label]) => [path, label])));
  const first = nav[0][1][0][0];
  const links = {};
  const workspaceName = h('div', { class: 'workspace-name' }, name);
  const workspaceSub = h('div', { class: 'workspace-sub' }, sub);

  const sidebar = h('nav', { class: 'sidebar', 'aria-label': 'Sections' },
    h('div', { class: 'workspace' }, logoMark(30), h('div', {}, workspaceName, workspaceSub)),
    nav.map(([group, items]) => h('div', { class: 'nav-group' }, group ? h('div', { class: 'nav-title' }, group) : null,
      items.map(([path, label, glyph]) => (links[path] = h('a', { href: `#/${path}` }, icon(glyph, 17), label))))),
    h('div', { class: 'nav-group' },
      h('a', { href: '/docs.html', target: '_blank', rel: 'noopener' }, icon('terminal', 17), 'API reference', h('span', { class: 'tail' }, icon('external', 13)))),
    h('div', { class: 'sidebar-foot' },
      h('div', { class: 'identity' }, identity),
      h('button', { class: 'icon-button', type: 'button', title: 'Log out', 'aria-label': 'Log out', onclick: onLogout }, icon('logout', 17))));

  const topbar = h('header', { class: 'topbar' }, h('div', { class: 'topbar-inner' },
    search ? jumpBox() : null,
    h('div', { class: 'topbar-right' },
      readOnly ? h('span', { class: 'mode quiet', title: 'A TEAM login can read everything and change nothing: the gateway refuses anything but a GET.' }, 'Read-only') : null,
      h('span', { class: 'mode', title: 'There is no real bank behind PayFlo: the acquirer, the bank\'s answers and payouts are simulated.' }, 'Simulated bank'),
      themeToggle())));
  // The page itself never scrolls: the sidebar and the API requests bar stay where they are, and only this area
  // moves. The top bar sits inside it (and sticks) so it lines up with the content whatever the scrollbar's width.
  const scroller = h('div', { class: 'scroller' }, topbar, view);

  function render() {
    const path = location.hash.replace(/^#\//, '') || first;
    const target = table[path] ? path : first;
    Object.entries(links).forEach(([route, link]) => {
      link.classList.toggle('active', route === target);
      if (route === target) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    // A fresh container per navigation: a view that is still loading when the user moves on then draws into a
    // detached element instead of over the next view.
    const container = h('div', {});
    clear(view, container);
    table[target](container, {});
    scroller.scrollTop = 0;
    document.title = `${titles[target]} · PayFlo`;
  }

  window.onhashchange = render;
  clear(app, h('div', { class: 'shell' }, sidebar, h('div', { class: 'main' }, scroller, apiConsole())));
  render();
  return { setWorkspace: (title, line) => { workspaceName.textContent = title; workspaceSub.textContent = line; } };
}

function apiConsole() {
  const rows = h('tbody', {});
  const count = h('span', { class: 'count' }, '');
  const last = h('span', { class: 'last' }, '');
  const body = h('div', { class: 'console-body', hidden: true },
    h('table', {}, h('thead', {}, h('tr', {}, ['Time', 'Method', 'Path', 'Status', 'ms', 'Credential', 'Notes'].map((t) => h('th', {}, t)))), rows));
  const toggle = h('button', { class: 'console-toggle', type: 'button', 'aria-expanded': 'false' }, icon('terminal', 16), 'API requests', count, last);
  toggle.addEventListener('click', () => {
    body.hidden = !body.hidden;
    toggle.setAttribute('aria-expanded', String(!body.hidden));
  });

  function draw() {
    const calls = apiCalls();
    count.textContent = String(calls.length);
    last.textContent = calls.length ? `${calls[0].method} ${calls[0].path.split('?')[0]} → ${calls[0].status || 'ERR'} · ${calls[0].ms} ms` : '';
    clear(rows, calls.slice(0, 40).map((c) => h('tr', {},
      h('td', {}, c.at.toLocaleTimeString('en-IN', { hour12: false })),
      h('td', {}, c.method),
      h('td', {}, c.path),
      h('td', { class: c.status >= 400 || c.status === 0 ? 'status-bad' : 'status-good' }, c.status || 'ERR'),
      h('td', { class: 'right' }, c.ms),
      h('td', {}, c.credential || ''),
      h('td', {}, [c.code, c.idempotencyKey ? `idempotency key ${c.idempotencyKey.slice(0, 8)}…` : null].filter(Boolean).join(' · ')))));
  }

  onApiCall(draw);
  draw();
  return h('aside', { class: 'console', 'aria-label': 'API requests made by this page' }, toggle, body);
}

// ---- start

function start() {
  window.onhashchange = null;
  document.onkeydown = null;
  document.title = 'PayFlo Dashboard';
  if (isAdmin()) {
    shell(OPERATOR_NAV, {
      name: 'PayFlo', sub: 'Platform operator', search: false, onLogout: () => adminLogout(),
      identity: [h('strong', {}, 'Operator'), h('span', {}, 'Admin key')],
    });
    return;
  }
  const session = currentSession();
  if (session) {
    const frame = shell(MERCHANT_NAV, {
      name: 'PayFlo', sub: session.email, search: true, readOnly: session.role === 'TEAM', onLogout: () => logout(),
      identity: [h('strong', {}, session.email), h('span', {}, statusLabel(session.role || ''))],
    });
    // The business's own name at the top, once the profile is in.
    api('GET', '/v1/merchants/me').then((profile) => {
      frame.setWorkspace(profile.businessName || profile.name || 'PayFlo', statusLabel(profile.status));
    }).catch(() => {});
    return;
  }
  loginPage();
}

onSessionEnded(() => {
  document.querySelectorAll('dialog').forEach((d) => d.close());
  location.hash = '';
  start();
});

start();
