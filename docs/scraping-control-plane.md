# Scraping control plane

The API owns durable scheduling and recovery state for the external scraper worker. The worker has no database credentials. It claims one source over HTTP, sends normalized jobs in bounded batches, checkpoints progress in the same transaction as ingest, and finishes or fails the run through a fenced lease.

The control plane starts with every source disabled. Enable only deployed worker adapters:

```sh
SCRAPING_ENABLED_SOURCES=linkedin,dou
```

Supported source IDs are `djinni`, `dou`, `euremotejobs`, `justjoinit`, `landingjobs`, `linkedin`, `nofluffjobs`, and `web3career`. The default cadence is 15 minutes, leases last 5 minutes, and every new run receives a fixed one-hour lookback through `since`, including the first run. `SCRAPING_LOOKBACK` configures that window. Retries keep the original run's `since`, criteria, and checkpoint. Terminal run metadata is retained for 30 days.

## Worker API

Mutation endpoints require `write:jobs`; status requires `read:jobs`.

| Endpoint | Result |
|---|---|
| `POST /scraping/sources/{source}/claim` | `200` with a run, criteria snapshot, checkpoint, and lease; `204` when disabled, not due, already leased, or criteria-less |
| `POST /scraping/runs/{runId}/heartbeat` | Extends the current lease by 5 minutes |
| `POST /scraping/runs/{runId}/batches` | Atomically ingests at most 250 jobs and persists the receipt, counters, and checkpoint |
| `POST /scraping/runs/{runId}/complete` | Marks a full traversal successful and schedules the source 15 minutes later |
| `POST /scraping/runs/{runId}/fail` | Records a bounded machine error code and schedules retry or terminal failure |
| `GET /scraping/status` | Returns all sources, persisted counters and timestamps, and the current lease metadata without its token |

A `batchId` is globally unique. Replaying the same ID with identical jobs, checkpoint, and fetched count returns the stored `acceptedCount` without ingesting again. Reusing it with different content returns `409`. Every job source must match the claimed run source. An ingest failure rolls back jobs, receipt, counters, and checkpoint together. The API reads and caps the whole batch request at 8 MiB without trusting `Content-Length`; larger bodies return `413 PAYLOAD_TOO_LARGE`. Workers must split by both the 250-job limit and serialized request bytes.

Lease expiry rotates the token and resumes the persisted checkpoint. The initial claim is attempt 1. Expired claims can be recovered through attempts 2 and 3; another expiry marks the run failed and schedules a fresh run after 15 minutes. Explicit failures retry the same run after 1 minute and 5 minutes, then fail terminally. Calls with an expired or replaced token return `409 SCRAPING_LEASE_LOST`; a disabled source returns `409 SCRAPING_SOURCE_DISABLED`. Other `409 CONFLICT` responses represent idempotency or state conflicts and must not be treated as lease loss. Invalid job field lengths and source mismatches return `400 VALIDATION_ERROR`. Completing an already successful run is idempotent only with the token that completed it.

## Metrics

All metrics carry one lowercase `source` label and read PostgreSQL state, so process restarts do not reset them:

- `jobhunter_scraping_source_enabled`
- `jobhunter_scraping_last_success_timestamp_seconds`
- `jobhunter_scraping_last_error_timestamp_seconds`
- `jobhunter_scraping_runs_started_total`
- `jobhunter_scraping_runs_succeeded_total`
- `jobhunter_scraping_runs_failed_total`
- `jobhunter_scraping_jobs_fetched_total`
- `jobhunter_scraping_jobs_accepted_total`

Timestamp gauges are `0` until the corresponding event occurs. Use source-specific freshness alerts based on the configured cadence and measured scraper duration. The control plane intentionally does not publish a guessed claim-duration threshold.

## Operational checks

Before enabling a source, deploy a worker version that implements the exact lease and idempotency contract. Verify `/scraping/status` shows the source enabled, then watch its first `runs_started_total`, terminal count, last-success timestamp, and accepted/fetched ratio. Disable the source by removing it from `SCRAPING_ENABLED_SOURCES` and restarting the API. Heartbeat, batch, and completion calls are then fenced with `409`; fail remains available to release the current attempt without consuming its retry budget. The active run and its checkpoint remain available if the source is enabled again.

Migration V32 terminates any active run created under the previous history-overlap contract with `LOOKBACK_CONTRACT_CHANGED`, preserving its checkpoint and counters as audit data and making the source immediately due. Keep sources disabled while deploying the API and worker, then enable them individually. The first claim creates a fresh run with the configured lookback; no old run is marked successful and no application data is reset.
