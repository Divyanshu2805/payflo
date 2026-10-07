import { api, query, newIdempotencyKey } from '../api.js';
import {
  h, clear, money, amountCell, dateTime, shortDate, shortId, idCell, badge, mono, copyable, json, panel, pageHeader,
  button, table, pagedList, details, hero, dialog, form, notice, errorBox, toast, toastError, confirmDialog, methodLabel, sleep,
} from '../ui.js';
import { openPayment, methodCell } from './payments.js';

const ORDER_STATUSES = ['CREATED', 'ATTEMPTED', 'PAID', 'CANCELLED', 'EXPIRED'];

export function orders(root) {
  const list = pagedList({
    load: (page, filters, size) => api('GET', '/v1/orders' + query({ status: filters.status, page, size })),
    tabs: { name: 'status', options: ORDER_STATUSES },
    columns: [
      { title: 'Amount', cell: (o) => amountCell(o.amount) },
      { title: 'Status', cell: (o) => badge(o.status) },
      { title: 'Receipt', cell: (o) => o.receipt || h('span', { class: 'muted' }, '–') },
      { title: 'Attempts', align: 'right', cell: (o) => o.attempts },
      { title: 'Order', cell: (o) => idCell(o.id) },
      { title: 'Created', cell: (o) => shortDate(o.createdAt) },
    ],
    onRow: (order) => openOrder(order.id, () => list.refresh()),
    emptyMessage: 'No orders here yet.',
  });
  clear(root,
    pageHeader('Orders', 'What a customer is paying for. An order is paid by one successful payment, after any number of failed attempts.',
      button('Refresh', () => list.refresh(), '', 'refresh'),
      button('Create order', () => openNewOrder((order) => { list.refresh(); openOrder(order.id, () => list.refresh()); }), 'primary', 'plus')),
    panel(null, null, list));
}

export function openNewOrder(onCreated) {
  const idempotencyKey = newIdempotencyKey();
  const modal = dialog('Create an order', form([
    { name: 'amount', label: 'Amount (₹)', type: 'number', required: true, value: '499', min: '0.01', max: '5000000', step: '0.01', inputmode: 'decimal' },
    { name: 'receipt', label: 'Receipt', placeholder: 'order-1001', maxlength: 100, hint: 'Your own reference for this order. Unique per merchant.' },
    { name: 'customerName', label: 'Customer name', value: 'Demo Buyer', maxlength: 200 },
    { name: 'customerEmail', label: 'Customer email', type: 'email', value: 'buyer@example.com', hint: 'The customer is found, or created, by merchant and email.' },
  ], async (v) => {
    const body = { amount: { amountUnits: Math.round(parseFloat(v.amount) * 100), currency: 'INR' } };
    if (v.receipt) body.receipt = v.receipt;
    if (v.customerEmail) body.customer = { email: v.customerEmail, name: v.customerName || undefined };
    // The key stays the same for this dialog, so pressing the button twice still creates one order.
    const order = await api('POST', '/v1/orders', body, { idempotencyKey });
    modal.close();
    toast(`Order for ${money(order.amount)} created`);
    onCreated(order);
  }, {
    submit: 'Create order',
    extra: h('p', { class: 'muted small' }, 'Sent with X-Idempotency-Key ', mono(idempotencyKey.slice(0, 8) + '…'), ', so a retry returns this order instead of making a second one.'),
  }));
}

export async function openOrder(orderId, onChange = () => {}) {
  const modal = dialog('Order', h('p', { class: 'loading' }, 'Loading…'), { drawer: true });

  async function load() {
    try {
      const [order, payments] = await Promise.all([
        api('GET', `/v1/orders/${orderId}`), api('GET', `/v1/orders/${orderId}/payments`)]);
      const payable = ['CREATED', 'ATTEMPTED'].includes(order.status);
      clear(modal.body,
        hero(money(order.amount), badge(order.status), [order.receipt ? `${order.receipt} · ` : '', dateTime(order.createdAt)]),
        payable ? h('div', { class: 'form-actions start' },
          button('Pay this order', () => openCheckout(order, () => { load(); onChange(); }), 'primary'),
          button('Cancel order', () => confirmDialog('Cancel this order?',
            'Only an unpaid order with no payment in flight can be cancelled.', 'Cancel order', async () => {
              await api('POST', `/v1/orders/${orderId}/cancel`);
              toast('Order cancelled');
              load();
              onChange();
            }))) : null,
        h('h3', {}, 'Payment attempts'),
        table([
          { title: 'Status', cell: (p) => badge(p.status) },
          { title: 'Method', cell: methodCell },
          { title: 'Decline reason', cell: (p) => (p.errorCode ? mono(p.errorCode) : h('span', { class: 'muted' }, '–')) },
          { title: 'Started', cell: (p) => shortDate(p.createdAt) },
        ], payments, (p) => openPayment(p.id, () => { load(); onChange(); }), 'No one has tried to pay this order yet.'),
        h('h3', {}, 'Details'),
        details([
          ['Order ID', copyable(order.id)],
          ['Amount', money(order.amount)],
          ['Receipt', order.receipt],
          ['Customer', order.customerId ? copyable(order.customerId) : '–'],
          ['Payment attempts', order.attempts],
          ['Created', dateTime(order.createdAt)],
          ['Expires', dateTime(order.expiresAt)],
          order.notes && Object.keys(order.notes).length ? ['Notes', json(order.notes)] : null,
        ]));
    } catch (error) {
      clear(modal.body, errorBox(error));
    }
  }

  load();
}

