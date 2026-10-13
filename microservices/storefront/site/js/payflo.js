// PayFlo's Payment Element: the payment form a merchant mounts in its own checkout, the way a payment SDK's embedded
// element works. It collects the method and its details; the shop's server makes the PayFlo calls, and
// confirmPayment asks it how the payment stands until the bank has answered.
import { h, clear, shop, sleep, icon, svg } from './dom.js';

const BANKS = [['HDFC', 'HDFC Bank'], ['ICICI', 'ICICI Bank'], ['SBI', 'State Bank of India'], ['AXIS', 'Axis Bank'], ['KOTAK', 'Kotak Mahindra Bank'],
  ['YES', 'Yes Bank'], ['PNB', 'Punjab National Bank'], ['BOB', 'Bank of Baroda'], ['IDFC', 'IDFC FIRST Bank'], ['INDUSIND', 'IndusInd Bank']];
const WALLETS = [['PAYTM', 'Paytm'], ['PHONEPE', 'PhonePe'], ['AMAZONPAY', 'Amazon Pay'], ['MOBIKWIK', 'MobiKwik'], ['FREECHARGE', 'Freecharge']];
const UPI_WINDOW_SECONDS = 300;
const POLL_MS = 900;
const WAIT_LIMIT_MS = 60000;

// ---- small pictures: card networks, UPI, the back of a card

function cardLogo(brand) {
  const frame = (...children) => svg('svg', { viewBox: '0 0 32 21', width: 28, height: 18, class: 'pfe-logo', 'aria-hidden': 'true' },
    svg('rect', { x: .5, y: .5, width: 31, height: 20, rx: 3, fill: '#fff', stroke: '#d9dde3' }), ...children);
  const word = (text, fill, size, style = 'normal') => svg('text', { x: 16, y: 13.6, 'text-anchor': 'middle', 'font-size': size, 'font-weight': 800,
    'font-style': style, fill, 'font-family': 'Arial, Helvetica, sans-serif' }, text);
  if (brand === 'VISA') return frame(word('VISA', '#1a1f71', 8, 'italic'));
  if (brand === 'MASTERCARD') {
    return frame(svg('circle', { cx: 13, cy: 10.5, r: 5.5, fill: '#eb001b' }), svg('circle', { cx: 19, cy: 10.5, r: 5.5, fill: '#f79e1b', 'fill-opacity': '.9' }));
  }
  if (brand === 'AMEX') return svg('svg', { viewBox: '0 0 32 21', width: 28, height: 18, class: 'pfe-logo', 'aria-hidden': 'true' },
    svg('rect', { width: 32, height: 21, rx: 3, fill: '#1f72cd' }), word('AMEX', '#fff', 7.5));
  return frame(word('RuPay', '#0b6e3a', 7.2, 'italic'));
}

function upiLogo(size = 20) {
  return svg('svg', { viewBox: '0 0 24 24', width: size, height: size, 'aria-hidden': 'true', class: 'icon' },
    svg('path', { d: 'M7 3.5 13 12l-6 8.5Z', fill: '#097939' }), svg('path', { d: 'M11.5 3.5 17.5 12l-6 8.5Z', fill: '#ed752e' }));
}

function cvcIcon() {
  return svg('svg', { viewBox: '0 0 32 21', width: 28, height: 18, class: 'pfe-logo', 'aria-hidden': 'true' },
    svg('rect', { x: .5, y: .5, width: 31, height: 20, rx: 3, fill: '#f6f8fa', stroke: '#d9dde3' }),
    svg('rect', { y: 4, width: 32, height: 4, fill: '#c7ccd4' }),
    svg('rect', { x: 17, y: 11, width: 11, height: 5, rx: 1.5, fill: '#fff', stroke: '#9aa3ad' }));
}

function brandOf(number) {
  if (/^4/.test(number)) return 'VISA';
  if (/^(5[1-5]|2[2-7])/.test(number)) return 'MASTERCARD';
  if (/^3[47]/.test(number)) return 'AMEX';
  if (/^(60|65|81|82|508)/.test(number)) return 'RUPAY';
  return null;
}

// ---- the element

/**
 * The form. `amount` is the formatted amount for the UPI request text, `cardholder` the name the card is charged
 * under. Returns the DOM node and the calls the checkout makes on it.
 */
