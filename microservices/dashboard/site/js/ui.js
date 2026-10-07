// DOM helpers and components shared by every view. Everything is built with createElement and text nodes, never
// innerHTML: receipts, notes, webhook payloads and error text all come from the API and must not become markup.
import { icon, logoMark } from './icons.js';

export { icon, logoMark };

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (name === 'class') el.className = value;
    else if (name.startsWith('on')) el.addEventListener(name.slice(2), value);
    else if (name === 'value') el.value = value;
    else if (value === true) el.setAttribute(name, '');
    else el.setAttribute(name, value);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

// ---- formatting

export function units(amountUnits, currency = 'INR') {
  if (amountUnits === null || amountUnits === undefined) return '–';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(amountUnits / 100);
}

export function money(amount) {
  return amount ? units(amount.amountUnits, amount.currency) : '–';
}

/** An amount as a table reads it: the figure in strong ink, the currency code quiet beside it. */
export function amountCell(amount) {
  if (!amount) return h('span', { class: 'muted' }, '–');
  return h('span', { class: 'amount' }, h('strong', {}, units(amount.amountUnits, amount.currency)), h('span', { class: 'ccy' }, amount.currency));
}

function compact(amountUnits, currency = 'INR') {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(amountUnits / 100);
}

// The API's timestamps carry no zone: they are the server's local time (IST), shown as they are.
function parse(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** The full timestamp, to the second: for detail views, where the order of events matters. */
export function dateTime(value) {
  if (!value) return '–';
  const date = parse(value);
  if (!date) return String(value);
  return date.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
    .replace(/\s/g, ' ');
}

/** A timestamp for a table column: to the minute, with the full one on hover. */
export function shortDate(value) {
  if (!value) return h('span', { class: 'muted' }, '–');
  const date = parse(value);
  if (!date) return String(value);
  const text = date.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).replace(/\s/g, ' ');
  return h('time', { class: 'when', title: dateTime(value) }, text);
}

export function day(value) {
  const date = new Date(value + 'T00:00:00');
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

// The tail, not the head: payment-service's ids are time-ordered (UUID v7), so ids made in the same minute start alike.
export function shortId(id) {
  return id ? '…' + String(id).slice(-8) : '–';
}

export function idCell(id) {
  return h('span', { class: 'mono id', title: id }, shortId(id));
}

export function percent(rate) {
  return rate === null || rate === undefined ? '–' : (rate * 100).toFixed(1) + '%';
}

const METHODS = { CARD: 'Card', UPI: 'UPI', NETBANKING: 'Net banking', WALLET: 'Wallet' };

export function methodLabel(method) {
  return METHODS[method] || method || '–';
}

/** "PARTIALLY_REFUNDED" as people write it: "Partially refunded". Acronyms keep their capitals. */
export function statusLabel(status) {
  const text = String(status).replaceAll('_', ' ').toLowerCase();
  return (text.charAt(0).toUpperCase() + text.slice(1)).replace(/\b(kyc|api|llp)\b/gi, (word) => word.toUpperCase());
}

const TONES = {
  good: ['CAPTURED', 'PAID', 'PROCESSED', 'DELIVERED', 'ACTIVE', 'ENABLED'],
  wait: ['ATTEMPTED', 'AUTHORIZED', 'PENDING', 'PENDING_KYC', 'PARTIALLY_REFUNDED', 'PAUSED'],
  info: ['CREATED', 'AUTHORIZING', 'CAPTURING', 'INITIATED', 'TRANSFER_PENDING', 'SETTLED'],
  bad: ['FAILED', 'DEAD', 'SUSPENDED', 'AUTH_EXPIRED', 'REVOKED'],
};

/** A status as a soft badge. The tone is never the only signal: the label says it too. */
export function badge(status) {
  if (!status) return h('span', { class: 'muted' }, '–');
  const tone = Object.keys(TONES).find((t) => TONES[t].includes(status)) || 'flat';
  return h('span', { class: `badge ${tone}` }, statusLabel(status));
}

export function mono(text) {
  return h('span', { class: 'mono' }, text ?? '–');
}

/** An id or a secret with a button that copies it. */
export function copyable(text, label) {
  const button = h('button', { class: 'icon-button', type: 'button', title: 'Copy', 'aria-label': 'Copy' }, icon('copy', 14));
  button.addEventListener('click', async (event) => {
    event.stopPropagation();
    let done = 'check';
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      done = 'x';
    }
    button.replaceChildren(icon(done, 14));
    setTimeout(() => button.replaceChildren(icon('copy', 14)), 1400);
  });
  return h('span', { class: 'copyable' }, h('code', {}, label || text), button);
}

