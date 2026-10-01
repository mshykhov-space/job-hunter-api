# Operations runbook

How to tell whether job matching is healthy, and what to do when an alert fires.

This exists because on 2026-07-21 the AI provider ran out of credit and matching failed silently for nine days. Nobody noticed, because there was nothing to notice with. Every check below answers a question that went unanswered then.

## Is it working right now

One query settles it. A healthy run processes everything it took, evaluates some of it with AI, and fails nothing.

```bash
kubectl logs -n job-hunter-api-prd deployment/job-hunter-api-prd --since=10m | grep "Matching complete" | tail -3
```

```
Matching complete: 210/210 processed (0 failed) - coldRejected=118 aiEvaluated=59 postAiRejected=19 saved=40 aiFailed=0 coldOnly=0
```

`aiFailed` above zero means the provider chain is failing. `aiEvaluated=0` with `aiFailed` high means every provider is down. `processed` short of the batch total means some groups were left unmatched deliberately, which is the correct response to a failure, not a bug.

If nothing appears at all, matching is not running. Check `MATCHING_INTERVAL_MS`: it was once set to 24 hours to pause matching deliberately.

## Where each signal lives

| Question | Where to look |
|---|---|
| Is matching succeeding | `Matching complete` log lines, above |
| Which provider served a request, and how it went | `jobhunter_ai_evaluations_total{provider,model,outcome}` |
| How many tokens the AI is burning | `gen_ai_client_token_usage_total{gen_ai_token_type}` |
| How far behind matching is | `jobhunter_matching_backlog` |
| Are scrapers delivering | `jobhunter_jobs_ingested_total{source}` |
| What a single request did end to end | VictoriaTraces, service `job-hunter-api`, span `chat <model>` |

Model labels in matching evaluation counters and timers come from the shared API
model catalogue, including `gpt-5.6-terra`. Unknown user-supplied model IDs are
collapsed to `other` to bound metric cardinality. The catalogue also supplies the
Settings model list; adding an entry does not change an existing provider chain
or the recommended default. Subscription token counters do not translate directly
to a percentage of the account's weekly Codex allowance.

Reaching the stores from a laptop:

```bash
# metrics (note port 8428, not 8429)
kubectl port-forward -n monitoring svc/vmsingle-victoria-metrics-k8s-stack 8428:8428
curl -sG --data-urlencode 'query=sum by (provider,outcome) (jobhunter_ai_evaluations_total)' localhost:8428/api/v1/query

# alert rule state, including what is pending versus firing
kubectl port-forward -n monitoring svc/vmalert-victoria-metrics-k8s-stack 8080:8080
curl -s localhost:8080/api/v1/rules

# what fired historically, which current state cannot tell you
curl -sG --data-urlencode 'query=ALERTS{alertname=~"JobHunter.*"}' localhost:8428/api/v1/query_range \
  --data-urlencode "start=$(($(date -u +%s)-43200))" --data-urlencode "end=$(date -u +%s)" --data-urlencode 'step=300'

# traces
kubectl port-forward -n monitoring svc/victoria-traces 10428:10428
curl -s localhost:10428/select/jaeger/api/services
```

Logs are already in VictoriaLogs with `traceId` and `spanId` on every line, so a log line and its trace are one query apart.

## When an alert fires

`severity: critical` reaches Pushover and Telegram. `severity: warning` reaches Telegram only.

**JobHunterAiAllProvidersDown** - every provider in the chain is failing. Read the exception: it names each provider tried and why each failed.

```bash
kubectl logs -n job-hunter-api-prd deployment/job-hunter-api-prd --since=15m | grep "All AI providers failed" | tail -1
```

Common causes, in the order they actually happen: the OpenAI key ran out of credit; the Codex proxy pod is down (`kubectl get pods -n cli-proxy-api-prd`); `AI_CODEX_BASE_URL` is wrong. On that last one, the base URL must be the host root **without** `/v1` - Spring AI appends `/v1/chat/completions` itself, and a `/v1` suffix yields a bare 404.

Nothing is lost while this is happening. Failed evaluations leave `matched_at` NULL and the jobs are retried; the scheduler backs off from one minute up to thirty so it does not hammer a dead provider.

**JobHunterAiQuotaExhausted** - a provider is out of credit. Either top up, or reorder the chain so a working provider is first. The chain is editable in Settings, AI Configuration.

