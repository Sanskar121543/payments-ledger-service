'use strict';

const state = {
  key: sessionStorage.getItem('ledger.apiKey') || '',
  lastEventId: null,
  feed: [],
  stream: null,
  charts: {},
  accounts: [],
  lastConcurrent: null,
};

const $ = (id) => document.getElementById(id);

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === 'class') node.className = value;
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined) node.append(child instanceof Node ? child : String(child));
  }
  return node;
}

const money = (cents) => (cents < 0 ? '−' : '') + '$' + (Math.abs(cents) / 100)
  .toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const compactDollars = (dollars) => '$' + Intl.NumberFormat('en-US', { notation: 'compact' }).format(dollars);
const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function instanceBadge(id, prefix = '') {
  return el('span', { class: 'instance', dataset: { instance: id || '' } }, prefix + (id || '?'));
}

function setStatus(text) {
  $('stream-status').textContent = text;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'X-API-Key': state.key, ...(options.headers || {}) },
  });
  const servedBy = response.headers.get('X-Served-By');
  if (servedBy) $('served-by').replaceChildren('Last API call served by ', instanceBadge(servedBy));
  if (response.status === 401) {
    setStatus('API key rejected');
    throw new Error('unauthorized');
  }
  const body = await response.json().catch(() => null);
  return { status: response.status, body, servedBy };
}

/* ---------- invariants, totals, balances (polled every 3 s) ---------- */

async function refresh() {
  if (!state.key) return;
  try {
    const [invariants, accounts] = await Promise.all([api('/v1/ledger/invariants'), api('/v1/accounts')]);
    renderInvariants(invariants.body);
    state.accounts = accounts.body || [];
    renderAccounts(state.accounts);
  } catch (e) {
    if (e.message !== 'unauthorized') setStatus('API unreachable, retrying');
  }
}

function renderInvariants(report) {
  if (!report) return;
  $('invariant-sum').textContent = money(report.sumOfAllEntriesCents);
  const status = $('invariant-status');
  status.className = 'status ' + (report.ok ? 'good' : 'critical');
  status.replaceChildren(
    el('span', { class: 'icon', 'aria-hidden': 'true' }, report.ok ? '✓' : '✕'),
    report.ok ? 'Ledger balanced: every check passes' : 'Invariant broken');
  $('inv-mismatch').textContent = report.accountsWithCachedBalanceMismatch;
  $('inv-unbalanced').textContent = report.transfersWithUnbalancedEntries;
  $('inv-negative').textContent = report.customerAccountsBelowZero;
  $('inv-outbox').textContent = report.outboxUnpublished;
  $('stat-accounts').textContent = report.accounts.toLocaleString('en-US');
  $('stat-transfers').textContent = report.transfers.toLocaleString('en-US');
  $('stat-entries').textContent = report.entries.toLocaleString('en-US');
}

function renderAccounts(accounts) {
  const funding = accounts.find((a) => a.type === 'FUNDING');
  if (funding) $('stat-issued').textContent = money(-funding.balanceCents);
  const customers = accounts.filter((a) => a.type === 'CUSTOMER').slice(-12).reverse();
  const labels = customers.map((a) => `#${a.id} ${a.name}`);
  const values = customers.map((a) => a.balanceCents / 100);

  const chart = state.charts.balances;
  if (chart) {
    chart.data.labels = labels;
    chart.data.datasets[0].data = values;
    chart.update('none');
  } else if (window.Chart) {
    state.charts.balances = new Chart($('balances-chart'), {
      type: 'bar',
      data: { labels, datasets: [{ label: 'Balance', data: values, ...barStyle(cssVar('--accent')) }] },
      options: chartOptions({ horizontal: true, legend: false }),
    });
  }
  $('balances-table').replaceChildren(table(['Account', 'Name', 'Balance'],
    customers.map((a) => [`#${a.id}`, a.name, { num: money(a.balanceCents) }])));
}

/* ---------- chart styling (mark specs: thin bars, 4px rounded data end, hairline grid) ---------- */

function barStyle(color) {
  return { backgroundColor: color, borderRadius: 4, borderSkipped: 'start', maxBarThickness: 24 };
}

