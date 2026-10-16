// Load test: random transfers between 20 funded accounts, through nginx, at a constant arrival rate.
// Run it with scripts/load-test.sh, which records the hardware and writes results/k6/.
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const API_KEY = __ENV.API_KEY;
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = 20;
const START_BALANCE_CENTS = 1_000_000;

const completed = new Counter('transfers_completed');
const refused = new Counter('transfers_refused_insufficient_funds');
const unexpected = new Counter('transfers_unexpected_status');
const invariantOk = new Rate('ledger_invariant_ok');
const conserved = new Rate('money_conserved');

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    // Not measured: lets the JVMs JIT-compile and the connection pools fill after a fresh start.
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'transfer',
      rate: Math.max(1, Math.floor(RATE / 2)),
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 25,
      maxVUs: 150,
    },
    transfers: {
      executor: 'constant-arrival-rate',
      exec: 'transfer',
      startTime: '25s',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    // Pass/fail rules, not results: no unexpected status codes, and the ledger must still balance.
    transfers_unexpected_status: ['count==0'],
    ledger_invariant_ok: ['rate==1'],
    money_conserved: ['rate==1'],
    // Always true; listed only so k6 computes these metrics for the transfer requests alone,
    // excluding the setup and teardown calls.
    'http_req_duration{scenario:transfers}': ['max>=0'],
    'http_reqs{scenario:transfers}': ['count>=0'],
    'transfers_completed{scenario:transfers}': ['count>=0'],
    'transfers_refused_insufficient_funds{scenario:transfers}': ['count>=0'],
    'dropped_iterations{scenario:transfers}': ['count>=0'],
  },
};

const headers = (key) => ({
  'Content-Type': 'application/json',
  'X-API-Key': API_KEY,
  ...(key ? { 'Idempotency-Key': key } : {}),
});

export function setup() {
  if (!API_KEY) fail('API_KEY is not set');
  if (!__ENV.HARDWARE_NOTE) fail('HARDWARE_NOTE is not set: describe the machine in .env');
  const run = `k6-${Date.now()}`;
  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const created = http.post(`${BASE_URL}/v1/accounts`, JSON.stringify({ name: `${run}-${i}` }),
      { headers: headers(`${run}-account-${i}`), responseType: 'text' });
    if (created.status !== 201) fail(`could not create account: ${created.status}`);
    const id = created.json('id');
    const funded = http.post(`${BASE_URL}/v1/transfers`,
      JSON.stringify({ fromAccount: 1, toAccount: id, amountCents: START_BALANCE_CENTS }),
      { headers: headers(`${run}-fund-${i}`) });
    if (funded.status !== 201) fail(`could not fund account: ${funded.status}`);
    accounts.push(id);
  }
  return { run, accounts };
}

export function transfer(data) {
  const from = Math.floor(Math.random() * ACCOUNTS);
  const to = (from + 1 + Math.floor(Math.random() * (ACCOUNTS - 1))) % ACCOUNTS;
  const body = JSON.stringify({
    fromAccount: data.accounts[from],
    toAccount: data.accounts[to],
    amountCents: 100 + Math.floor(Math.random() * 20_000),
  });
  // iterationInTest restarts at 0 in every scenario, so the scenario name keeps keys unique.
  const key = `${data.run}-${exec.scenario.name}-${exec.scenario.iterationInTest}`;
  const response = http.post(`${BASE_URL}/v1/transfers`, body, {
    headers: headers(key),
    responseCallback: http.expectedStatuses(201, 422),
  });
  if (response.status === 201) completed.add(1);
  else if (response.status === 422) refused.add(1);
  else unexpected.add(1);
  check(response, { 'status is 201 or 422': (r) => r.status === 201 || r.status === 422 });
}

export function teardown(data) {
  const report = http.get(`${BASE_URL}/v1/ledger/invariants`, { headers: headers(), responseType: 'text' });
  invariantOk.add(report.status === 200 && report.json('ok') === true);
  let total = 0;
  for (const id of data.accounts) {
    total += http.get(`${BASE_URL}/v1/accounts/${id}`, { headers: headers(), responseType: 'text' })
      .json('balanceCents');
  }
  conserved.add(total === ACCOUNTS * START_BALANCE_CENTS);
}