export function json(value) {
  return h('pre', { class: 'code' }, JSON.stringify(value, null, 2));
}

// ---- layout pieces

/** A padded card. */
export function card(title, ...body) {
  return h('section', { class: 'card' }, title ? h('h2', {}, title) : null, body);
}

/** A card whose content runs edge to edge (a table, a list), with an optional title row and actions. */
export function panel(title, actions, ...body) {
  return h('section', { class: 'card flush' },
    title || actions ? h('header', { class: 'card-head' }, title ? h('h2', {}, title) : h('span', {}), h('div', { class: 'actions' }, actions || null)) : null,
    body);
}

export function stat(label, value, hint) {
  return h('div', { class: 'stat' }, h('div', { class: 'stat-label' }, label), h('div', { class: 'stat-value' }, value),
    hint ? h('div', { class: 'stat-hint' }, hint) : null);
}

export function pageHeader(title, subtitle, ...actions) {
  return h('header', { class: 'page-header' },
    h('div', {}, h('h1', {}, title), subtitle ? h('p', {}, subtitle) : null),
    h('div', { class: 'actions' }, actions));
}

export function button(label, onclick, variant = '', iconName) {
  return h('button', { class: variant, type: 'button', onclick }, iconName ? icon(iconName, 15) : null, label);
}

export function empty(message) {
  return h('div', { class: 'empty' }, icon('empty', 22), h('p', {}, message));
}

const NOTICE_ICONS = { good: 'check', wait: 'clock', bad: 'alert', flat: 'info', info: 'info' };

export function notice(tone, ...content) {
  return h('div', { class: `notice ${tone}` }, icon(NOTICE_ICONS[tone] || 'info', 16), h('div', {}, content));
}

/** Label and value pairs, for a detail view. */
export function details(pairs) {
  return h('dl', { class: 'details' }, pairs.filter(Boolean).map(([label, value]) =>
    [h('dt', {}, label), h('dd', {}, value ?? '–')]));
}

/** The top of a detail view: the figure that matters, its status, and one line of context. */
export function hero(value, status, sub) {
  return h('div', { class: 'hero' }, h('div', { class: 'hero-line' }, h('span', { class: 'hero-value' }, value), status), sub ? h('p', { class: 'muted' }, sub) : null);
}

/** What happened, oldest first. items: [{ title, time, tone, detail }]. */
export function timeline(items) {
  return h('ol', { class: 'timeline' }, items.map((item) => h('li', { class: item.tone || '' },
    h('div', { class: 'timeline-title' }, item.title),
    item.detail ? h('div', { class: 'muted small' }, item.detail) : null,
    item.time ? h('time', { class: 'muted small' }, dateTime(item.time)) : null)));
}

/**
 * columns: [{ title, cell: (row) => node or text, align }]. onRow makes a row clickable.
 */
export function table(columns, rows, onRow, emptyMessage = 'Nothing here yet.') {
  if (!rows.length) return empty(emptyMessage);
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, columns.map((c) => h('th', { class: c.align || '', scope: 'col' }, c.title)))),
    h('tbody', {}, rows.map((row) => h('tr', {
      class: onRow ? 'clickable' : '',
      tabindex: onRow ? '0' : null,
      onclick: onRow ? () => onRow(row) : null,
      onkeydown: onRow ? (e) => { if (e.key === 'Enter') onRow(row); } : null,
    }, columns.map((c) => h('td', { class: c.align || '' }, c.cell(row))))))));
}

