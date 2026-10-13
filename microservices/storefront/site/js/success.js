// The shop's order confirmation. The order is paid when this page opens; "Payment confirmed" in the tracker ticks
// when PayFlo's signed webhook reaches the shop's server, a moment after the payment, so the page waits for it.
import { h, clear, money, shop, sleep, icon, logo, orderSummary, workingDaysFrom, SUPPORT_EMAIL } from './dom.js';

const app = document.getElementById('app');
const sessionId = new URLSearchParams(location.search).get('session') || '';

function header() {
  return h('header', { class: 'checkout-head' }, h('div', { class: 'wrap checkout-head-inner' },
    logo(),
    h('a', { class: 'link-strong', href: '/' }, 'Continue shopping', icon('chevron', 16))));
}

function time(value) {
  return new Date(value).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' });
}

function tracker(session, placedAt) {
  // Without a webhook configured the shop can only go by the API, which already says the payment is captured.
  const confirmed = session.webhook || !session.webhookExpected;
  let confirmedAt = 'Confirmed';
  if (session.webhook) {
    const [hours, minutes] = session.webhook.receivedAt.split(':');
    confirmedAt = `Confirmed at ${time(new Date().setHours(+hours, +minutes))}`;
  }
  const stages = [
    ['Order placed', time(placedAt), 'done'],
    ['Payment confirmed', confirmed ? confirmedAt : 'Confirming…', confirmed ? 'done' : 'active'],
    ['Packed', 'Within 1–2 days', ''],
    ['Shipped', 'Tracking link by email', ''],
    ['Delivered', `By ${workingDaysFrom(placedAt, 5)}`, ''],
  ];
  return h('ol', { class: 'tracker' }, stages.map(([title, detail, state]) => h('li', { class: state },
    h('span', { class: 'tracker-dot' }, state === 'done' ? icon('check', 14) : null),
    h('strong', {}, title), h('span', { class: 'muted small' }, detail))));
}

function draw(session) {
  const placedAt = session.payment?.capturedAt ? new Date(session.payment.capturedAt) : new Date();
  const contact = session.contact || {};
  const address = session.address || {};
  const firstName = (contact.name || '').split(' ')[0];
  clear(app, header(), h('main', { class: 'wrap confirm-grid' },
    h('div', { class: 'confirm-main' },
      h('div', { class: 'confirm-hero' },
        h('span', { class: 'confirm-tick' }, icon('check', 28)),
        h('div', {},
          h('p', { class: 'muted' }, `Order ${session.receipt}`),
          h('h1', {}, firstName ? `Thank you, ${firstName}!` : 'Thank you for your order!'))),
      h('section', { class: 'card' },
        h('h2', {}, 'Your order is confirmed'),
        h('p', { class: 'muted' }, 'We\'ve received your order and are getting it ready. You\'ll get an email at ',
          h('strong', {}, contact.email || 'your address'), ' when it ships.'),
        tracker(session, placedAt)),
      h('section', { class: 'card' },
        h('h2', {}, 'Order details'),
        h('div', { class: 'details-grid' },
          h('div', {}, h('h3', {}, icon('mail', 16), 'Contact'), h('p', {}, contact.name), h('p', { class: 'muted' }, contact.email),
            contact.phone ? h('p', { class: 'muted' }, `+91 ${contact.phone.slice(0, 5)} ${contact.phone.slice(5)}`) : null),
          h('div', {}, h('h3', {}, icon('pin', 16), 'Shipping address'), h('p', {}, contact.name), h('p', { class: 'muted' }, address.line1),
            address.line2 ? h('p', { class: 'muted' }, address.line2) : null, h('p', { class: 'muted' }, `${address.city}, ${address.state} ${address.pin}`)),
          h('div', {}, h('h3', {}, icon('card', 16), 'Payment'), h('p', {}, session.paidWith || 'Paid'),
            h('p', { class: 'muted' }, `${money(session.amountUnits, session.currency)} · ${placedAt.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}`)),
          h('div', {}, h('h3', {}, icon('truck', 16), 'Delivery'), h('p', {}, 'Standard delivery'),
            h('p', { class: 'muted' }, `${workingDaysFrom(placedAt, 3)} – ${workingDaysFrom(placedAt, 5)}`)))),
      h('div', { class: 'confirm-foot' },
        h('p', { class: 'muted' }, 'Need help? Write to ', h('a', { href: `mailto:${SUPPORT_EMAIL}` }, SUPPORT_EMAIL), ' and quote ', h('strong', {}, session.receipt), '.'),
        h('a', { class: 'button primary', href: '/' }, 'Continue shopping'))),
    h('aside', { class: 'checkout-summary', 'aria-label': 'Order summary' }, h('h2', {}, 'Order summary'), orderSummary(session))));
}

try {
  let session = await shop('GET', `/store/checkout/${sessionId}`);
  if (session.state !== 'paid') {
    location.replace(`/checkout.html?session=${encodeURIComponent(sessionId)}`);
  } else {
    try {
      sessionStorage.removeItem('chai.basket');
    } catch {
      // Nothing to empty in a private window.
    }
    draw(session);
    // The webhook usually lands within a couple of seconds of the capture.
    for (let i = 0; i < 60 && session.webhookExpected && !session.webhook; i++) {
      await sleep(1500);
      session = await shop('GET', `/store/checkout/${sessionId}`);
      if (session.webhook) draw(session);
    }
  }
} catch (failure) {
  clear(app, header(), h('main', { class: 'notice-page' }, h('h1', {}, 'We couldn\'t find this order'), h('p', { class: 'muted' }, failure.message),
    h('a', { class: 'button primary', href: '/' }, 'Back to the shop')));
}