// ---- checkout: what a customer's "Pay now" does, with the mock acquirer's test values one click away

const METHODS = {
  UPI: {
    field: 'vpa', fieldLabel: 'UPI address (VPA)',
    presets: [['buyer@okaxis', 'Succeeds'], ['fail@okaxis', 'Rejected by the acquirer'],
      ['capturefail@okaxis', 'Capture refused']],
  },
  CARD: {
    presets: [['4111111111111111', 'Succeeds'], ['4000000000000002', 'Declined'],
      ['4000000000000069', 'Expired card'], ['4000000000000341', 'Capture refused']],
  },
  NETBANKING: {
    field: 'bank', fieldLabel: 'Bank code',
    presets: [['HDFC', 'Succeeds'], ['BANK_CODE_FAIL', 'Rejected by the bank'],
      ['BANK_CODE_CAPTURE_FAIL', 'Capture refused']],
  },
  WALLET: {
    field: 'wallet', fieldLabel: 'Wallet',
    presets: [['PAYTM', 'Succeeds'], ['wallet_fail', 'Rejected'],
      ['wallet_capture_fail', 'Capture refused']],
  },
};

const OUTCOMES = {
  'fail@okaxis': 'FAILED with UPI_REJECTED', 'capturefail@okaxis': 'Authorized, then the capture is refused',
  4000000000000002: 'FAILED with CARD_DECLINED', 4000000000000069: 'FAILED with CARD_EXPIRED', 4000000000000341: 'Authorized, then the capture is refused',
  BANK_CODE_FAIL: 'FAILED with BANK_REJECTED', BANK_CODE_CAPTURE_FAIL: 'Authorized, then the capture is refused',
  wallet_fail: 'FAILED with WALLET_REJECTED', wallet_capture_fail: 'Authorized, then the capture is refused',
};

const FINAL = ['CAPTURED', 'FAILED', 'AUTH_EXPIRED', 'CANCELLED', 'REFUNDED', 'SETTLED'];

function isSettled(payment) {
  return FINAL.includes(payment.status) || (payment.status === 'AUTHORIZED' && payment.errorCode);
}

