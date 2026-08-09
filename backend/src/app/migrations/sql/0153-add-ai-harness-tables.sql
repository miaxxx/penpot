CREATE TABLE ai_harness_skill (
  id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  name text NOT NULL,
  slug text NOT NULL,
  version text NOT NULL,
  description text NOT NULL DEFAULT '',
  source text NOT NULL DEFAULT 'upload',
  enabled boolean NOT NULL DEFAULT true,
  manifest jsonb NOT NULL DEFAULT '{}'::jsonb,
  files jsonb NOT NULL DEFAULT '{}'::jsonb,
  instructions text NOT NULL DEFAULT '',
  content_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  CONSTRAINT ai_harness_skill__source__check
    CHECK (source IN ('upload', 'builtin', 'plugin')),
  CONSTRAINT ai_harness_skill__profile_slug__uniq
    UNIQUE (profile_id, slug)
);

CREATE INDEX ai_harness_skill__profile_enabled__idx
  ON ai_harness_skill (profile_id, enabled, modified_at DESC);

CREATE TABLE ai_harness_plugin (
  id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  name text NOT NULL,
  slug text NOT NULL,
  version text NOT NULL,
  description text NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  manifest jsonb NOT NULL DEFAULT '{}'::jsonb,
  contributions jsonb NOT NULL DEFAULT '{}'::jsonb,
  content_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  CONSTRAINT ai_harness_plugin__profile_slug__uniq
    UNIQUE (profile_id, slug)
);

CREATE INDEX ai_harness_plugin__profile_enabled__idx
  ON ai_harness_plugin (profile_id, enabled, modified_at DESC);

CREATE TABLE ai_harness_session (
  id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  page_id uuid,
  transport text NOT NULL DEFAULT 'internal',
  input_mode text NOT NULL DEFAULT 'assistant',
  persona text NOT NULL DEFAULT 'assistant',
  mode text NOT NULL,
  status text NOT NULL DEFAULT 'active',
  base_revision bigint NOT NULL,
  scope jsonb NOT NULL,
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  summary text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz NOT NULL DEFAULT clock_timestamp() + interval '24 hours',

  CONSTRAINT ai_harness_session__transport__check
    CHECK (transport IN ('internal', 'rpc', 'mcp', 'plugin', 'remote')),
  CONSTRAINT ai_harness_session__input_mode__check
    CHECK (input_mode IN ('assistant', 'buddy', 'voice', 'vim', 'remote', 'mcp', 'plugin')),
  CONSTRAINT ai_harness_session__persona__check
    CHECK (persona IN ('assistant', 'buddy')),
  CONSTRAINT ai_harness_session__mode__check
    CHECK (mode IN ('generate', 'modify', 'refactor', 'adapt')),
  CONSTRAINT ai_harness_session__status__check
    CHECK (status IN ('active', 'paused', 'closed', 'expired'))
);

CREATE INDEX ai_harness_session__profile_file__idx
  ON ai_harness_session (profile_id, file_id, modified_at DESC);

CREATE INDEX ai_harness_session__expires_at__idx
  ON ai_harness_session (expires_at)
  WHERE status IN ('active', 'paused');

CREATE TABLE ai_harness_run (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES ai_harness_session(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  proposal_id uuid REFERENCES ai_design_proposal(id) ON DELETE SET NULL,
  input_mode text NOT NULL,
  input_text text NOT NULL,
  command text,
  selected_skills jsonb NOT NULL DEFAULT '[]'::jsonb,
  coordinator_plan jsonb,
  context_report jsonb NOT NULL DEFAULT '{}'::jsonb,
  trace jsonb NOT NULL DEFAULT '[]'::jsonb,
  result jsonb,
  status text NOT NULL DEFAULT 'running',
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,

  CONSTRAINT ai_harness_run__input_mode__check
    CHECK (input_mode IN ('assistant', 'buddy', 'voice', 'vim', 'remote', 'mcp', 'plugin')),
  CONSTRAINT ai_harness_run__status__check
    CHECK (status IN ('running', 'control', 'proposal-created',
                      'completed', 'failed', 'cancelled'))
);

CREATE INDEX ai_harness_run__session_created__idx
  ON ai_harness_run (session_id, created_at DESC);

CREATE INDEX ai_harness_run__proposal__idx
  ON ai_harness_run (proposal_id)
  WHERE proposal_id IS NOT NULL;
