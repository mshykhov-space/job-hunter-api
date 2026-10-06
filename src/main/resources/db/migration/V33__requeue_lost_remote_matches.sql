WITH affected_groups AS (
    SELECT DISTINCT j.group_id
    FROM jobs j
    WHERE j.remote IS NULL
      AND j.matched_at IS NOT NULL
      AND EXISTS (
          SELECT 1
          FROM user_job_group_decisions decision
          JOIN user_job_groups matched
            ON matched.group_id = decision.group_id AND matched.user_id = decision.user_id
          WHERE decision.group_id = j.group_id
            AND decision.outcome = 'AI_SCORED'
            AND decision.inferred_remote IS TRUE
      )
      AND NOT EXISTS (
          SELECT 1 FROM jobs other
          WHERE other.group_id = j.group_id AND other.remote IS TRUE
      )
      AND NOT EXISTS (
          SELECT 1 FROM jobs other
          WHERE other.group_id = j.group_id AND length(other.description) > length(j.description)
      )
)
UPDATE jobs j
SET matched_at = NULL, match_attempts = 0
FROM affected_groups affected
WHERE j.group_id = affected.group_id;