function chartOptions({ horizontal = false, legend = false } = {}) {
  const valueAxis = {
    grid: { color: cssVar('--grid'), lineWidth: 1 },
    border: { color: cssVar('--axis') },
    ticks: { color: cssVar('--muted'), callback: (v) => compactDollars(v) },
    beginAtZero: true,
  };
  const categoryAxis = { grid: { display: false }, border: { color: cssVar('--axis') }, ticks: { color: cssVar('--ink-2') } };
  return {
    indexAxis: horizontal ? 'y' : 'x',
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      legend: { display: legend, labels: { color: cssVar('--ink-2'), boxWidth: 12, boxHeight: 12 } },
      tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${money(Math.round(c.raw * 100))}` } },
    },
    scales: horizontal ? { x: valueAxis, y: categoryAxis } : { x: categoryAxis, y: valueAxis },
  };
}

function table(headers, rows) {
  return el('table', {},
    el('thead', {}, el('tr', {}, headers.map((h) => el('th', {}, h)))),
    el('tbody', {}, rows.map((row) => el('tr', {}, row.map((cell) => {
      if (cell && typeof cell === 'object' && !(cell instanceof Node) && 'num' in cell) {
        return el('td', { class: 'num' }, cell.num);
      }
      return el('td', {}, cell);
    })))));
}

/* ---------- live feed over SSE (fetch, because EventSource cannot send the API key header) ---------- */

async function connectStream() {
  if (state.stream) state.stream.abort();
  const controller = new AbortController();
  state.stream = controller;
  let watchdog;
  // No bytes for 45 s means the connection is dead (the server sends a heartbeat every 15 s).
  const resetWatchdog = () => {
    clearTimeout(watchdog);
    watchdog = setTimeout(() => controller.abort(), 45_000);
  };
  try {
    const headers = { 'X-API-Key': state.key };
    if (state.lastEventId) headers['Last-Event-ID'] = state.lastEventId;
    const response = await fetch('/v1/stream', { headers, signal: controller.signal });
    if (!response.ok) throw new Error(`stream returned ${response.status}`);
    setStatus(`Live · stream served by ${response.headers.get('X-Served-By') || '?'}`);
    resetWatchdog();
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      resetWatchdog();
      buffer += value.replace(/\r\n/g, '\n');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        handleEvent(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
      }
    }
  } catch (e) {
    // fall through to reconnect unless this stream was deliberately replaced
  } finally {
    clearTimeout(watchdog);
  }
  if (state.stream !== controller) return;
  setStatus('Reconnecting…');
  setTimeout(() => { if (state.stream === controller) connectStream(); }, 1000 + Math.random() * 4000);
}

function handleEvent(block) {
  let id = null;
  let event = 'message';
  const data = [];
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
    if (field === 'id') id = value;
    else if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  }
  if (event !== 'activity' || data.length === 0) return;
  if (id) state.lastEventId = id;
  addFeedItem(JSON.parse(data.join('\n')));
}

function addFeedItem(item) {
  if (state.feed.some((existing) => existing.id === item.id)) return;
  state.feed.unshift(item);
  state.feed.length = Math.min(state.feed.length, 60);
  const flags = [];
  if (item.largeAmount) flags.push(el('span', { class: 'flag' }, '▲ Large amount'));
  if (item.highVelocity) flags.push(el('span', { class: 'flag' }, '⚡ High velocity'));
  const row = el('li', { tabindex: '0', onclick: () => showTransfer(item.transferId),
      onkeydown: (e) => { if (e.key === 'Enter') showTransfer(item.transferId); } },
    el('span', { class: 'muted' }, `#${item.transferId}${item.kind === 'REVERSAL' ? ' ↺' : ''}`),
    el('span', {}, `account ${item.fromAccount} → ${item.toAccount}`),
    el('span', { class: 'feed-amount' }, money(item.amountCents)),
    el('div', { class: 'feed-meta' }, instanceBadge(item.servedBy, 'served by '),
      instanceBadge(item.consumedBy, 'consumed by '), flags));
  $('feed').prepend(row);
  while ($('feed').children.length > 60) $('feed').lastChild.remove();
  $('feed-count').textContent = `${state.feed.length} events`;
}

/* ---------- transfer detail: the two ledger entries ---------- */

