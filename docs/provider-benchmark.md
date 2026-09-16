# Provider Benchmark

Scores a fixed set of jobs with every configured AI provider/model and compares
each result against the owner's own judgement, so a provider or model choice is
backed by a number instead of a feeling.

## 1. Export a fixture

```bash
scripts/export-bench-fixture.sh
```

Requires `kubectl` access to the production cluster and `jq`. It reads ten
AI-scored job groups and the current user preferences, and writes:

- `src/test/resources/bench/fixture.local.json` - the jobs and the preference profile
- `src/test/resources/bench/labels.local.json` - one entry per job id, pre-filled with `null`

Only groups that carry a `user_job_groups` row are eligible. `matched_at` alone is
not enough: it is also set on groups the cold filter rejected before any AI call,
and roughly four in five matched groups are exactly that. Benchmarking those would
score the models on input production never sends them.

The ten are stratified across the incumbent scores - four at 70 or above, three
between 40 and 69, three below - so the fixture spans the relevance boundary
instead of collapsing into a single class. That does mean the sample is chosen by
the model currently in production; without it a fixture of recent groups is almost
entirely negatives and false negatives become unmeasurable. The representative job
per group is the one with the longest description, matching `JobRelevanceEvaluator`.

Both files are gitignored (`*.local.json`) - the preference profile holds the
owner's CV text, which must never end up in this public repository. Re-running
the script refreshes the fixture but never overwrites an existing labels file,
so labelling progress is never lost by accident.

## 2. Label the fixture

Open `labels.local.json` and, for every job id, fill in your own judgement:

```json
{
  "094b5ecc-...": { "relevant": true, "score": 78 }
}
```

- `relevant` - would you actually want to see this job? Yes/no.
- `score` - your own 0-100 estimate of fit, used for mean absolute error.

The harness refuses to run while any job is missing a label. A benchmark
without ground truth can only show that two models disagree, never which one
is right.

## 3. Run it

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew bench
```

Provider configuration comes from environment variables only, never from the
database - nobody should need production access, and nobody's stored key gets
spent by a normal test run:

| Variable | Meaning | Default |
|---|---|---|
| `BENCH_CODEX_BASE_URL` | CLIProxyAPI base URL (host root, no `/v1`) | unset - provider skipped |
| `BENCH_CODEX_MODELS` | Comma-separated model ids to try | every `AiModel` entry for CODEX |
| `BENCH_GROQ_KEY` | Groq API key | unset - provider skipped |
| `BENCH_GROQ_MODELS` | Comma-separated Groq model ids | every `AiModel` entry for GROQ |
| `BENCH_GROQ_BASE_URL` | Groq OpenAI-compatible endpoint | `https://api.groq.com/openai/` |
| `BENCH_NVIDIA_KEY` | NVIDIA Build API key | unset - provider skipped |
| `BENCH_NVIDIA_MODELS` | Comma-separated NVIDIA model ids | every `AiModel` entry for NVIDIA |
| `BENCH_NVIDIA_BASE_URL` | NVIDIA OpenAI-compatible endpoint | `https://integrate.api.nvidia.com/` |
| `BENCH_OPENAI_KEY` | OpenAI API key | unset - provider skipped |
| `BENCH_OPENAI_MODELS` | Comma-separated model ids to try | every `AiModel` entry for OPENAI |
| `BENCH_GEMINI_KEY` | Gemini API key | unset - provider skipped |
| `BENCH_GEMINI_MODELS` | Comma-separated model ids to try | every `AiModel` entry for GEMINI |
| `BENCH_GEMINI_BASE_URL` | Gemini OpenAI-compatible endpoint | `https://generativelanguage.googleapis.com/v1beta/openai/` |

A provider with no configuration is skipped and reported as a skip, not a
failure. A provider that is configured but rejects a call (quota, auth,
schema, timeout) is also reported as data, not a test failure - `./gradlew
bench` only fails outright if the fixture/labels cannot be loaded at all.

If there is no `fixture.local.json` on the classpath, the harness falls back
to the committed `fixture.example.json`/`labels.example.json` - two synthetic
jobs, so the benchmark is runnable by anyone who clones this repository.

`./gradlew test` never runs this: the `bench` JUnit tag is excluded from the
default `test` task, so the normal build never calls a paid API.

## 4. Read the output

`build/bench/report.md` (also printed to stdout) has:

- **Providers** - one row per provider/model with mean absolute error against
  the owner's scores, false positives and false negatives, median/p95 latency,
  an estimated cost per 1000 calls (derived from the static per-1M prices in
  `AiModel`, using prompt/response length as a rough token estimate - not a
  measured token count), and how the structured output behaved.
- **Skipped Providers** - which providers had no configuration.
- **Per-Job Detail** - every job's label next to every model's score, so a
  surprising aggregate can be traced back to the job that caused it.

A model score of 60 or above is treated as "relevant" for the false
positive/negative counts. There is no such threshold in production - job
groups are stored with their raw score and filtering is a client concern - so
this is a benchmark-only convention for turning a continuous score into a
yes/no comparable against the owner's `relevant` label.

**False positives** (model says relevant, owner rejected it) matter more than
mean absolute error: they waste the owner's attention on a job that was never
worth a look, which is the exact failure the matching pipeline exists to
prevent. False negatives (model says irrelevant, owner would have wanted it)
are a real cost too, but a quieter one.

## Caveat

This is one person's judgement on ten jobs. Treat the numbers as a smoke test
that catches an obviously broken provider or model, not as a leaderboard for
picking the "best" model in general.

## NVIDIA soak test

After the ten-job benchmark passes, a bounded read-only soak test can measure the
hosted endpoint over several hours without touching the production database:

```bash
NVIDIA_API_KEY=... python3 -u scripts/nvidia-soak.py \
  --duration-hours 8 --interval-seconds 30 --max-requests 1000
```

The runner cycles through the offline ground-truth fixture, writes one JSONL record
per request under `build/nvidia-soak/`, captures latency, HTTP status, token usage and
any rate-limit headers, and writes a summary beside the log. It stops after five
consecutive `429` responses, on the duration/request bound, or on `SIGINT`/`SIGTERM`.
