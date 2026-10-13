// Shared by the shop's three pages. Everything is built with createElement and text nodes, never innerHTML.

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

/** ₹1,047 for whole rupees, ₹1,047.50 otherwise, as an Indian shop prints prices. */
export function money(amountUnits, currency = 'INR') {
  const whole = amountUnits % 100 === 0;
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 }).format(amountUnits / 100);
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** A call to the shop's own backend. A refusal is thrown with the sentence to show and the field it is about. */
export async function shop(method, path, body) {
  let response;
  try {
    response = await fetch(path, {
      method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw Object.assign(new Error('We couldn\'t reach the shop. Check your connection and try again.'), { code: 'NETWORK' });
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw Object.assign(new Error(result.message || 'Something went wrong. Try again.'), { code: result.errorCode, field: result.field, status: response.status });
  }
  return result;
}

export const SUPPORT_EMAIL = 'help@chaiandco.example';

// ---- drawings (inline SVG: the page's policy allows no remote images)

const SVG = 'http://www.w3.org/2000/svg';

export function svg(tag, attrs, ...children) {
  const el = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attrs || {})) el.setAttribute(name, value);
  el.append(...children);
  return el;
}

const ICONS = {
  bag: ['M6 8h12l1 12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1L6 8Z', 'M9 8V6.5a3 3 0 0 1 6 0V8'],
  x: ['M6 6l12 12', 'M18 6 6 18'],
  plus: ['M12 6v12', 'M6 12h12'],
  minus: ['M6 12h12'],
  lock: ['M7 11V8a5 5 0 0 1 10 0v3', 'M6 11h12a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1Z'],
  back: ['M19 12H5', 'm11 6-6 6 6 6'],
  chevron: ['m9 6 6 6-6 6'],
  down: ['m6 9 6 6 6-6'],
  check: ['m5 12.5 4.5 4.5L19 7.5'],
  alert: ['M12 8v5', 'M12 16.5h.01', 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z'],
  card: ['M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z', 'M2 10h20', 'M6 15h4'],
  upi: ['M13 2 4 14h7l-1 8 9-12h-7l1-8Z'],
  bank: ['M3 21h18', 'M12 3 3 8v2h18V8l-9-5Z', 'M6 10v8', 'M10 10v8', 'M14 10v8', 'M18 10v8'],
  wallet: ['M4 6h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a1 1 0 0 1 1-1Z', 'M16 13h.01', 'M3 9h17'],
  truck: ['M3 6h11v10H3z', 'M14 9h4l3 3v4h-7', 'M7 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z', 'M17 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z'],
  leaf: ['M5 19c0-8 5-13 14-14 0 9-4 14-12 14', 'M5 19c3-5 6-8 10-10'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z', 'm20 20-3.5-3.5'],
  star: ['m12 3.5 2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9L12 3.5Z'],
  shield: ['M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6l-7-3Z', 'm9 12 2 2 4-4'],
  refresh: ['M20 11a8 8 0 0 0-14.8-4M4 4v4h4', 'M4 13a8 8 0 0 0 14.8 4M20 20v-4h-4'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 7v5l3 2'],
  box: ['M3 7.5 12 3l9 4.5v9L12 21l-9-4.5v-9Z', 'M3 7.5 12 12l9-4.5', 'M12 12v9'],
  home: ['M4 11 12 4l8 7', 'M6 9.5V20h12V9.5'],
  mail: ['M4 6h16v12H4z', 'm4 7 8 6 8-6'],
  phone: ['M7 3h10v18H7z', 'M11 18h2'],
  pin: ['M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21Z', 'M12 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z'],
  gift: ['M4 11h16v10H4z', 'M3 7h18v4H3z', 'M12 7v14', 'M12 7c-1.5-3-5-3.5-5-1.5S10 7 12 7Zm0 0c1.5-3 5-3.5 5-1.5S14 7 12 7Z'],
  cup: ['M5 8h11v6a5 5 0 0 1-5 5H10a5 5 0 0 1-5-5V8Z', 'M16 10h1.5a2.5 2.5 0 0 1 0 5H16', 'M8 3v2', 'M11 3v2'],
};

export function icon(name, size = 18) {
  return svg('svg', { viewBox: '0 0 24 24', width: size, height: size, fill: 'none', stroke: 'currentColor', 'stroke-width': '1.75',
    'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true', class: 'icon' },
  ...ICONS[name].map((d) => svg('path', { d })));
}

/** A row of stars and the review count: ★ 4.8 (1,284). */
export function rating(product) {
  return h('span', { class: 'rating', 'aria-label': `Rated ${product.rating} out of 5 from ${product.reviews} reviews` },
    icon('star', 14), h('strong', {}, product.rating.toFixed(1)), h('span', { class: 'muted' }, `(${product.reviews.toLocaleString('en-IN')})`));
}

/** The price, with the struck-through MRP when the product is on offer. */
export function price(product, extraClass = '') {
  return h('span', { class: `price ${extraClass}` },
    h('strong', {}, money(product.priceUnits)),
    product.mrpUnits ? [h('s', { class: 'mrp' }, money(product.mrpUnits)),
      h('span', { class: 'saving' }, `${Math.round((1 - product.priceUnits / product.mrpUnits) * 100)}% off`)] : null);
}

/** The card networks and UPI, as small wordmarks. */
export function paymentMarks() {
  return h('span', { class: 'pay-marks', 'aria-label': 'UPI, Visa, Mastercard, RuPay' },
    ['UPI', 'VISA', 'Mastercard', 'RuPay'].map((name) => h('span', { class: `pay-mark pay-mark-${name.toLowerCase()}` }, name)));
}

let payfloMarks = 0;

/** PayFlo's mark: two slanted, offset bars on an indigo tile, as in docs/assets/logo.svg. */
export function payfloMark(size = 20) {
  const id = `pf-mark-${++payfloMarks}`;
  return svg('svg', { viewBox: '0 0 32 32', width: size, height: size, 'aria-hidden': 'true', class: 'payflo-mark' },
    svg('defs', {},
      svg('linearGradient', { id: `${id}-tile`, x1: 3, y1: 2, x2: 29, y2: 30, gradientUnits: 'userSpaceOnUse' },
        svg('stop', { offset: 0, 'stop-color': '#7067ff' }), svg('stop', { offset: 0.55, 'stop-color': '#3b4ce8' }),
        svg('stop', { offset: 1, 'stop-color': '#2733c2' }))),
    svg('rect', { width: 32, height: 32, rx: 9, fill: `url(#${id}-tile)` }),
    svg('g', { transform: 'translate(3.6 0) skewX(-12)' },
      svg('rect', { x: 7.5, y: 9, width: 12.5, height: 5.5, rx: 2.75, fill: '#fff' }),
      svg('rect', { x: 13.5, y: 17.5, width: 12.5, height: 5.5, rx: 2.75, fill: '#c3c8ff' })));
}

/** The shop's mark: a leaf in a round badge. */
export function shopMark(size = 30) {
  return svg('svg', { viewBox: '0 0 32 32', width: size, height: size, 'aria-hidden': 'true', class: 'shop-mark' },
    svg('circle', { cx: 16, cy: 16, r: 16, fill: '#1d4a38' }),
    svg('path', { d: 'M9.5 22.5c0-7.5 4.5-12 13-13 0 8.5-3.8 13-11 13', fill: '#f3e9d2' }),
    svg('path', { d: 'M9.5 22.5c2.6-4.6 5.6-7.4 9.4-9.4', fill: 'none', stroke: '#1d4a38', 'stroke-width': '1.4', 'stroke-linecap': 'round' }));
}

/** The shop's logo: the mark and the name. */
export function logo(href = '/') {
  return h('a', { class: 'logo', href, 'aria-label': 'Chai & Co. home' }, shopMark(34),
    h('span', { class: 'logo-text' }, h('span', { class: 'logo-name' }, 'Chai & Co.'), h('span', { class: 'logo-sub' }, 'Tea merchants · Est. 2019')));
}

// background, body, lid or rim, label
const TONES = {
  spice: ['#f6e7d8', '#a8432a', '#7f2f1c', '#fbf3e6'],
  amber: ['#f7ecd2', '#b9791c', '#8f5a10', '#fdf6e3'],
  leaf: ['#e4efdf', '#3f7a4b', '#2c5a36', '#f6f8ee'],
  saffron: ['#fbe9cf', '#d98a1f', '#a96510', '#fff7e8'],
  mint: ['#dff1ea', '#2f8f78', '#1f6b59', '#f2faf6'],
  lemon: ['#f2f0d4', '#b4a22c', '#86791a', '#fffbe8'],
  clay: ['#f3e2d6', '#b86b47', '#8f4e31', '#f3e2d6'],
  gift: ['#f4e4dd', '#8f2f2f', '#6b1f1f', '#e2b553'],
};

function labelText(product, y, size, fill) {
  return [
    svg('text', { x: 100, y, 'text-anchor': 'middle', 'font-size': 6.5, 'letter-spacing': 1.6, 'font-weight': 600, fill, 'fill-opacity': '.75',
      'font-family': 'Segoe UI, Helvetica, Arial, sans-serif' }, 'CHAI & CO.'),
    svg('text', { x: 100, y: y + size + 5, 'text-anchor': 'middle', 'font-size': size, 'letter-spacing': .6, 'font-weight': 700, fill,
      'font-family': 'Palatino Linotype, Book Antiqua, Georgia, serif' }, product.name.replace(/^The /, '').split(' ')[0].toUpperCase()),
  ];
}

/** The object alone, drawn in a 200 × 200 box: a tin, a pouch, a clay cup or a gift box. */
export function productObject(product) {
  const [, body, dark, label] = TONES[product.tone] || TONES.leaf;
  const g = svg('g', {}, svg('ellipse', { cx: 100, cy: 166, rx: 54, ry: 7, fill: '#000', 'fill-opacity': '.09' }));
  if (product.shape === 'cup') {
    g.append(
      svg('path', { d: 'M62 78h76l-9 76a10 10 0 0 1-10 9H81a10 10 0 0 1-10-9l-9-76Z', fill: body }),
      svg('path', { d: 'M66 78h12l7 85h-4a10 10 0 0 1-10-9l-5-76Z', fill: '#fff', 'fill-opacity': '.1' }),
      svg('ellipse', { cx: 100, cy: 78, rx: 38, ry: 9, fill: dark }),
      svg('ellipse', { cx: 100, cy: 79, rx: 31, ry: 6, fill: '#6b4423' }),
      svg('ellipse', { cx: 96, cy: 78, rx: 12, ry: 2, fill: '#c89a6a', 'fill-opacity': '.45' }),
      svg('path', { d: 'M68 108h64M70 124h60', stroke: dark, 'stroke-width': '2.5', 'stroke-linecap': 'round', 'stroke-opacity': '.45', fill: 'none' }),
      svg('path', { d: 'M92 62c-4-6 4-9 0-16M106 60c-4-6 4-9 0-16', stroke: dark, 'stroke-width': '2.5', 'stroke-linecap': 'round', 'stroke-opacity': '.3', fill: 'none' }));
  } else if (product.shape === 'pouch') {
    g.append(
      svg('path', { d: 'M66 58h68l7 102a6 6 0 0 1-6 6H65a6 6 0 0 1-6-6l7-102Z', fill: body }),
      svg('path', { d: 'M66 58h12l-6 108h-7a6 6 0 0 1-6-6l7-102Z', fill: '#fff', 'fill-opacity': '.14' }),
      svg('rect', { x: 64, y: 48, width: 72, height: 14, rx: 3, fill: dark }),
      svg('path', { d: 'M70 55h60', stroke: '#fff', 'stroke-opacity': '.25', 'stroke-width': '1', 'stroke-dasharray': '2 3' }),
      svg('rect', { x: 72, y: 90, width: 56, height: 52, rx: 5, fill: label }),
      ...labelText(product, 108, 9, dark),
      svg('path', { d: 'M93 133c0-6 4-10 10-10 0 6-3 10-9 10', fill: body }));
  } else if (product.shape === 'box') {
    g.append(
      svg('rect', { x: 46, y: 88, width: 108, height: 74, rx: 4, fill: body }),
      svg('rect', { x: 46, y: 88, width: 16, height: 74, fill: '#fff', 'fill-opacity': '.08' }),
      svg('rect', { x: 40, y: 72, width: 120, height: 22, rx: 4, fill: dark }),
      svg('rect', { x: 93, y: 72, width: 14, height: 90, fill: label }),
      svg('path', { d: 'M100 72c-8-14-26-16-24-6 1 6 14 7 24 6Zm0 0c8-14 26-16 24-6-1 6-14 7-24 6Z', fill: label }),
      svg('rect', { x: 58, y: 112, width: 28, height: 20, rx: 2, fill: '#fff', 'fill-opacity': '.92', transform: 'rotate(-6 72 122)' }),
      svg('path', { d: 'M63 120h17M63 125h12', stroke: dark, 'stroke-width': '1.4', 'stroke-linecap': 'round', 'stroke-opacity': '.6', transform: 'rotate(-6 72 122)' }));
  } else {
    g.append(
      svg('rect', { x: 62, y: 56, width: 76, height: 108, rx: 9, fill: body }),
      svg('rect', { x: 62, y: 56, width: 14, height: 108, rx: 7, fill: '#fff', 'fill-opacity': '.14' }),
      svg('rect', { x: 58, y: 44, width: 84, height: 20, rx: 7, fill: dark }),
      svg('rect', { x: 58, y: 58, width: 84, height: 3, fill: '#000', 'fill-opacity': '.12' }),
      svg('rect', { x: 62, y: 86, width: 76, height: 56, fill: label }),
      ...labelText(product, 101, 9, dark),
      svg('path', { d: 'M93 133c0-6 4-10 10-10 0 6-3 10-9 10', fill: body }));
  }
  return g;
}

/** A product's picture: its object on a soft background in the product's own colours. */
export function productArt(product) {
  const [background] = TONES[product.tone] || TONES.leaf;
  return svg('svg', { viewBox: '0 0 200 200', class: 'art', role: 'img', 'aria-label': product.name, preserveAspectRatio: 'xMidYMid slice' },
    svg('rect', { width: 200, height: 200, fill: background }),
    svg('circle', { cx: 100, cy: 104, r: 74, fill: '#fff', 'fill-opacity': '.35' }),
    productObject(product));
}

// ---- the order summary, shared by the checkout and the confirmation

export function orderSummary(session) {
  const count = session.items.reduce((sum, item) => sum + item.quantity, 0);
  return h('div', { class: 'summary-card' },
    h('ul', { class: 'summary-items' }, session.items.map((item) => h('li', { class: 'summary-item' },
      h('div', { class: 'summary-thumb' }, productArt(item), h('span', { class: 'qty-badge', 'aria-label': `Quantity ${item.quantity}` }, item.quantity)),
      h('div', { class: 'summary-name' }, h('strong', {}, item.name), h('span', { class: 'muted small' }, item.size)),
      h('span', { class: 'summary-price' }, money(item.totalUnits, session.currency))))),
    h('dl', { class: 'summary-totals' },
      h('div', {}, h('dt', {}, `Subtotal · ${count} ${count === 1 ? 'item' : 'items'}`), h('dd', {}, money(session.subtotalUnits, session.currency))),
      h('div', {}, h('dt', {}, 'Shipping'), h('dd', {}, session.shippingUnits ? money(session.shippingUnits, session.currency) : h('span', { class: 'free' }, 'Free'))),
      h('div', { class: 'summary-total' }, h('dt', {}, 'Total'), h('dd', {}, h('span', { class: 'muted small' }, session.currency), money(session.amountUnits, session.currency)))),
    h('p', { class: 'muted small' }, 'Prices include GST.'));
}

/** "Thu, 16 Oct": the delivery estimate, counting working days (Monday to Saturday). */
export function workingDaysFrom(start, days) {
  const date = new Date(start);
  while (days > 0) {
    date.setDate(date.getDate() + 1);
    if (date.getDay() !== 0) days--;
  }
  return date.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
}
