# Runbook

## Start and stop

```bash
cp .env.example .env                                   # once; local-only demo credentials
docker compose --profile full up -d --build --wait     # 3 instances + everything else
docker compose --profile lite up -d --build --wait     # 1 instance, no Grafana (lighter)
docker compose up -d                                   # only postgres + kafka, for ./mvnw spring-boot:run
docker compose --profile full down                     # stop, keep data
docker compose --profile full down -v                  # stop and delete all data (fresh ledger)
```

| Port (host) | Service | Change it with (`.env`) |
|---|---|---|
| 8088 | nginx → dashboard and API | `HTTP_PORT` |
| 3000 | Grafana | `GRAFANA_PORT` |
| 9091 | Prometheus | `PROMETHEUS_PORT` |
| 8090 | kafbat-ui | `KAFBAT_PORT` |
| 5432 | Postgres (localhost only) | `POSTGRES_PORT` |
| 9094 | Kafka (localhost only) | `KAFKA_PORT` |

## Is it healthy?

```bash
docker compose --profile full ps        # every app should say (healthy)
scripts/smoke.sh                        # accounts, transfer, retry, reversal, invariants, 3 instances seen
curl -s localhost:8088/v1/ledger/invariants -H "X-API-Key: $DEMO_API_KEY"   # "ok": true
```

**If `ok` is ever false:**
1. Stop sending traffic.
2. Look at which counter is non-zero.
3. Open `/v1/accounts/{id}/entries` for the affected accounts.

The ledger itself is append-only, so nothing has been overwritten.

## Logs

```bash
docker compose logs -f app1                       # JSON (ECS); every line has correlationId and the instance
docker compose logs nginx | grep <request-id>     # nginx logs request_id, upstream and timings as JSON
```

To follow one request across services, take `X-Correlation-Id` from the response. It equals nginx's `request_id` unless the client sent its own. Search for it in the nginx and app logs; Kafka messages carry it as a header too.

## Demo: kill an instance

```bash
docker compose kill app2             # SIGKILL, like a crash
scripts/smoke.sh                     # still passes: nginx routes around app2
docker compose --profile full up -d  # app2 comes back; nginx finds it again via DNS
```

Watch it in Grafana ("Requests per second by instance") and in kafbat-ui. The consumer group `activity-feed` rebalances its partitions onto the surviving instances.

## Load test

```bash
# Set HARDWARE_NOTE in .env first (CPU, RAM, OS, in double quotes).
scripts/load-test.sh 100 60s
```

- **Writes** `results/k6/<timestamp>.json`, `latest.json`, `latest.md`, and a `docker stats` snapshot.
- **Exits non-zero** on any status other than 201/422, a broken invariant, or money not conserved.
- **Updating the README:** paste `latest.md` between the `k6-results` markers; `ReadmeResultsSyncTest` checks they match.
- **For clean numbers,** stop other Docker workloads first. The script records how many other containers were running.

## Tests

```bash
docker compose --profile full down    # integration tests start their own containers; don't run both
./mvnw verify
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Bind for 0.0.0.0:8088 failed: port is already allocated` | Another program uses the port. Set `HTTP_PORT` (or the other `*_PORT`) in `.env` |
| `set POSTGRES_PASSWORD in .env` | `.env` is missing: `cp .env.example .env` |
| Containers exit with code 137, or `OOMKilled` | Docker is out of memory. Stop other stacks, use `--profile lite`, or give WSL2 more memory in `%UserProfile%\.wslconfig` (`[wsl2]` then `memory=10GB`), then run `wsl --shutdown` |
| Integration tests fail to start containers | Docker Desktop isn't running, or the compose stack is using the memory: `docker compose --profile full down` |
| `409 idempotency-key-reused` | The same Idempotency-Key was sent with a different body or path. Use a new key per distinct request |
| `409 idempotency-key-in-use` + `Retry-After: 1` | The original request with that key is still running. Retry with the same key |
| `503 lock-timeout` + `Retry-After: 1` | An account stayed locked for more than 2 s. Retry with the same key; nothing was written |
| Dashboard shows "API key rejected" | Paste `DEMO_API_KEY` from `.env`. The server checks it against `API_KEY_HASHES` |
| Grafana is slow to load right after start | It needs about 30 s and close to its 512 MB limit while it builds its search index |

## Rotate the API key

```bash
scripts/new-api-key.sh demo            # prints DEMO_API_KEY=... and API_KEY_HASHES=demo:...
# paste both into .env, then:
docker compose --profile full up -d    # apps restart with the new hash
```