export function select(options, value, onchange) {
  const el = h('select', { onchange: (e) => onchange && onchange(e.target.value) },
    options.map((o) => {
      const [val, label] = Array.isArray(o) ? o : [o, o];
      return h('option', { value: val }, label);
    }));
  el.value = value ?? '';
  return el;
}

/**
 * A list the API pages with { items, page, size, hasNext }: status tabs, a table, and previous/next.
 * load(page, filters, size) returns that shape. tabs: { name, options: [value, ...] } is drawn as a tab per value;
 * filters: [{ name, label, options }] as a drop-down each, for a value with too many choices for tabs.
 */
export function pagedList({ load, columns, onRow, tabs, filters = [], size = 20, emptyMessage }) {
  const state = { page: 0, filters: Object.fromEntries([...filters.map((f) => [f.name, '']), ...(tabs ? [[tabs.name, '']] : [])]) };
  const body = h('div', { class: 'list-body' }, h('p', { class: 'loading' }, 'Loading…'));
  const pager = h('div', { class: 'pager' });
  const tabRow = tabs ? h('div', { class: 'tabs', role: 'tablist' }) : null;

  function drawTabs() {
    if (!tabs) return;
    clear(tabRow, [['', 'All'], ...tabs.options.map((o) => (Array.isArray(o) ? o : [o, statusLabel(o)]))].map(([value, label]) =>
      h('button', {
        type: 'button', role: 'tab', class: state.filters[tabs.name] === value ? 'tab active' : 'tab',
        'aria-selected': String(state.filters[tabs.name] === value),
        onclick: () => { state.filters[tabs.name] = value; state.page = 0; drawTabs(); refresh(); },
      }, label)));
  }

  const root = h('div', { class: 'list' },
    tabRow,
    filters.length ? h('div', { class: 'filters' }, filters.map((f) => h('label', {}, f.label,
      select([['', 'All'], ...f.options.map((o) => [o, statusLabel(o)])], '', (v) => { state.filters[f.name] = v; state.page = 0; refresh(); })))) : null,
    body, pager);

  async function refresh() {
    body.classList.add('stale');          // the old rows stay, dimmed, until the new ones arrive: no jump, no flash
    try {
      const result = await load(state.page, state.filters, size);
      clear(body, table(columns, result.items, onRow, emptyMessage));
      clear(pager,
        h('span', { class: 'muted' }, result.items.length ? `Page ${state.page + 1}` : ''),
        h('div', { class: 'pager-buttons' },
          h('button', { class: 'small', type: 'button', disabled: state.page === 0, onclick: () => { state.page--; refresh(); } }, icon('left', 14), 'Newer'),
          h('button', { class: 'small', type: 'button', disabled: !result.hasNext, onclick: () => { state.page++; refresh(); } }, 'Older', icon('right', 14))));
    } catch (error) {
      clear(body, errorBox(error));
      clear(pager);
    } finally {
      body.classList.remove('stale');
    }
  }

  root.refresh = refresh;
  drawTabs();
  refresh();
  return root;
}

// ---- errors, toasts, dialogs

export function errorText(error) {
  if (error && error.code) {
    const wait = error.retryAfter ? ` Try again in ${error.retryAfter} s.` : '';
    return `${error.description || 'The request was refused.'}${wait}`;
  }
  return (error && error.message) || 'Something went wrong';
}

export function errorBox(error) {
  const fields = error && error.fieldErrors;
  return notice('bad',
    h('strong', {}, error && error.code ? `${error.code} ` : ''), error && error.status ? h('span', { class: 'muted' }, `(${error.status}) `) : null,
    h('div', {}, errorText(error)),
    fields && fields.length ? h('ul', {}, fields.map((f) => h('li', {}, `${f.field}: ${f.message}`))) : null);
}

