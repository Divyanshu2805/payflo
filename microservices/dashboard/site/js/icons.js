// The dashboard's icons: 24-unit outlines drawn with the current text colour. Built as SVG elements, so the page's
// policy (no inline markup, nothing remote) holds. Every icon is decorative: the control it sits in carries the label.

const SVG = 'http://www.w3.org/2000/svg';

const PATHS = {
  home: ['M3 10.5 12 3l9 7.5', 'M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5'],
  orders: ['M6 3h9l4 4v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M14 3v5h5', 'M9 13h6', 'M9 17h6'],
  payments: ['M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z', 'M2 10h20', 'M6 15h4'],
  refunds: ['M9 14 4 9l5-5', 'M4 9h10.5a5.5 5.5 0 0 1 0 11H11'],
  settlements: ['M3 21h18', 'M12 3 3 8v2h18V8l-9-5Z', 'M6 10v8', 'M10 10v8', 'M14 10v8', 'M18 10v8'],
  reports: ['M3 21h18', 'M7 17v-5', 'M12 17V7', 'M17 17v-8'],
  webhooks: ['M13 2 4 14h7l-1 8 9-12h-7l1-8Z'],
  developers: ['m8 7-5 5 5 5', 'm16 7 5 5-5 5', 'm14 4-4 16'],
  account: ['M16 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z', 'M4 21a8 8 0 0 1 16 0'],
  audit: ['M12 3 4 6v6c0 4.5 3.2 7.9 8 9 4.8-1.1 8-4.5 8-9V6l-8-3Z', 'm9 12 2 2 4-4'],
  merchants: ['M5 21V5a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v16', 'M3 21h18', 'M9 8h2', 'M13 8h2', 'M9 12h2', 'M13 12h2', 'M10 21v-4h4v4'],
  run: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'm10 8.5 5.5 3.5-5.5 3.5v-7Z'],
  plus: ['M12 5v14', 'M5 12h14'],
  copy: ['M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1Z', 'M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1'],
  external: ['M14 4h6v6', 'M20 4 10 14', 'M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z', 'm20 20-4-4'],
  sun: ['M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z', 'M12 2v2', 'M12 20v2', 'm4.9 4.9 1.4 1.4', 'm17.7 17.7 1.4 1.4', 'M2 12h2', 'M20 12h2', 'm4.9 19.1 1.4-1.4', 'm17.7 6.3 1.4-1.4'],
  moon: ['M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z'],
  logout: ['M9 21H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h4', 'm16 17 5-5-5-5', 'M21 12H9'],
  check: ['m5 12.5 4.5 4.5L19 7.5'],
  x: ['M6 6l12 12', 'M18 6 6 18'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 7v5l3 2'],
  alert: ['M12 9v4', 'M12 17h.01', 'M10.3 3.9 2.5 17.5a2 2 0 0 0 1.7 3h15.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 11v5', 'M12 8h.01'],
  right: ['m9 6 6 6-6 6'],
  left: ['m15 6-6 6 6 6'],
  terminal: ['m5 8 4 4-4 4', 'M12 16h7'],
  refresh: ['M20 11a8 8 0 0 0-14.9-3', 'M4 4v4h4', 'M4 13a8 8 0 0 0 14.9 3', 'M20 20v-4h-4'],
  empty: ['M4 13h4l1.5 3h5L16 13h4', 'M5.5 5h13L21 13v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5l2.5-8Z'],
};

export function icon(name, size = 16) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.75');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'icon');
  for (const d of PATHS[name] || []) {
    const path = document.createElementNS(SVG, 'path');
    path.setAttribute('d', d);
    svg.append(path);
  }
  return svg;
}

/** PayFlo's mark: two offset bars, money moving from one side to the other. */
export function logoMark(size = 26) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 28 28');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'logo-mark');
  const shapes = [['rect', { width: 28, height: 28, rx: 7, class: 'logo-bg' }],
    ['rect', { x: 6, y: 8.5, width: 12, height: 4.5, rx: 2.25, class: 'logo-bar' }],
    ['rect', { x: 10, y: 15, width: 12, height: 4.5, rx: 2.25, class: 'logo-bar dim' }]];
  for (const [tag, attrs] of shapes) {
    const el = document.createElementNS(SVG, tag);
    for (const [name, value] of Object.entries(attrs)) el.setAttribute(name, value);
    svg.append(el);
  }
  return svg;
}