export function openCheckout(order, onDone = () => {}) {
  let method = 'UPI';
  const content = h('div', {});
  const modal = dialog(`Pay ${money(order.amount)}`, content, { onClose: onDone });

  function renderForm() {
    const config = METHODS[method];
    const tabs = h('div', { class: 'segmented', role: 'tablist' }, Object.keys(METHODS).map((name) =>
      h('button', { type: 'button', role: 'tab', class: name === method ? 'active' : '', 'aria-selected': String(name === method),
        onclick: () => { method = name; renderForm(); } }, methodLabel(name))));

    const fields = method === 'CARD' ? [
      { name: 'pan', label: 'Card number', required: true, value: config.presets[0][0], inputmode: 'numeric', pattern: '[0-9]{13,19}', autocomplete: 'off' },
      { name: 'expiry', label: 'Expiry (MM/YY)', required: true, value: `12/${String(new Date().getFullYear() + 3).slice(2)}`, pattern: '(0[1-9]|1[0-2])/[0-9]{2}' },
      { name: 'cvv', label: 'CVV', required: true, value: '123', type: 'password', inputmode: 'numeric', pattern: '[0-9]{3,4}' },
      { name: 'cardHolderName', label: 'Name on card', required: true, value: 'Demo Buyer', minlength: 3 },
    ] : [{ name: 'detail', label: config.fieldLabel, required: true, value: config.presets[0][0], maxlength: 200 }];

    const paymentForm = form(fields, (values) => pay(values), { submit: `Pay ${money(order.amount)}`, block: true });
    const target = paymentForm.inputs[method === 'CARD' ? 'pan' : 'detail'];
    const presets = h('div', { class: 'presets' }, h('span', { class: 'muted small' }, 'Try a test value:'),
      config.presets.map(([value, label]) => h('button', { type: 'button', class: 'chip', title: value, onclick: () => { target.value = value; target.focus(); } }, label)));

    clear(content, tabs, presets, paymentForm,
      method === 'CARD' ? h('p', { class: 'muted small' },
        'The card goes to vault-service, which returns a token; the payment carries only the token. This form posts the number to the API directly, which is fine for test cards. A real checkout would use hosted fields.') : null);
  }

  async function pay(values) {
    const steps = [];
    const progress = h('ol', { class: 'steps' });
    const footer = h('div', {});
    const draw = () => clear(progress, steps.map((s) => h('li', { class: s.tone }, s.text)));
    const show = (text, tone = 'done') => { steps.push({ text, tone }); draw(); };
    const started = performance.now();
    const elapsed = () => `${((performance.now() - started) / 1000).toFixed(1)} s`;

    let methodDetails;
    if (method === 'CARD') {
      const [month, year] = values.expiry.split('/');
      const tokenized = await api('POST', '/v1/vault/tokenize', {
        pan: values.pan.replace(/\s/g, ''), cvv: values.cvv, expiryMonth: Number(month), expiryYear: 2000 + Number(year),
        cardHolderName: values.cardHolderName,
      });
      methodDetails = { token: tokenized.token };
      show(`Card tokenized by vault-service: ${tokenized.brand} ending ${tokenized.lastFour}`);
    } else {
      methodDetails = { [METHODS[method].field]: values.detail };
    }

    let payment = await api('POST', '/v1/payments', { orderId: order.id, method, methodDetails }, { idempotencyKey: newIdempotencyKey() });
    clear(content, progress, footer);
    show(`Payment ${shortId(payment.id)} recorded as ${payment.status.toLowerCase()}`);

    let last = payment.status;
    const deadline = Date.now() + 45000;
    // From here the form is gone, so a failure is shown in the dialog itself rather than thrown back to the form.
    try {
      while (!isSettled(payment) && Date.now() < deadline) {
        await sleep(1000);
        if (!content.isConnected) return;          // the dialog was closed; the payment carries on without us
        payment = await api('GET', `/v1/payments/${payment.id}`);
        if (payment.status !== last && !isSettled(payment)) show(`${payment.status.charAt(0)}${payment.status.slice(1).toLowerCase()} after ${elapsed()}`);
        last = payment.status;
      }
    } catch (error) {
      footer.append(errorBox(error));
    }

    if (payment.status === 'CAPTURED') {
      show(`Captured in ${elapsed()}. The order is paid, and a PAYMENT_STATUS_CHANGED webhook is on its way.`, 'good');
    } else if (payment.status === 'AUTHORIZED' && payment.errorCode) {
      show(`The bank refused the capture (${payment.errorCode}). The authorization is still held.`, 'bad');
      const again = button('Capture again', async () => {
        try {
          payment = await api('POST', `/v1/payments/${payment.id}/capture`);
          show(payment.status === 'CAPTURED' ? 'Captured on retry. The order is paid.' : `Refused again: ${payment.errorCode}`, payment.status === 'CAPTURED' ? 'good' : 'bad');
          if (payment.status === 'CAPTURED') again.remove();
        } catch (error) {
          toastError(error);
        }
      }, 'primary');
      footer.append(h('div', { class: 'form-actions start' }, again));
    } else if (isSettled(payment)) {
      show(`Failed: ${payment.errorCode || payment.status}. ${payment.errorDescription || ''} The order can be paid again.`, 'bad');
    } else {
      show('Still waiting for the bank. Look at the payment again in a moment.', 'wait');
    }
    // One primary action at a time: while a refused capture is waiting to be retried, that is it.
    const retryPending = payment.status === 'AUTHORIZED' && payment.errorCode;
    footer.append(h('div', { class: 'form-actions' },
      button('View payment', () => { modal.close(); openPayment(payment.id, onDone); }),
      button('Done', () => modal.close(), retryPending ? '' : 'primary')));
  }

  renderForm();
}

/** "Create payment" from the home page: an order, then its checkout. */
export function quickPayment(onDone) {
  openNewOrder((order) => openCheckout(order, onDone));
}

export function testValuesCard() {
  return panel('Test values', null,
    h('p', { class: 'muted' }, 'There is no real bank. These inputs make the simulated acquirer and bank answer a particular way; anything else is approved or declined by chance.'),
    table([
      { title: 'Method', cell: (r) => methodLabel(r[0]) },
      { title: 'Value', cell: (r) => copyable(r[1]) },
      { title: 'Outcome', cell: (r) => r[2] },
    ], Object.entries(METHODS).flatMap(([name, m]) => m.presets.slice(1).map(([value]) => [name, value, OUTCOMES[value]]))),
    notice('flat', 'The bank also declines some payments on its own: about 5% of UPI and wallet, 10% of card and 20% of net-banking payments fail with SIM_BANK_ERROR_CODE.'));
}
