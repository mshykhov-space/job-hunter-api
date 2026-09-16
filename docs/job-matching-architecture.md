# Job Matching Architecture

How scraped jobs become per-user scored matches.

## Pipeline

```
n8n scrapers ──POST /jobs/ingest──▶ JobEntity (dedup by URL)
                                        │  grouped via JobGroupKeyComputer
                                        ▼
                                  JobGroupEntity (title, company, categories)
                                        │
                     JobMatchingScheduler (@Scheduled, jobhunter.matching.interval-ms, default 60s)
                     picks jobs WHERE matched_at IS NULL AND match_attempts < max-attempts
                     ordered by created_at, limited to batch-size, expanded to whole groups
                                        │  per group × per user
                                        ▼
                  STAGE 1: ColdFilterChain (deterministic, free)
                  source → remote → excludedKeywords → excludedTitleKeywords
                  → excludedCompanies → categories
                                        │ passed
                                        ▼
                  STAGE 2: JobRelevanceEvaluator (AI, per-user BYOK client)
                  representative job = longest description in group
                  prompt: job fields + candidate profile (about) + target
                  categories + custom instructions
                  output: { score 0-100, reasoning, inferredRemote }
                                        │
                                        ▼
                  UserJobGroupEntity (status=NEW, aiRelevanceScore, aiReasoning)
                                        │
                  UserJobGroupDecisionEntity (durable terminal outcome history)
                                        │
                                        ▼
                  UI/API: filter by minScore + status; POST rematch resets
                  matched_at + match_attempts (capped at 3 days back)
```

## Key Classes

| Class | Role |
|-------|------|
| `application/matching/JobMatchingService` | Orchestrates groups × users; concurrency via semaphore (`jobhunter.ai.matching.concurrency`, default 10) |
| `application/matching/ColdFilterChain` | Deterministic pre-filters — reject before AI spend |
| `application/matching/ColdFilterRetroService` | Handles `PreferenceChangedEvent` after commit on a bounded single-worker executor, re-runs the cold filter over the user's NEW groups in fixed-size chunks, and deletes the ones that stopped passing |
| `application/ai/JobRelevanceEvaluator` | Scoring system prompt + Spring AI `.entity()` structured output |
| `application/ai/ChatClientFactory` | Per-user OpenAI-compatible client; reasoning models get `reasoning_effort`, others `temperature` (per `AiUseCase`). Responses are bounded per use case; NVIDIA disables chat-template thinking for predictable latency |
| `application/ai/UserAiProviderEntity` | BYOK: ordered per-user AI provider chain row - provider, priority, model id, encrypted API key (optional for keyless providers) |

## Behaviour Notes

- **Group-level scoring** — one AI call per (group, user); representative = job with the longest description.
- **Users without AI settings** get `aiRelevanceScore=0` with a cold-only reasoning marker.
- **remoteOnly enforced twice** — cold filter rejects explicit `remote=false`; post-AI check rejects `inferredRemote=false`. AI backfills `job.remote` when null.
- **Terminal decision history** - every completed per-user evaluation writes `user_job_group_decisions`, preserving the first group-created `vacancy_seen_at`. Outcomes distinguish cold rejection, post-AI remote rejection, AI scoring, cold-only evaluation, and unknowable legacy rejection. AI failures write no decision and remain retryable. Decision writes complete before `matched_at` is set.
- **Cold rejection still marks the group matched** - a group the filter drops gets `matched_at` set and no `UserJobGroupEntity`, which is what keeps it out of the retry queue. Around four in five matched groups are cold-rejected, so a count of matched groups and a count of user rows are not comparable numbers.
- **Failure handling** — group failure is logged and `matched_at` stays NULL, so the next cycle retries; a single AI failure skips that user only. A group's `match_attempts` only increments when the same run had at least one successful AI evaluation elsewhere - a provider-wide outage leaves every counter untouched, while a group that deterministically fails against a live provider (e.g. context-length or content-filter rejection) is retried up to `jobhunter.matching.max-attempts` (default 5) before dropping out of the query window.
- **Scheduler backoff** treats an `AI_UNAVAILABLE` outcome and an unexpected exception from the matching run the same way, applying the same exponential backoff (`jobhunter.matching.backoff-initial`, doubling, capped at `backoff-max`); a broken per-user AI client is caught and excluded rather than aborting the whole run.
- **No server-side score threshold** — every passed group is saved with its score; filtering by score is a client concern (`minScore` query param).
- **Retro-clean on preference change** — saving search/matching preferences schedules background cleanup and returns without waiting for historical scanning. The worker snapshots NEW group IDs, loads group/job graphs in fixed-size chunks, records `COLD_REJECTED` decisions for rows that stop passing, then guard-deletes only rows that are still NEW for the same user. Its bounded single-worker queue drops the oldest queued cleanup when saturated so preference writes remain independent from maintenance load; each drop is logged and counted in `jobhunter_matching_retro_discarded_total`. A cleanup failure is logged without rolling back the already committed preference update; reviewed statuses (APPLIED/IRRELEVANT) are never touched.
- **Structured output** — the relevance evaluator forces OpenAI strict `json_schema` (`reasoning` before `score`, so the model reasons before committing to a number).
