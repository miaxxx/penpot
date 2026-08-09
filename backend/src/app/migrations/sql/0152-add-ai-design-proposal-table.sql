CREATE TABLE ai_design_proposal (
  id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  page_id uuid,
  origin text NOT NULL,
  mode text NOT NULL,
  dsl_type text NOT NULL,
  status text NOT NULL DEFAULT 'validated',
  base_revision bigint NOT NULL,
  scope jsonb NOT NULL,
  plan jsonb NOT NULL DEFAULT '{}'::jsonb,
  dsl jsonb NOT NULL,
  preview jsonb,
  error jsonb,
  apply_token uuid,
  transaction_id text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz NOT NULL DEFAULT clock_timestamp() + interval '1 hour',
  apply_started_at timestamptz,
  applied_at timestamptz,
  discarded_at timestamptz,

  CONSTRAINT ai_design_proposal__status__check
    CHECK (status IN ('validated', 'previewed', 'applying', 'applied',
                      'discarded', 'conflicted', 'expired')),
  CONSTRAINT ai_design_proposal__mode__check
    CHECK (mode IN ('generate', 'modify', 'refactor', 'adapt')),
  CONSTRAINT ai_design_proposal__dsl_type__check
    CHECK (dsl_type IN ('document', 'patch')),
  CONSTRAINT ai_design_proposal__origin__check
    CHECK (origin IN ('internal', 'mcp', 'plugin', 'rpc'))
);

CREATE INDEX ai_design_proposal__profile_file_status__idx
  ON ai_design_proposal (profile_id, file_id, status);

CREATE INDEX ai_design_proposal__expires_at__idx
  ON ai_design_proposal (expires_at)
  WHERE status IN ('validated', 'previewed', 'applying');
