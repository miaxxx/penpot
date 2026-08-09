CREATE TABLE ai_harness_workspace (
  id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  page_id uuid,
  name text NOT NULL DEFAULT 'Penpot AI Harness',
  version text NOT NULL DEFAULT '2.0',
  status text NOT NULL DEFAULT 'active',
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  CONSTRAINT ai_harness_workspace__status__check
    CHECK (status IN ('active', 'archived'))
);

CREATE UNIQUE INDEX ai_harness_workspace__profile_file_page__uniq
  ON ai_harness_workspace (profile_id, file_id, COALESCE(page_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE TABLE ai_harness_artifact (
  id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL REFERENCES ai_harness_workspace(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  path text NOT NULL,
  kind text NOT NULL,
  content_type text NOT NULL DEFAULT 'text/markdown',
  content text NOT NULL DEFAULT '',
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  required boolean NOT NULL DEFAULT false,
  read_order integer NOT NULL DEFAULT 100,
  content_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  CONSTRAINT ai_harness_artifact__kind__check
    CHECK (kind IN ('entry', 'guide', 'rules', 'tools', 'checks', 'progress',
                    'handoff', 'features', 'environment', 'documentation')),
  CONSTRAINT ai_harness_artifact__workspace_path__uniq
    UNIQUE (workspace_id, path)
);

CREATE INDEX ai_harness_artifact__workspace_order__idx
  ON ai_harness_artifact (workspace_id, read_order, path);

CREATE TABLE ai_harness_environment_snapshot (
  id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL REFERENCES ai_harness_workspace(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  file_revision bigint NOT NULL,
  status text NOT NULL,
  report jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  CONSTRAINT ai_harness_environment_snapshot__status__check
    CHECK (status IN ('healthy', 'degraded', 'blocked'))
);

CREATE INDEX ai_harness_environment_snapshot__workspace_created__idx
  ON ai_harness_environment_snapshot (workspace_id, created_at DESC);

ALTER TABLE ai_harness_run
  ADD COLUMN workspace_id uuid REFERENCES ai_harness_workspace(id) ON DELETE SET NULL,
  ADD COLUMN goal text NOT NULL DEFAULT '',
  ADD COLUMN progress jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN blockers jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN next_action text NOT NULL DEFAULT '',
  ADD COLUMN handoff jsonb,
  ADD COLUMN completion jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai_harness_run
  DROP CONSTRAINT ai_harness_run__status__check;

ALTER TABLE ai_harness_run
  ADD CONSTRAINT ai_harness_run__status__check
    CHECK (status IN ('running', 'control', 'proposal-created', 'blocked',
                      'verifying', 'paused', 'completed', 'failed', 'cancelled'));

CREATE INDEX ai_harness_run__workspace_created__idx
  ON ai_harness_run (workspace_id, created_at DESC)
  WHERE workspace_id IS NOT NULL;

CREATE TABLE ai_harness_check_result (
  id uuid PRIMARY KEY,
  run_id uuid NOT NULL REFERENCES ai_harness_run(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  check_id text NOT NULL,
  status text NOT NULL,
  required boolean NOT NULL DEFAULT true,
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  duration_ms bigint,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,

  CONSTRAINT ai_harness_check_result__status__check
    CHECK (status IN ('pending', 'running', 'passed', 'failed', 'skipped')),
  CONSTRAINT ai_harness_check_result__run_check__uniq
    UNIQUE (run_id, check_id)
);

CREATE INDEX ai_harness_check_result__run_status__idx
  ON ai_harness_check_result (run_id, status, required);