export function toast(message, tone = 'good') {
  let stack = document.querySelector('.toasts');
  if (!stack) {
    stack = h('div', { class: 'toasts', role: 'status', 'aria-live': 'polite', popover: 'manual' });
    document.body.append(stack);
  }
  const el = h('div', { class: `toast ${tone}` }, icon(NOTICE_ICONS[tone] || 'info', 16), h('span', {}, message));
  stack.append(el);
  // A dialog or drawer sits in the browser's top layer, above everything in the page. Showing the stack as a
  // popover, afresh for each message, puts it in that layer too and above whatever opened last.
  if (stack.showPopover) {
    if (stack.matches(':popover-open')) stack.hidePopover();
    stack.showPopover();
  }
  setTimeout(() => {
    el.remove();
    if (!stack.children.length && stack.hidePopover && stack.matches(':popover-open')) stack.hidePopover();
  }, tone === 'bad' ? 7000 : 3800);
}

export function toastError(error) {
  toast(error && error.code ? `${error.code}: ${errorText(error)}` : errorText(error), 'bad');
}

/**
 * A modal, or with drawer: true a panel from the right for a detail view. Returns { close, body }.
 */
export function dialog(title, content, { wide = false, drawer = false, onClose } = {}) {
  const body = h('div', { class: 'dialog-body' }, content);
  const el = h('dialog', { class: drawer ? 'drawer' : wide ? 'wide' : '' },
    h('header', {}, h('h2', {}, title),
      h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Close', title: 'Close', onclick: () => el.close() }, icon('x', 18))),
    body);
  el.addEventListener('close', () => { el.remove(); if (onClose) onClose(); });
  el.addEventListener('click', (event) => { if (event.target === el) el.close(); });
  document.body.append(el);
  el.showModal();
  return { close: () => el.close(), body };
}

/**
 * A form. fields: [{ name, label, type, value, required, placeholder, hint, options, pattern, min, max }].
 * onSubmit(values) may throw an ApiError: its message is shown above the button, and a field the API names is marked.
 */
export function form(fields, onSubmit, { submit = 'Save', danger = false, block = false, extra } = {}) {
  const inputs = {};
  const fieldErrors = {};
  const error = h('div', { 'aria-live': 'polite' });
  const submitButton = h('button', { class: (danger ? 'danger' : 'primary') + (block ? ' block' : ''), type: 'submit' }, submit);
  const el = h('form', { class: 'form' },
    fields.map((f) => {
      let input;
      if (f.options) {
        input = select(f.options, f.value ?? '');
      } else if (f.type === 'textarea') {
        input = h('textarea', { rows: f.rows || 3, placeholder: f.placeholder }, f.value || '');
      } else {
        input = h('input', {
          type: f.type || 'text', value: f.value ?? '', placeholder: f.placeholder, required: f.required,
          pattern: f.pattern, min: f.min, max: f.max, step: f.step, minlength: f.minlength, maxlength: f.maxlength,
          autocomplete: f.autocomplete || 'off', inputmode: f.inputmode,
        });
      }
      input.name = f.name;
      inputs[f.name] = input;
      fieldErrors[f.name] = h('small', { class: 'field-error' });
      return h('label', { class: 'field' }, h('span', {}, f.label, f.required ? null : h('em', {}, 'Optional')), input,
        fieldErrors[f.name], f.hint ? h('small', {}, f.hint) : null);
    }),
    extra || null, error, h('div', { class: 'form-actions' }, submitButton));

  function showFieldErrors(list) {
    for (const [name, slot] of Object.entries(fieldErrors)) {
      const found = (list || []).find((f) => f.field === name);
      slot.textContent = found ? found.message : '';
      inputs[name].toggleAttribute('aria-invalid', Boolean(found));
    }
  }

  el.addEventListener('submit', async (event) => {
    event.preventDefault();
    const values = Object.fromEntries(Object.entries(inputs).map(([name, input]) => [name, input.value.trim()]));
    submitButton.disabled = true;
    submitButton.classList.add('busy');
    clear(error);
    showFieldErrors([]);
    try {
      await onSubmit(values);
    } catch (e) {
      clear(error, errorBox(e));
      showFieldErrors(e && e.fieldErrors);
    } finally {
      submitButton.disabled = false;
      submitButton.classList.remove('busy');
    }
  });
  el.inputs = inputs;
  return el;
}

export function confirmDialog(title, message, actionLabel, action, { danger = true } = {}) {
  const error = h('div', {});
  const go = h('button', { class: danger ? 'danger' : 'primary', type: 'button' }, actionLabel);
  const modal = dialog(title, [h('p', {}, message), error,
    h('div', { class: 'form-actions' }, h('button', { type: 'button', onclick: () => modal.close() }, 'Cancel'), go)]);
  go.addEventListener('click', async () => {
    go.disabled = true;
    try {
      await action();
      modal.close();
    } catch (e) {
      clear(error, errorBox(e));
      go.disabled = false;
    }
  });
}

/** A secret the API returns once: shown in a dialog that says so. */
export function showSecret(title, rows, note) {
  dialog(title, [
    notice('wait', 'Copy this now. It is stored hashed or encrypted and can\'t be shown again.'),
    details(rows.map(([label, value]) => [label, copyable(value)])),
    note || null,
  ]);
}

// ---- charts (inline SVG, no library)

const SVG = 'http://www.w3.org/2000/svg';

function svg(tag, attrs, ...children) {
  const el = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attrs || {})) el.setAttribute(name, value);
  append(el, children);
  return el;
}