**JobHunterNoJobsIngested** - no job from any source in six hours. This is about the scrapers, not the API: check the n8n workflows in `job-hunt-n8n-prd`. The rule suppresses itself when the API is not being scraped at all, so if it fires the API is up and the scrapers are not delivering.

**JobHunterCodexUnavailable** - the free subscription path is failing and traffic has moved to a paid or dead provider. The chain is covering, so this is a warning, but it is costing money or heading for a total outage.

**JobHunterMatchingBacklogGrowing** - more than 500 jobs waiting for an hour. Expected and harmless during a deliberate backlog restore. Otherwise matching is falling behind ingest.

## Routine operations

**Re-match a period.** Prefer authenticated `POST /jobs/rematch?since=<timestamp>`. The endpoint clamps the period to three days and resets both `matched_at` and `match_attempts`, so jobs that reached the retry cap become eligible again. Use the SQL form only when the API is unavailable.

```sql
UPDATE jobs SET matched_at = NULL, match_attempts = 0
WHERE created_at > now() - interval '3 days';
```

The queue drains at `jobhunter.matching.batch-size` jobs per run, 200 by default, once a minute.

**Reorder the provider chain.** Settings, AI Configuration. Leave a key field empty to keep the stored key; only a new provider needs one typed in.

**Check retention.** The purge deletes jobs whose posting has been gone for thirty days, that were evaluated, and that nobody matched or wrote an outreach message for. It refuses to run during a grace period measured from `flyway_schema_history.installed_on` for V25, the migration that added `jobs.last_seen_at` - the column only becomes trustworthy once the scrapers have re-seen every job after it. A restart no longer moves that deadline, so a crashlooping pod can no longer postpone the purge forever. The log line names the deadline it is waiting for:

```bash
kubectl logs -n job-hunter-api-prd deployment/job-hunter-api-prd --since=24h | grep -E "Retention (purge|anchor)"
```

**Verify a purge run.** `maxPerRun` caps a single run at 5000 jobs, so a backlog is cleared over several days rather than in one pass - a first run that deletes exactly 5000 is the cap doing its job, not a coincidence. Count what is eligible before and after and compare against the log line and the counter:

```bash
kubectl exec -n job-hunter-api-prd job-hunter-api-main-db-prd-cluster-1 -c postgres -- psql -U postgres -d jobhunter -tAc "
SELECT count(*) FROM jobs j
WHERE j.last_seen_at < now() - interval '30 days' AND j.matched_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_job_groups u WHERE u.group_id = j.group_id)
  AND NOT EXISTS (SELECT 1 FROM user_jobs uj WHERE uj.job_id = j.id);"

kubectl exec -n job-hunter-api-prd deployment/job-hunter-api-prd -- \
  wget -qO- localhost:8080/actuator/prometheus | grep jobhunter_jobs_purged_total
```

A deletion count far from `min(eligible, 5000)` means the predicate is not what it appears to be. Set `RETENTION_ENABLED=false` and re-derive it before letting another run go through.

## Things that look like bugs and are not

- **A group left unmatched after a failure.** Deliberate. Marking it processed is what lost 18 863 jobs in July.
- **A matched group with no `user_job_groups` row.** Normal, and the usual case: the cold filter rejected it before any AI call, which still sets `matched_at`. About four in five matched groups look like this, driven mostly by `remoteOnly` against an explicit `remote=false`. Its terminal outcome remains in `user_job_group_decisions`, which is the source for vacancy statistics.
- **A re-match that leaves the visible list the same size.** Expected. Clearing `matched_at` re-runs the whole pipeline, cold filter included, and a group that already has a `user_job_groups` row is updated in place rather than inserted. A re-match can move a score across the client's `minScore`, never add a group the cold filter still rejects. When the list looks too short, widen the date filter before suspecting the matcher.
- **`match_attempts` climbing on one group.** That group fails deterministically, most likely because its representative description is too long for the model. After the cap it leaves the queue so it cannot block everything behind it.
- **A counter missing from `/actuator/prometheus`.** Counters register on first use. A metric that has never fired has no series, which is also why alert expressions comparing to zero need `or vector(0)`.
- **`Failed to decrypt API key` warnings for the Codex row.** Codex needs no key, the stored value is empty, and decryption of an empty string fails harmlessly.