const metric = (data, name, stat) => {
  const m = data.metrics[name];
  return m && m.values && m.values[stat] !== undefined ? m.values[stat] : 0;
};
const ms = (value) => `${value.toFixed(1)} ms`;

export function handleSummary(data) {
  const finishedAt = new Date().toISOString();
  const result = {
    finishedAt,
    gitCommit: __ENV.GIT_SHA || 'unknown',
    hardware: __ENV.HARDWARE_NOTE || 'not recorded',
    dockerHost: __ENV.DOCKER_INFO || 'not recorded',
    topology: __ENV.TOPOLOGY || 'not recorded',
    otherLoadOnMachine: __ENV.OTHER_LOAD || 'none recorded',
    scenario: { executor: 'constant-arrival-rate', ratePerSecond: RATE, duration: DURATION, accounts: ACCOUNTS },
    results: {
      transferRequests: metric(data, 'http_reqs{scenario:transfers}', 'count'),
      transfersCompleted: metric(data, 'transfers_completed{scenario:transfers}', 'count'),
      transfersRefusedInsufficientFunds: metric(data, 'transfers_refused_insufficient_funds{scenario:transfers}', 'count'),
      unexpectedStatusesIncludingWarmup: metric(data, 'transfers_unexpected_status', 'count'),
      droppedIterations: metric(data, 'dropped_iterations{scenario:transfers}', 'count'),
      latencyMs: {
        avg: metric(data, 'http_req_duration{scenario:transfers}', 'avg'),
        p50: metric(data, 'http_req_duration{scenario:transfers}', 'med'),
        p90: metric(data, 'http_req_duration{scenario:transfers}', 'p(90)'),
        p95: metric(data, 'http_req_duration{scenario:transfers}', 'p(95)'),
        p99: metric(data, 'http_req_duration{scenario:transfers}', 'p(99)'),
        max: metric(data, 'http_req_duration{scenario:transfers}', 'max'),
      },
      ledgerInvariantOk: metric(data, 'ledger_invariant_ok', 'rate') === 1,
      moneyConserved: metric(data, 'money_conserved', 'rate') === 1,
    },
  };
  const r = result.results;
  const markdown = [
    `| Metric | Value |`,
    `|---|---|`,
    `| Scenario | ${RATE} transfers/s offered for ${DURATION} (after a 20 s warm-up that is not counted), constant arrival rate, through nginx to 3 instances |`,
    `| Transfer requests sent | ${r.transferRequests} |`,
    `| Completed (201) / refused for insufficient funds (422) | ${r.transfersCompleted} / ${r.transfersRefusedInsufficientFunds} |`,
    `| Unexpected status codes (warm-up included) | ${r.unexpectedStatusesIncludingWarmup} |`,
    `| Dropped iterations (k6 could not keep up) | ${r.droppedIterations} |`,
    `| Latency p50 / p90 / p95 / p99 | ${ms(r.latencyMs.p50)} / ${ms(r.latencyMs.p90)} / ${ms(r.latencyMs.p95)} / ${ms(r.latencyMs.p99)} |`,
    `| Latency max | ${ms(r.latencyMs.max)} |`,
    `| Ledger invariant after the run | ${r.ledgerInvariantOk ? 'holds (all entries sum to 0)' : 'BROKEN'} |`,
    `| Money conserved across the 20 accounts | ${r.moneyConserved ? 'yes' : 'NO'} |`,
    `| Hardware | ${result.hardware} |`,
    `| Docker host | ${result.dockerHost} |`,
    `| Other load on the machine | ${result.otherLoadOnMachine} |`,
    `| Recorded | ${finishedAt}, commit ${result.gitCommit} |`,
  ].join('\n') + '\n';
  const stamp = finishedAt.replace(/[:.]/g, '-');
  return {
    stdout: `\n${markdown}\n`,
    [`/results/${stamp}.json`]: JSON.stringify({ ...result, k6: data }, null, 2),
    '/results/latest.json': JSON.stringify(result, null, 2),
    '/results/latest.md': markdown,
  };
}
