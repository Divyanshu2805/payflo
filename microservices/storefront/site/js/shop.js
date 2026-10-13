// The shop: products, a product view, and a cart that starts a checkout. Prices shown here come from the shop's
// server, and the server works the total out again from product ids: nothing the browser says sets an amount.
import { h, clear, money, shop, icon, svg, logo, rating, price, paymentMarks, productArt, productObject, SUPPORT_EMAIL } from './dom.js';

const app = document.getElementById('app');
const CART_KEY = 'chai.basket';
const CATEGORIES = [['all', 'Shop all'], ['black', 'Black tea'], ['green', 'Green & herbal'], ['teaware', 'Teaware & gifts']];

let config = { catalog: [], maxQuantity: 10, freeShippingFrom: 0, shippingUnits: 0 };
let cart = readCart();          // { productId: quantity }
let filter = 'all';
let query = '';

function readCart() {
  try {
    return JSON.parse(sessionStorage.getItem(CART_KEY)) || {};
  } catch {
    return {};
  }
}

function saveCart() {
  try {
    sessionStorage.setItem(CART_KEY, JSON.stringify(cart));
  } catch {
    // A private window: the cart then lasts until the page is reloaded.
  }
}

const lines = () => config.catalog.filter((p) => cart[p.id]).map((p) => ({ ...p, quantity: cart[p.id] }));
const count = () => Object.values(cart).reduce((sum, quantity) => sum + quantity, 0);
const subtotal = () => lines().reduce((sum, line) => sum + line.priceUnits * line.quantity, 0);
const shipping = () => (subtotal() >= config.freeShippingFrom ? 0 : config.shippingUnits);

function setQuantity(id, quantity) {
  if (quantity <= 0) delete cart[id];
  else cart[id] = Math.min(config.maxQuantity, quantity);
  saveCart();
  drawCart();
}

function addToCart(product, quantity = 1) {
  setQuantity(product.id, (cart[product.id] || 0) + quantity);
  toast(product);
}

// ---- a short "added to cart" note

const toastSlot = h('div', { class: 'toast-slot', 'aria-live': 'polite' });
let toastTimer;

function toast(product) {
  clearTimeout(toastTimer);
  clear(toastSlot, h('div', { class: 'toast' },
    h('div', { class: 'toast-thumb' }, productArt(product)),
    h('div', {}, h('strong', {}, 'Added to your cart'), h('div', { class: 'muted small' }, product.name)),
    h('button', { class: 'link-button', type: 'button', onclick: () => { clear(toastSlot); openCart(); } }, 'View cart')));
  toastTimer = setTimeout(() => clear(toastSlot), 3200);
}

// ---- the cart, a drawer from the right

