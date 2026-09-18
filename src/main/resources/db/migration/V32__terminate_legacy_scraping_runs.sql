WITH terminated AS (
    UPDATE scraping_runs
    SET status = 'FAILED',
        lease_worker_id = NULL,
        lease_token = NULL,
        lease_expires_at = NULL,
        failure_code = 'LOOKBACK_CONTRACT_CHANGED',
        completed_at = now(),
        updated_at = now()
    WHERE status = 'ACTIVE'
    RETURNING source
)
UPDATE scraping_sources AS source
SET next_run_at = now(),
    last_error_at = now(),
    last_error_code = 'LOOKBACK_CONTRACT_CHANGED',
    runs_failed = source.runs_failed + 1,
    updated_at = now()
FROM terminated
WHERE source.source = terminated.source;
