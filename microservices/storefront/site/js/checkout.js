// The shop's checkout: contact and delivery details, and PayFlo's Payment Element for the payment itself. It talks
// only to the shop's own server, which holds the API key and calls PayFlo.
import { h, clear, money, shop, icon, logo, payfloMark, orderSummary, workingDaysFrom } from './dom.js';
import { createPaymentElement, confirmPayment, waitForPayment } from './payflo.js';

const app = document.getElementById('app');
const sessionId = new URLSearchParams(location.search).get('session') || '';

function header() {
  return h('header', { class: 'checkout-head' }, h('div', { class: 'wrap checkout-head-inner' },
    logo(),
    h('span', { class: 'secure-note' }, icon('lock', 16), 'Secure checkout')));
}

function noticePage(title, text) {
  clear(app, header(), h('main', { class: 'notice-page' }, h('span', { class: 'notice-icon' }, icon('bag', 28)), h('h1', {}, title),
    h('p', { class: 'muted' }, text), h('a', { class: 'button primary', href: '/' }, 'Back to the shop')));
}

function field(label, input, hint, wide) {
  const error = h('p', { class: 'field-error', role: 'alert' });
  const wrap = h('div', { class: `field ${wide ? 'wide' : ''}` }, h('label', { class: 'label', for: input.id }, label), input, hint ? h('p', { class: 'hint' }, hint) : null, error);
  return { wrap, error, input };
}