const badge = h('span', { class: 'cart-count', hidden: true });
const drawerTitle = h('h2', {}, 'Your cart');
const drawerBody = h('div', { class: 'drawer-body' });
const drawerFoot = h('div', { class: 'drawer-foot' });
const drawer = h('dialog', { class: 'drawer', 'aria-label': 'Your cart' },
  h('header', { class: 'drawer-head' }, drawerTitle,
    h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Close the cart', onclick: () => drawer.close() }, icon('x', 20))),
  drawerBody, drawerFoot);
drawer.addEventListener('click', (event) => { if (event.target === drawer) drawer.close(); });

function openCart() {
  drawCart();
  if (!drawer.open) drawer.showModal();
}

function shippingMeter() {
  const remaining = config.freeShippingFrom - subtotal();
  const share = Math.min(1, subtotal() / config.freeShippingFrom);
  const bar = h('span', { class: 'meter-fill' });
  bar.style.width = `${Math.round(share * 100)}%`;
  return h('div', { class: `meter ${remaining <= 0 ? 'meter-done' : ''}` },
    h('p', {}, icon('truck', 16), remaining <= 0
      ? h('span', {}, 'You\'ve unlocked ', h('strong', {}, 'free shipping'), '.')
      : h('span', {}, 'Add ', h('strong', {}, money(remaining)), ' more for free shipping.')),
    h('span', { class: 'meter-track' }, bar));
}

function drawCart() {
  const items = lines();
  badge.textContent = String(count());
  badge.hidden = !items.length;
  drawerTitle.textContent = items.length ? `Your cart (${count()})` : 'Your cart';
  if (!items.length) {
    clear(drawerBody, h('div', { class: 'cart-empty' },
      h('span', { class: 'cart-empty-icon' }, icon('bag', 30)),
      h('strong', {}, 'Your cart is empty'),
      h('p', { class: 'muted' }, 'Our bestsellers are a good place to start.'),
      h('button', { class: 'button primary', type: 'button', onclick: () => { drawer.close(); showCategory('all'); } }, 'Start shopping')));
    clear(drawerFoot);
    return;
  }
  clear(drawerBody, shippingMeter(), items.map((line) => h('div', { class: 'line' },
    h('div', { class: 'thumb' }, productArt(line)),
    h('div', { class: 'line-main' },
      h('div', { class: 'line-top' }, h('strong', {}, line.name), h('strong', { class: 'line-total' }, money(line.priceUnits * line.quantity))),
      h('div', { class: 'muted small' }, `${line.size} · ${money(line.priceUnits)}`),
      h('div', { class: 'line-actions' },
        h('div', { class: 'stepper' },
          h('button', { type: 'button', 'aria-label': `One fewer ${line.name}`, onclick: () => setQuantity(line.id, line.quantity - 1) }, icon('minus', 14)),
          h('span', { 'aria-live': 'polite' }, line.quantity),
          h('button', { type: 'button', 'aria-label': `One more ${line.name}`, disabled: line.quantity >= config.maxQuantity, onclick: () => setQuantity(line.id, line.quantity + 1) }, icon('plus', 14))),
        h('button', { class: 'link-button muted', type: 'button', onclick: () => setQuantity(line.id, 0) }, 'Remove'))))));

  const error = h('p', { class: 'error', role: 'alert', hidden: true });
  const total = subtotal() + shipping();
  const go = h('button', { class: 'button primary block', type: 'button' }, icon('lock', 16), h('span', {}, 'Checkout'), h('span', { class: 'go-amount' }, money(total)));
  go.addEventListener('click', async () => {
    go.disabled = true;
    error.hidden = true;
    try {
      const session = await shop('POST', '/store/checkout', { items: items.map((line) => ({ id: line.id, quantity: line.quantity })) });
      location.href = `/checkout.html?session=${encodeURIComponent(session.id)}`;
    } catch (failure) {
      error.textContent = failure.message;
      error.hidden = false;
      go.disabled = false;
    }
  });
  clear(drawerFoot,
    h('div', { class: 'sum-row' }, h('span', {}, 'Subtotal'), h('span', {}, money(subtotal()))),
    h('div', { class: 'sum-row' }, h('span', {}, 'Shipping'), h('span', {}, shipping() ? money(shipping()) : h('span', { class: 'free' }, 'Free'))),
    h('div', { class: 'sum-row sum-total' }, h('span', {}, 'Total'), h('span', {}, money(total))),
    h('p', { class: 'muted small' }, 'Prices include GST. Delivery in 3–5 working days.'),
    error, go,
    h('div', { class: 'secure-row' }, icon('shield', 15), h('span', {}, 'Secure payments'), paymentMarks()));
}

// ---- one product, in a dialog

const productDialog = h('dialog', { class: 'product-dialog', 'aria-label': 'Product' });
productDialog.addEventListener('click', (event) => { if (event.target === productDialog) productDialog.close(); });

function openProduct(product) {
  let quantity = 1;
  const amount = h('span', {}, quantity);
  const less = h('button', { type: 'button', 'aria-label': 'One fewer', disabled: true, onclick: () => step(-1) }, icon('minus', 14));
  const more = h('button', { type: 'button', 'aria-label': 'One more', onclick: () => step(1) }, icon('plus', 14));
  function step(by) {
    quantity = Math.max(1, Math.min(config.maxQuantity, quantity + by));
    amount.textContent = String(quantity);
    less.disabled = quantity <= 1;
    more.disabled = quantity >= config.maxQuantity;
  }
  clear(productDialog,
    h('button', { class: 'icon-button dialog-close', type: 'button', 'aria-label': 'Close', onclick: () => productDialog.close() }, icon('x', 20)),
    h('div', { class: 'pd' },
      h('div', { class: 'pd-art' }, productArt(product), product.badge ? h('span', { class: 'badge' }, product.badge) : null),
      h('div', { class: 'pd-info' },
        h('p', { class: 'eyebrow' }, CATEGORIES.find(([key]) => key === product.category)?.[1] || ''),
        h('h2', {}, product.name),
        h('div', { class: 'pd-meta' }, rating(product), h('span', { class: 'muted' }, product.size)),
        price(product, 'pd-price'),
        h('p', { class: 'muted small' }, 'Inclusive of all taxes'),
        h('p', { class: 'pd-description' }, product.description),
        h('div', { class: 'notes' }, product.notes.map((note) => h('span', { class: 'note' }, note))),
        h('p', { class: 'brew' }, icon(product.category === 'teaware' ? 'gift' : 'cup', 16), product.brew),
        h('div', { class: 'pd-buy' },
          h('div', { class: 'stepper stepper-lg' }, less, amount, more),
          h('button', { class: 'button primary', type: 'button', onclick: () => { addToCart(product, quantity); productDialog.close(); } }, 'Add to cart')),
        h('ul', { class: 'pd-perks' },
          h('li', {}, icon('truck', 16), `Free shipping on orders over ${money(config.freeShippingFrom)}`),
          h('li', {}, icon('box', 16), 'Packed fresh, dispatched in 1–2 days')))));
  productDialog.showModal();
}

// ---- the product grid

const grid = h('div', { class: 'grid' });
const resultCount = h('span', { class: 'muted' });
const chips = h('div', { class: 'chips', role: 'tablist', 'aria-label': 'Categories' });
const shopSection = h('section', { class: 'wrap shop', id: 'shop' });

function productCard(product) {
  const add = h('button', { class: 'button add', type: 'button' }, icon('plus', 16), 'Add to cart');
  add.addEventListener('click', () => {
    addToCart(product);
    clear(add, icon('check', 16), 'Added');
    add.classList.add('added');
    setTimeout(() => { clear(add, icon('plus', 16), 'Add to cart'); add.classList.remove('added'); }, 1300);
  });
  const view = () => openProduct(product);
  return h('article', { class: 'product' },
    h('button', { class: 'product-art', type: 'button', 'aria-label': `View ${product.name}`, onclick: view },
      productArt(product), product.badge ? h('span', { class: 'badge' }, product.badge) : null, h('span', { class: 'quick' }, 'Quick view')),
    h('div', { class: 'product-body' },
      h('div', { class: 'product-meta' }, rating(product), h('span', { class: 'muted small' }, product.size)),
      h('h3', {}, h('button', { class: 'product-name', type: 'button', onclick: view }, product.name)),
      h('p', { class: 'muted small' }, product.detail),
      price(product),
      add));
}

function drawGrid() {
  const words = query.trim().toLowerCase();
  const shown = config.catalog.filter((p) => (filter === 'all' || p.category === filter)
    && (!words || `${p.name} ${p.detail} ${p.notes.join(' ')}`.toLowerCase().includes(words)));
  resultCount.textContent = `${shown.length} ${shown.length === 1 ? 'product' : 'products'}`;
  clear(chips, CATEGORIES.map(([key, label]) => h('button', {
    class: `chip ${filter === key ? 'active' : ''}`, type: 'button', role: 'tab', 'aria-selected': String(filter === key),
    onclick: () => { filter = key; drawGrid(); },
  }, label)));
  clear(grid, shown.length ? shown.map(productCard) : h('p', { class: 'no-results muted' }, `Nothing matches "${query.trim()}".`));
}

function showCategory(key) {
  filter = key;
  drawGrid();
  shopSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ---- the page

function heroArt() {
  const find = (id) => config.catalog.find((p) => p.id === id);
  const place = (product, x, y, scale) => (product ? svg('g', { transform: `translate(${x} ${y}) scale(${scale})` }, productObject(product)) : null);
  const leaf = (x, y, r, fill) => svg('path', { d: 'M0 0c0-14 9-22 24-24 0 15-7 24-21 24', fill, transform: `translate(${x} ${y}) rotate(${r})` });
  return svg('svg', { viewBox: '0 0 520 440', class: 'hero-art', role: 'img', 'aria-label': 'Tins of tea and a clay cup of chai' },
    svg('rect', { width: 520, height: 440, rx: 28, fill: '#e6efe9' }),
    svg('circle', { cx: 300, cy: 200, r: 170, fill: '#f3e9d2' }),
    svg('circle', { cx: 300, cy: 200, r: 120, fill: '#fbf5e8' }),
    ...[place(find('darjeeling-first-flush'), 14, 84, 1.2), place(find('masala-chai'), 128, 34, 1.6),
      place(find('kashmiri-kahwa'), 292, 104, 1.15), place(find('kulhad-set'), 186, 214, .95)].filter(Boolean),
    leaf(70, 380, -20, '#3f7a4b'), leaf(110, 395, 30, '#2c5a36'), leaf(430, 120, -60, '#3f7a4b'), leaf(455, 380, 10, '#2c5a36'));
}

function page() {
  const search = h('input', { type: 'search', class: 'search-input', placeholder: 'Search teas', 'aria-label': 'Search the shop' });
  search.addEventListener('input', () => { query = search.value; drawGrid(); });

  clear(shopSection,
    h('div', { class: 'section-head' },
      h('div', {}, h('p', { class: 'eyebrow' }, 'The shop'), h('h2', {}, 'Teas for every cup')),
      h('label', { class: 'search' }, icon('search', 17), search)),
    h('div', { class: 'toolbar' }, chips, resultCount),
    grid);

  clear(app,
    h('div', { class: 'announce' }, h('div', { class: 'wrap' }, icon('truck', 15), h('span', {}, `Free shipping on orders over ${money(config.freeShippingFrom)}`),
      h('span', { class: 'announce-sep' }), h('span', { class: 'announce-extra' }, 'Packed fresh every week'))),
    h('header', { class: 'nav' }, h('div', { class: 'wrap nav-inner' },
      logo(),
      h('nav', { class: 'nav-links', 'aria-label': 'Categories' },
        CATEGORIES.map(([key, label]) => h('a', { href: '#shop', onclick: (event) => { event.preventDefault(); showCategory(key); } }, label)),
        h('a', { href: '#story' }, 'Our story')),
      h('button', { class: 'cart-button', type: 'button', 'aria-label': 'Open the cart', onclick: openCart }, icon('bag', 22), badge))),
    h('main', {},
      h('section', { class: 'hero' }, h('div', { class: 'wrap hero-inner' },
        h('div', { class: 'hero-copy' },
          h('p', { class: 'eyebrow' }, 'Single-estate teas from Assam, Darjeeling and Kashmir'),
          h('h1', {}, 'Proper chai, from the garden to your cup.'),
          h('p', { class: 'lead' }, 'Whole-leaf teas and hand-ground masalas, bought directly from the gardens and packed the week they ship.'),
          h('div', { class: 'hero-actions' },
            h('button', { class: 'button primary large', type: 'button', onclick: () => showCategory('all') }, 'Shop the teas'),
            h('button', { class: 'button ghost large', type: 'button', onclick: () => openProduct(config.catalog.find((p) => p.id === 'chai-gift-box') || config.catalog[0]) }, icon('gift', 18), 'Gift a chai kit')),
          h('div', { class: 'hero-proof' },
            h('span', { class: 'stars', 'aria-hidden': 'true' }, [1, 2, 3, 4, 5].map(() => icon('star', 15))),
            h('span', {}, h('strong', {}, '4.8'), ' from 4,000+ tea lovers'))),
        h('div', { class: 'hero-visual' }, heroArt()))),
      h('section', { class: 'perks' }, h('div', { class: 'wrap perks-inner' },
        [['truck', 'Free shipping', `On orders over ${money(config.freeShippingFrom)}`], ['leaf', 'Garden fresh', 'Direct from the estates'],
          ['refresh', 'Easy returns', 'Within 7 days of delivery'], ['shield', 'Secure payments', 'UPI, cards, net banking, wallets']].map(([glyph, title, text]) =>
          h('div', { class: 'perk' }, h('span', { class: 'perk-icon' }, icon(glyph, 20)), h('div', {}, h('strong', {}, title), h('p', { class: 'muted small' }, text)))))),
      shopSection,
      h('section', { class: 'story', id: 'story' }, h('div', { class: 'wrap story-inner' },
        h('div', {},
          h('p', { class: 'eyebrow' }, 'Our story'),
          h('h2', {}, 'Started over a roadside cup of chai.'),
          h('p', {}, 'We buy from a handful of family-run gardens, taste every lot ourselves and blend our masalas in small batches. '
            + 'No warehouses full of old stock: what you order was packed this week.'),
          h('p', { class: 'muted' }, 'Questions about a tea? Write to us at ', h('a', { href: `mailto:${SUPPORT_EMAIL}` }, SUPPORT_EMAIL), '.')),
        h('ul', { class: 'story-facts' },
          [['12', 'partner gardens'], ['48 h', 'from packing to dispatch'], ['100%', 'plastic-free tins']].map(([figure, label]) =>
            h('li', {}, h('strong', {}, figure), h('span', { class: 'muted' }, label))))))),
    h('footer', { class: 'foot' }, h('div', { class: 'wrap' },
      h('div', { class: 'foot-grid' },
        h('div', { class: 'foot-brand' }, logo(), h('p', { class: 'muted small' }, 'Small-batch Indian teas, blended in Bengaluru and shipped across India.')),
        h('div', {}, h('h3', {}, 'Shop'), h('ul', {}, CATEGORIES.map(([key, label]) =>
          h('li', {}, h('a', { href: '#shop', onclick: (event) => { event.preventDefault(); showCategory(key); } }, label))))),
        h('div', {}, h('h3', {}, 'Help'), h('ul', {},
          h('li', {}, 'Delivery in 3–5 working days'), h('li', {}, 'Returns within 7 days'),
          h('li', {}, h('a', { href: `mailto:${SUPPORT_EMAIL}` }, SUPPORT_EMAIL)), h('li', {}, 'Mon–Sat, 10 am – 7 pm'))),
        h('div', {}, h('h3', {}, 'We accept'), paymentMarks(), h('p', { class: 'muted small foot-secure' }, icon('lock', 14), 'Payments secured by PayFlo'))),
      h('div', { class: 'foot-bottom muted small' },
        h('span', {}, `© ${new Date().getFullYear()} Chai & Co. All rights reserved.`),
        h('span', {}, 'Prices include GST')))),
    toastSlot, drawer, productDialog);
  drawGrid();
  drawCart();
}

try {
  config = await shop('GET', '/store/config');
  // Forget anything in the cart the shop no longer sells.
  for (const id of Object.keys(cart)) if (!config.catalog.some((p) => p.id === id)) delete cart[id];
  page();
} catch (failure) {
  clear(app, h('p', { class: 'fatal' }, failure.message));
}