export function createPaymentElement({ amount, cardholder }) {
  let method = 'CARD';
  const field = (id, attrs) => h('input', { id: `pfe-${id}`, autocomplete: 'off', spellcheck: 'false', ...attrs });
  const inputs = {
    number: field('number', { inputmode: 'numeric', autocomplete: 'cc-number', placeholder: '1234 1234 1234 1234', maxlength: 23 }),
    expiry: field('expiry', { inputmode: 'numeric', autocomplete: 'cc-exp', placeholder: 'MM / YY', maxlength: 7 }),
    cvc: field('cvc', { inputmode: 'numeric', autocomplete: 'cc-csc', placeholder: 'CVC', maxlength: 4 }),
    vpa: field('vpa', { placeholder: 'username@bank' }),
    bank: h('select', { id: 'pfe-bank' }, h('option', { value: '' }, 'Select your bank'), BANKS.map(([value, label]) => h('option', { value }, label))),
    wallet: h('select', { id: 'pfe-wallet' }, h('option', { value: '' }, 'Select a wallet'), WALLETS.map(([value, label]) => h('option', { value }, label))),
  };
  const errors = Object.fromEntries(Object.keys(inputs).map((name) => [name, h('p', { class: 'pfe-error', role: 'alert' })]));
  const brands = h('span', { class: 'pfe-brands' });
  const showBrands = (known) => clear(brands, (known ? [known] : ['VISA', 'MASTERCARD', 'AMEX', 'RUPAY']).map(cardLogo));
  showBrands(null);

  inputs.number.addEventListener('input', () => {
    const digits = inputs.number.value.replace(/\D/g, '').slice(0, 19);
    inputs.number.value = digits.replace(/(.{4})(?=.)/g, '$1 ');
    showBrands(brandOf(digits));
  });
  inputs.expiry.addEventListener('input', (event) => {
    const digits = inputs.expiry.value.replace(/\D/g, '').slice(0, 4);
    // Typing forward writes the separator; deleting is left alone, or the cursor would fight the backspace key.
    inputs.expiry.value = digits.length > 2 || (digits.length === 2 && event.inputType !== 'deleteContentBackward') ? `${digits.slice(0, 2)} / ${digits.slice(2)}` : digits;
  });
  inputs.cvc.addEventListener('input', () => { inputs.cvc.value = inputs.cvc.value.replace(/\D/g, ''); });
  for (const [name, input] of Object.entries(inputs)) {
    const reset = () => { errors[name].textContent = ''; input.removeAttribute('aria-invalid'); };
    input.addEventListener('input', reset);
    input.addEventListener('change', reset);
  }

  const label = (id, text) => h('label', { class: 'pfe-label', for: `pfe-${id}` }, text);
  const panels = {
    CARD: h('div', { class: 'pfe-fields' },
      h('div', {}, label('number', 'Card number'), h('div', { class: 'pfe-input-wrap' }, inputs.number, brands), errors.number),
      h('div', { class: 'pfe-row' },
        h('div', {}, label('expiry', 'Expiration date'), inputs.expiry, errors.expiry),
        h('div', {}, label('cvc', 'Security code'), h('div', { class: 'pfe-input-wrap' }, inputs.cvc, cvcIcon()), errors.cvc))),
    UPI: h('div', { class: 'pfe-fields' },
      h('div', {}, label('vpa', 'UPI ID'), inputs.vpa, errors.vpa),
      h('p', { class: 'pfe-note' }, 'You\'ll get a request in your UPI app to approve this payment.')),
    NETBANKING: h('div', { class: 'pfe-fields' },
      h('div', {}, label('bank', 'Bank'), h('div', { class: 'pfe-select' }, inputs.bank, icon('down', 16)), errors.bank),
      h('p', { class: 'pfe-note' }, 'Your bank will ask you to confirm the payment.')),
    WALLET: h('div', { class: 'pfe-fields' },
      h('div', {}, label('wallet', 'Wallet'), h('div', { class: 'pfe-select' }, inputs.wallet, icon('down', 16)), errors.wallet),
      h('p', { class: 'pfe-note' }, 'The amount is paid from your wallet balance.')),
  };

  const TABS = [['CARD', 'Card', () => icon('card', 20)], ['UPI', 'UPI', () => upiLogo(20)], ['NETBANKING', 'Netbanking', () => icon('bank', 20)], ['WALLET', 'Wallet', () => icon('wallet', 20)]];
  const tabs = {};
  const tabList = h('div', { class: 'pfe-tabs', role: 'tablist', 'aria-label': 'Payment method' }, TABS.map(([key, text, glyph]) =>
    (tabs[key] = h('button', { class: 'pfe-tab', type: 'button', role: 'tab', onclick: () => choose(key) }, glyph(), h('span', {}, text)))));
  const body = h('div', { class: 'pfe-body' });
  const element = h('div', { class: 'pfe' }, tabList, body);

  function choose(key) {
    method = key;
    for (const [name, tab] of Object.entries(tabs)) {
      tab.classList.toggle('selected', name === key);
      tab.setAttribute('aria-selected', String(name === key));
    }
    clear(body, panels[key]);
  }
  choose('CARD');

  function fieldError(name, message, focus = true) {
    if (!errors[name]) return false;
    errors[name].textContent = message;
    inputs[name].setAttribute('aria-invalid', 'true');
    if (focus) inputs[name].focus();
    return true;
  }

  /** The details for the shop's server, or null after marking what is missing. */
  function collect() {
    for (const error of Object.values(errors)) error.textContent = '';
    const problems = [];
    if (method === 'CARD') {
      const digits = inputs.number.value.replace(/\D/g, '');
      const month = +inputs.expiry.value.slice(0, 2);
      if (digits.length < 12) problems.push(['number', digits ? 'Your card number is incomplete.' : 'Enter your card number.']);
      if (!/^\d{2} \/ \d{2}$/.test(inputs.expiry.value)) problems.push(['expiry', 'Your card\'s expiration date is incomplete.']);
      else if (month < 1 || month > 12) problems.push(['expiry', 'Your card\'s expiration date is invalid.']);
      if (!/^\d{3,4}$/.test(inputs.cvc.value)) problems.push(['cvc', 'Your card\'s security code is incomplete.']);
    }
    if (method === 'UPI' && !/^[\w.-]+@[\w.-]+$/.test(inputs.vpa.value.trim())) problems.push(['vpa', 'Enter a valid UPI ID, like username@bank.']);
    if (method === 'NETBANKING' && !inputs.bank.value) problems.push(['bank', 'Select your bank.']);
    if (method === 'WALLET' && !inputs.wallet.value) problems.push(['wallet', 'Select a wallet.']);
    problems.forEach(([name, message], index) => fieldError(name, message, index === 0));
    if (problems.length) return null;
    if (method === 'CARD') return { method, card: { number: inputs.number.value, expiry: inputs.expiry.value, cvc: inputs.cvc.value, name: cardholder() } };
    if (method === 'UPI') return { method, vpa: inputs.vpa.value.trim() };
    if (method === 'NETBANKING') return { method, bank: inputs.bank.value };
    return { method, wallet: inputs.wallet.value };
  }

  function setDisabled(disabled) {
    for (const input of Object.values(inputs)) input.disabled = disabled;
    for (const tab of Object.values(tabs)) tab.disabled = disabled;
  }

  // ---- waiting for the bank, shown in place of the fields

  let timer = null;
  function waiting(status, paymentMethod = method) {
    if (paymentMethod !== method && tabs[paymentMethod]) choose(paymentMethod);
    const approved = status === 'AUTHORIZED' || status === 'CAPTURING';
    if (waiting.shown === `${method}:${approved}`) return;          // already saying this: don't restart the spinner
    waiting.shown = `${method}:${approved}`;
    clearInterval(timer);
    const bankName = BANKS.find(([value]) => value === inputs.bank.value)?.[1] || 'your bank';
    const walletName = WALLETS.find(([value]) => value === inputs.wallet.value)?.[1] || 'your wallet';
    const vpa = inputs.vpa.value.trim();
    const [title, text] = approved ? ['Approved. Completing your payment…', 'This takes a moment.']
      : method === 'UPI' ? ['Approve the payment in your UPI app', `We've sent a request for ${amount}${vpa ? ` to ${vpa}` : ''}. Open your UPI app and enter your PIN to approve it.`]
        : method === 'CARD' ? ['Confirming with your bank…', 'Your bank is verifying this payment.']
          : method === 'NETBANKING' ? [`Waiting for ${bankName}…`, 'Your bank is confirming the payment.']
            : [`Waiting for ${walletName}…`, 'Confirming the payment with your wallet.'];
    const countdown = h('p', { class: 'pfe-timer' });
    if (method === 'UPI' && !approved) {
      const started = waiting.started || (waiting.started = Date.now());
      const tick = () => {
        const left = Math.max(0, UPI_WINDOW_SECONDS - Math.floor((Date.now() - started) / 1000));
        countdown.textContent = `Request expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
      };
      tick();
      timer = setInterval(tick, 1000);
    }
    clear(body, h('div', { class: 'pfe-waiting', 'aria-live': 'polite' },
      h('span', { class: 'pfe-spinner' }),
      h('div', {}, h('strong', {}, title), h('p', {}, text), method === 'UPI' && !approved ? countdown : null)));
  }

  function stopWaiting() {
    clearInterval(timer);
    waiting.started = waiting.shown = null;
    choose(method);
  }

  return { element, collect, setDisabled, fieldError, waiting, stopWaiting, focus: () => tabs[method].focus() };
}

// ---- confirming

/** Starts the payment and waits for the bank. Resolves with the checkout once it is paid, failed, or still waiting. */
export async function confirmPayment(sessionId, details, onUpdate) {
  const state = await shop('POST', `/store/checkout/${sessionId}/pay`, details);
  return waitForPayment(sessionId, state, onUpdate);
}

export async function waitForPayment(sessionId, state, onUpdate) {
  const deadline = Date.now() + WAIT_LIMIT_MS;
  while (state.state === 'processing' && Date.now() < deadline) {
    onUpdate?.(state.payment);
    await sleep(POLL_MS);
    state = await shop('GET', `/store/checkout/${sessionId}`);
  }
  return state;
}