async function showTransfer(id) {
  const { status, body } = await api(`/v1/transfers/${id}`);
  const panel = $('detail');
  panel.hidden = false;
  if (status !== 200) {
    $('detail-body').replaceChildren(el('p', {}, `Transfer ${id} not found`));
    return;
  }
  const sum = body.entries.reduce((total, e) => total + e.amountCents, 0);
  $('detail-title').textContent = `Transfer #${body.id} · ${body.kind} · ${body.status}`;
  $('detail-body').replaceChildren(
    el('div', { class: 'result-line' },
      el('span', {}, `${money(body.amountCents)} from account ${body.fromAccount} to account ${body.toAccount}`),
      instanceBadge(body.servedBy, 'served by '),
      body.reversesTransfer ? el('span', {}, `reverses #${body.reversesTransfer}`) : null,
      body.reversedBy ? el('span', {}, `reversed by #${body.reversedBy}`) : null),
    table(['Entry', 'Account', 'Direction', 'Amount'],
      body.entries.map((e) => [`#${e.id}`, `#${e.accountId}`, e.direction, { num: money(e.amountCents) }])),
    el('p', { class: 'status ' + (sum === 0 ? 'good' : 'critical') },
      el('span', { class: 'icon', 'aria-hidden': 'true' }, sum === 0 ? '✓' : '✕'),
      body.entries.length === 0 ? 'No entries: nothing moved (failed transfer)' : `Entries sum to ${money(sum)}`));
  panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

/* ---------- demo buttons ---------- */

async function runDemo(button, path, render) {
  button.disabled = true;
  button.textContent = 'Running…';
  try {
    const { status, body } = await api(path, { method: 'POST' });
    if (status !== 200) throw new Error(body?.detail || `HTTP ${status}`);
    render(body);
    refresh();
  } catch (e) {
    alertBox(button, e.message);
  } finally {
    button.disabled = false;
    button.textContent = 'Run again';
  }
}

function alertBox(button, message) {
  button.closest('.card').querySelector('.demo-result')
    .replaceChildren(el('p', { class: 'status critical' }, el('span', { class: 'icon' }, '✕'), message));
}

function renderRetry(report) {
  const replays = report.attempts.filter((a) => a.replayed).length;
  $('retry-result').replaceChildren(
    el('div', { class: 'result-line' },
      el('span', {}, 'Transfers created ', el('strong', {}, report.distinctTransfers)),
      el('span', {}, 'Replays of the stored response ', el('strong', {}, replays))),
    el('div', { class: 'result-line' }, 'Answered by ',
      Object.entries(report.byInstance).map(([id, n]) => instanceBadge(id, `${n}× `))),
    table(['#', 'Status', 'Transfer', 'Replayed', 'Served by', 'Latency'],
      report.attempts.map((a, i) => [i + 1, a.status, a.transferId ?? '—', a.replayed ? 'yes' : 'no',
        instanceBadge(a.servedBy), { num: `${a.latencyMillis} ms` }])),
    balancesTable(report.balances),
    invariantLine(report.invariants));
}

function renderConcurrent(report) {
  state.lastConcurrent = report;
  const completed = report.byStatus['201'] || 0;
  const failed = report.byStatus['422'] || 0;
  const other = report.requests - completed - failed;
  $('concurrent-result').replaceChildren(
    el('div', { class: 'result-line' },
      el('span', {}, 'Completed ', el('strong', {}, completed)),
      el('span', {}, 'Refused, insufficient funds ', el('strong', {}, failed)),
      other ? el('span', {}, 'Other ', el('strong', {}, other)) : null,
      el('span', {}, `p50 ${report.p50Millis} ms · p95 ${report.p95Millis} ms · ${report.durationMillis} ms total`)),
    el('p', { class: 'status ' + (report.totalConserved && report.noNegativeBalances ? 'good' : 'critical') },
      el('span', { class: 'icon', 'aria-hidden': 'true' }, report.totalConserved ? '✓' : '✕'),
      `Total ${money(report.totalBeforeCents)} before → ${money(report.totalAfterCents)} after` +
      (report.noNegativeBalances ? ', no negative balances' : ', NEGATIVE BALANCE')),
    invariantLine(report.invariants),
    balancesTable(report.balances));
  $('concurrent-charts').hidden = false;
  drawConcurrentCharts(report);
}

function drawConcurrentCharts(report) {
  if (!window.Chart || !report) return;
  state.charts.instances?.destroy();
  state.charts.beforeAfter?.destroy();
  const instances = Object.keys(report.byInstance);
  const instanceColors = instances.map((id) => cssVar(`--${id}`) || cssVar('--muted'));
  const countOptions = chartOptions({ legend: false });
  countOptions.scales.y.ticks.callback = (v) => v;
  countOptions.plugins.tooltip.callbacks.label = (c) => `${c.raw} requests`;
  countOptions.plugins.title = { display: true, text: 'Requests per instance', color: cssVar('--ink-2') };
  state.charts.instances = new Chart($('instance-chart'), {
    type: 'bar',
    data: { labels: instances, datasets: [{ label: 'Requests', data: instances.map((i) => report.byInstance[i]),
      ...barStyle(instanceColors) }] },
    options: countOptions,
  });
  const balanceOptions = chartOptions({ legend: true });
  balanceOptions.plugins.title = { display: true, text: 'Balances before and after', color: cssVar('--ink-2') };
  state.charts.beforeAfter = new Chart($('before-after-chart'), {
    type: 'bar',
    data: {
      labels: report.balances.map((b) => `#${b.accountId}`),
      datasets: [
        { label: 'Before', data: report.balances.map((b) => b.beforeCents / 100), ...barStyle(cssVar('--accent-soft')) },
        { label: 'After', data: report.balances.map((b) => b.afterCents / 100), ...barStyle(cssVar('--accent')) },
      ],
    },
    options: balanceOptions,
  });
}

function balancesTable(balances) {
  return table(['Account', 'Before', 'After'],
    balances.map((b) => [`#${b.accountId} ${b.name}`, { num: money(b.beforeCents) }, { num: money(b.afterCents) }]));
}

function invariantLine(report) {
  return el('p', { class: 'status ' + (report.ok ? 'good' : 'critical') },
    el('span', { class: 'icon', 'aria-hidden': 'true' }, report.ok ? '✓' : '✕'),
    `Ledger invariant: all entries sum to ${money(report.sumOfAllEntriesCents)}`);
}

/* ---------- theme and wiring ---------- */

function rebuildCharts() {
  state.charts.balances?.destroy();
  state.charts.balances = null;
  renderAccounts(state.accounts);
  if (state.lastConcurrent) drawConcurrentCharts(state.lastConcurrent);
}

function toggleTheme() {
  const root = document.documentElement;
  const dark = root.dataset.theme
    ? root.dataset.theme === 'dark'
    : window.matchMedia('(prefers-color-scheme: dark)').matches;
  root.dataset.theme = dark ? 'light' : 'dark';
  try { localStorage.setItem('ledger.theme', root.dataset.theme); } catch (e) { /* storage blocked */ }
  rebuildCharts();
}

function start() {
  try {
    const theme = localStorage.getItem('ledger.theme');
    if (theme) document.documentElement.dataset.theme = theme;
  } catch (e) { /* storage blocked */ }
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', rebuildCharts);
  $('theme-toggle').addEventListener('click', toggleTheme);
  $('api-key').value = state.key;
  $('key-form').addEventListener('submit', (e) => {
    e.preventDefault();
    state.key = $('api-key').value.trim();
    sessionStorage.setItem('ledger.apiKey', state.key);
    state.feed = [];
    state.lastEventId = null;
    $('feed').replaceChildren();
    refresh();
    connectStream();
  });
  $('detail-close').addEventListener('click', () => { $('detail').hidden = true; });
  document.querySelectorAll('[data-toggle-table]').forEach((button) => button.addEventListener('click', () => {
    const tableView = $(`${button.dataset.toggleTable}-table`);
    tableView.hidden = !tableView.hidden;
    button.textContent = tableView.hidden ? 'Show table' : 'Hide table';
  }));
  $('run-retry').addEventListener('click', (e) => runDemo(e.currentTarget, '/v1/demo/retry-same-request', renderRetry));
  $('run-concurrent').addEventListener('click',
    (e) => runDemo(e.currentTarget, '/v1/demo/concurrent-transfers', renderConcurrent));
  setInterval(refresh, 3000);
  if (state.key) {
    refresh();
    connectStream();
  }
}

start();