// A y-axis that ends on a round number, with three or four round steps up to it.
function niceScale(max) {
  if (max <= 0) return { top: 1, ticks: [0] };
  const rough = max / 3;
  const power = 10 ** Math.floor(Math.log10(rough));
  const fraction = rough / power;
  const step = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10) * power;
  const top = Math.ceil(max / step) * step;
  const ticks = [];
  for (let value = 0; value <= top + step / 2; value += step) ticks.push(value);
  return { top, ticks };
}

/**
 * One series over time, in paise. points: [{ label, value, title, detail }], oldest first. A line with a light wash
 * when there are enough points to read as a trend, columns when there are only a few. Pointing at (or arrowing to) a
 * position shows its value; the same numbers are in a table for a screen reader.
 */
export function trendChart(points, { name = 'Captured volume', currency = 'INR', height = 230 } = {}) {
  const W = 720, H = height, left = 54, right = 14, top = 14, bottom = 28;
  const innerW = W - left - right, innerH = H - top - bottom, base = top + innerH;
  const scale = niceScale(Math.max(0, ...points.map((p) => p.value)));
  const columns = points.length < 4;
  const band = innerW / Math.max(1, points.length);
  const x = (i) => (columns || points.length === 1 ? left + band * (i + 0.5) : left + (innerW * i) / (points.length - 1));
  const y = (value) => base - (value / scale.top) * innerH;
  const last = points.length - 1;

  const chart = svg('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img', tabindex: '0',
    'aria-label': `${name}, ${points.length} periods${points.length ? ` from ${points[0].label} to ${points[last].label}` : ''}. Use the arrow keys to read each value.` });

  for (const tick of scale.ticks) {
    chart.append(svg('line', { x1: left, x2: W - right, y1: y(tick), y2: y(tick), class: tick === 0 ? 'axis' : 'grid' }),
      svg('text', { x: left - 8, y: y(tick) + 3.5, class: 'tick', 'text-anchor': 'end' }, compact(tick, currency)));
  }
  // At most about seven date labels, always including the first; the last only when it isn't crowding its neighbour.
  const every = Math.max(1, Math.ceil(points.length / 7));
  points.forEach((p, i) => {
    const regular = i % every === 0;
    const closing = i === last && last % every >= every / 2;
    if (!p.label || !(regular || closing)) return;
    const anchor = columns ? 'middle' : i === 0 ? 'start' : i === last ? 'end' : 'middle';
    chart.append(svg('text', { x: x(i), y: H - 8, class: 'tick', 'text-anchor': anchor }, p.label));
  });

  const marks = [];
  if (columns) {
    const width = Math.min(24, band * 0.5);
    points.forEach((p, i) => {
      const tall = base - y(p.value), r = Math.min(4, tall / 2), x0 = x(i) - width / 2;
      const d = tall <= 0 ? '' : `M${x0} ${base}V${y(p.value) + r}q0 ${-r} ${r} ${-r}h${width - 2 * r}q${r} 0 ${r} ${r}V${base}Z`;
      const mark = svg('path', { d, class: 'column' });
      marks.push(mark);
      chart.append(mark);
    });
  } else {
    const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(p.value).toFixed(1)}`).join('');
    chart.append(svg('path', { d: `${line}L${x(last)} ${base}L${x(0)} ${base}Z`, class: 'wash' }),
      svg('path', { d: line, class: 'line' }),
      svg('circle', { cx: x(last), cy: y(points[last].value), r: 4, class: 'dot' }));
  }

  const cross = svg('line', { y1: top, y2: base, class: 'cross', visibility: 'hidden' });
  const focus = svg('circle', { r: 4.5, class: 'dot', visibility: 'hidden' });
  chart.append(cross, focus);

  const tip = h('div', { class: 'chart-tip', hidden: true });
  // The same numbers as a table, for a screen reader. It is hidden through a wrapper, never by sizing the table
  // itself: a table ignores a width and height smaller than its content, so one "hidden" at 1px keeps its full
  // height and stretches the page far past its end.
  const wrap = h('div', { class: 'chart-wrap' }, chart, tip,
    h('div', { class: 'sr-only' }, h('table', {}, h('caption', {}, name),
      h('tbody', {}, points.map((p) => h('tr', {}, h('th', { scope: 'row' }, p.title || p.label), h('td', {}, units(p.value, currency))))))));

  let current = -1;
  function hide() {
    current = -1;
    tip.hidden = true;
    cross.setAttribute('visibility', 'hidden');
    focus.setAttribute('visibility', 'hidden');
    marks.forEach((mark) => mark.classList.remove('active'));
  }
  function show(i) {
    if (i < 0 || i > last) return hide();
    current = i;
    const p = points[i];
    marks.forEach((mark, index) => mark.classList.toggle('active', index === i));
    if (!columns) {
      cross.setAttribute('x1', x(i));
      cross.setAttribute('x2', x(i));
      cross.setAttribute('visibility', 'visible');
      focus.setAttribute('cx', x(i));
      focus.setAttribute('cy', y(p.value));
      focus.setAttribute('visibility', 'visible');
    }
    clear(tip, h('strong', {}, units(p.value, currency)), h('span', {}, p.title || p.label), p.detail ? h('span', {}, p.detail) : null);
    tip.hidden = false;
    const box = chart.getBoundingClientRect();
    const px = (x(i) / W) * box.width, py = (y(p.value) / H) * box.height;
    tip.classList.toggle('flip', px > box.width * 0.62);
    tip.style.left = `${px}px`;
    tip.style.top = `${Math.max(0, py - 12)}px`;
  }
  // The pointer only has to be nearest a position, never on the line itself.
  chart.addEventListener('pointermove', (event) => {
    const box = chart.getBoundingClientRect();
    const at = ((event.clientX - box.left) / box.width) * W;
    let nearest = 0;
    points.forEach((_, i) => { if (Math.abs(x(i) - at) < Math.abs(x(nearest) - at)) nearest = i; });
    show(nearest);
  });
  chart.addEventListener('pointerleave', hide);
  chart.addEventListener('focus', () => show(last));
  chart.addEventListener('blur', hide);
  chart.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowLeft') show(Math.max(0, current - 1));
    else if (event.key === 'ArrowRight') show(Math.min(last, current + 1));
    else if (event.key === 'Escape') hide();
    else return;
    event.preventDefault();
  });
  return wrap;
}

/** rows: [{ label, value, text }]. One bar per row from a shared baseline, one colour, the value written beside it. */
export function shareBars(rows) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return h('div', { class: 'shares' }, rows.map((r) => {
    const fill = h('div', { class: 'share-fill' });
    fill.style.width = `${Math.max(1, (r.value / max) * 100)}%`;
    return h('div', { class: 'share', title: `${r.label}: ${r.text}` }, h('span', { class: 'share-label' }, r.label),
      h('div', { class: 'share-track' }, fill), h('span', { class: 'share-value' }, r.text));
  }));
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