function form(session, config) {
  const contact = session.contact || {};
  const address = session.address || {};
  const make = (id, attrs) => h('input', { id, name: id, ...attrs });
  const fields = {
    email: field('Email', make('email', { type: 'email', autocomplete: 'email', placeholder: 'you@example.com', value: contact.email, required: true })),
    phone: field('Mobile number', make('phone', { type: 'tel', inputmode: 'numeric', autocomplete: 'tel-national', placeholder: '98765 43210', maxlength: 11, value: contact.phone }),
      'For delivery updates from the courier'),
    name: field('Full name', make('name', { autocomplete: 'name', placeholder: 'First and last name', value: contact.name }), null, true),
    line1: field('Address', make('line1', { autocomplete: 'address-line1', placeholder: 'Flat, house no., building, street', value: address.line1 }), null, true),
    line2: field('Landmark or area (optional)', make('line2', { autocomplete: 'address-line2', placeholder: 'Near …, locality', value: address.line2 }), null, true),
    city: field('City', make('city', { autocomplete: 'address-level2', value: address.city })),
    state: field('State', h('select', { id: 'state', name: 'state', autocomplete: 'address-level1' },
      h('option', { value: '' }, 'Select'), config.states.map((state) => h('option', { value: state, selected: state === address.state }, state)))),
    pin: field('PIN code', make('pin', { inputmode: 'numeric', autocomplete: 'postal-code', placeholder: '560001', maxlength: 6, value: address.pin })),
  };
  fields.phone.input.addEventListener('input', () => {
    const digits = fields.phone.input.value.replace(/\D/g, '').slice(0, 10);
    fields.phone.input.value = digits.length > 5 ? `${digits.slice(0, 5)} ${digits.slice(5)}` : digits;
  });
  fields.pin.input.addEventListener('input', () => { fields.pin.input.value = fields.pin.input.value.replace(/\D/g, '').slice(0, 6); });
  for (const { input, error } of Object.values(fields)) {
    input.addEventListener('input', () => { error.textContent = ''; input.removeAttribute('aria-invalid'); });
  }

  // The same checks the server makes, so most mistakes are caught before anything is sent.
  function check() {
    const value = (name) => fields[name].input.value.trim();
    const rules = [
      ['email', /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value('email')), 'Enter a valid email address.'],
      ['phone', /^[6-9][0-9]{9}$/.test(value('phone').replace(/\D/g, '')), 'Enter a 10-digit mobile number.'],
      ['name', value('name').length >= 2, 'Enter your full name.'],
      ['line1', value('line1').length > 0, 'Enter your address.'],
      ['city', value('city').length > 0, 'Enter your city.'],
      ['state', value('state').length > 0, 'Choose your state.'],
      ['pin', /^[1-9][0-9]{5}$/.test(value('pin')), 'Enter a 6-digit PIN code.'],
    ];
    const failed = rules.filter(([, passed]) => !passed);
    for (const [name, , message] of failed) showError(name, message, false);
    if (failed.length) fields[failed[0][0]].input.focus();
    return !failed.length;
  }

  function showError(name, message, focus = true) {
    const target = fields[name];
    if (!target) return false;
    target.error.textContent = message;
    target.input.setAttribute('aria-invalid', 'true');
    if (focus) target.input.focus();
    return true;
  }

  const total = money(session.amountUnits, session.currency);
  const payment = createPaymentElement({ amount: total, cardholder: () => fields.name.input.value.trim() });
  const formError = h('div', { role: 'alert' });
  const button = h('button', { class: 'button primary block large pay-button', type: 'submit' });
  let busy = false;
  let pending = false;          // the bank hasn't answered in time: the button checks again instead of paying again

  function setButton(state) {
    button.classList.toggle('paid', state === 'paid');
    if (state === 'busy') clear(button, h('span', { class: 'spinner' }), h('span', {}, 'Processing…'));
    else if (state === 'paid') clear(button, icon('check', 18), h('span', {}, 'Payment successful'));
    else if (state === 'pending') clear(button, icon('refresh', 16), h('span', {}, 'Check payment status'));
    else clear(button, icon('lock', 16), h('span', {}, `Pay ${total}`));
  }

  function setBusy(value) {
    busy = value;
    button.disabled = value;
    for (const { input } of Object.values(fields)) input.disabled = value;
    payment.setDisabled(value);
    if (value) setButton('busy');
  }

  function notice(kind, message) {
    clear(formError, h('div', { class: kind }, icon(kind === 'alert' ? 'alert' : 'clock', 18), h('span', {}, message)));
  }

  async function finish(state) {
    if (state.state === 'paid') {
      setButton('paid');
      await new Promise((resolve) => setTimeout(resolve, 900));
      location.replace(`/success.html?session=${encodeURIComponent(sessionId)}`);
      return;
    }
    payment.stopWaiting();
    setBusy(false);
    if (state.state === 'failed') {
      setButton('idle');
      notice('alert', state.message);
      payment.focus();
    } else {
      pending = true;
      setButton('pending');
      notice('wait-notice', 'Your bank is taking longer than usual. Please don\'t pay again: we\'ll confirm your order as soon as the bank answers.');
    }
  }

  async function resume(state) {
    setBusy(true);
    try {
      await finish(await waitForPayment(sessionId, state, (latest) => payment.waiting(latest?.status, latest?.method)));
    } catch (failure) {
      payment.stopWaiting();
      setBusy(false);
      setButton(pending ? 'pending' : 'idle');
      notice('alert', failure.message);
    }
  }

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    clear(formError);
    if (pending) return resume(await shop('GET', `/store/checkout/${sessionId}`));
    const details = payment.collect();
    if (!check() || !details) return;
    setBusy(true);
    try {
      const value = (name) => fields[name].input.value.trim();
      await shop('POST', `/store/checkout/${sessionId}/details`, {
        email: value('email'), phone: value('phone'), name: value('name'),
        address: { line1: value('line1'), line2: value('line2'), city: value('city'), state: value('state'), pin: value('pin') },
      }).catch((failure) => { if (failure.code !== 'PAYMENT_STARTED') throw failure; });
      await finish(await confirmPayment(sessionId, details, (latest) => payment.waiting(latest?.status)));
    } catch (failure) {
      payment.stopWaiting();
      setBusy(false);
      setButton('idle');
      if (!showError(failure.field, failure.message) && !payment.fieldError(failure.field, failure.message)) notice('alert', failure.message);
    }
  }

  setButton('idle');
  const shipBy = workingDaysFrom(Date.now(), 3);
  const shipTo = workingDaysFrom(Date.now(), 5);
  const formEl = h('form', { class: 'checkout-form', novalidate: true, onsubmit: submit },
    h('section', { class: 'form-section' },
      h('h2', {}, 'Contact'),
      h('div', { class: 'field-grid' }, fields.email.wrap, fields.phone.wrap)),
    h('section', { class: 'form-section' },
      h('h2', {}, 'Delivery address'),
      h('div', { class: 'field-grid' }, fields.name.wrap, fields.line1.wrap, fields.line2.wrap,
        h('div', { class: 'field-row wide' }, fields.city.wrap, fields.state.wrap, fields.pin.wrap))),
    h('section', { class: 'form-section' },
      h('h2', {}, 'Delivery method'),
      h('div', { class: 'option selected' },
        h('span', { class: 'radio' }),
        h('div', {}, h('strong', {}, 'Standard delivery'), h('div', { class: 'muted small' }, `3–5 working days · arrives ${shipBy} – ${shipTo}`)),
        h('strong', {}, session.shippingUnits ? money(session.shippingUnits, session.currency) : h('span', { class: 'free' }, 'Free')))),
    h('section', { class: 'form-section' },
      h('h2', {}, 'Payment'),
      h('p', { class: 'muted small section-note' }, icon('lock', 14), 'All transactions are secure and encrypted.'),
      payment.element),
    formError, button,
    h('p', { class: 'powered-by' }, 'Powered by', payfloMark(16), h('strong', {}, 'PayFlo')),
    h('p', { class: 'fine-print muted small' }, 'By placing this order you agree to our terms of sale. Returns are accepted within 7 days of delivery.'));

  return { formEl, resume };
}

try {
  const [session, config] = await Promise.all([shop('GET', `/store/checkout/${sessionId}`), shop('GET', '/store/config')]);
  if (session.state === 'paid') {
    location.replace(`/success.html?session=${encodeURIComponent(sessionId)}`);
  } else {
    const { formEl, resume } = form(session, config);
    const total = money(session.amountUnits, session.currency);
    clear(app, header(), h('main', { class: 'checkout' },
      h('details', { class: 'summary-toggle' },
        h('summary', {}, icon('bag', 18), h('span', {}, 'Order summary'), icon('down', 16), h('strong', {}, total)),
        orderSummary(session)),
      h('div', { class: 'wrap checkout-grid' },
        formEl,
        h('aside', { class: 'checkout-summary', 'aria-label': 'Order summary' }, h('h2', {}, 'Order summary'), orderSummary(session)))));
    // Back on the page while the bank is still answering: wait for it here.
    if (session.state === 'processing') resume(session);
  }
} catch (failure) {
  noticePage(failure.status === 404 ? 'This checkout has expired' : 'The checkout could not be opened', failure.message);
}
