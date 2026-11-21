CREATE TABLE IF NOT EXISTS gateway_api_keys (
 id VARCHAR(36) PRIMARY KEY, tenant VARCHAR(128) NOT NULL, tenant_scope CHAR(64) NOT NULL,
 key_hash CHAR(64) NOT NULL UNIQUE,
 key_prefix VARCHAR(16) NOT NULL, name VARCHAR(100) NOT NULL, description VARCHAR(1000) NOT NULL,
 created_at VARCHAR(40) NOT NULL, last_used_at VARCHAR(40), revoked_at VARCHAR(40),
 INDEX gateway_key_tenant(tenant_scope)
);

CREATE TABLE IF NOT EXISTS gateway_rate_windows (
 tenant_owner VARCHAR(36) PRIMARY KEY, minute_start BIGINT NOT NULL, minute_used BIGINT NOT NULL,
 hour_start BIGINT NOT NULL, hour_used BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS daemon_approvals (
 tenant_owner VARCHAR(36) NOT NULL, requester VARCHAR(64) NOT NULL, request_id VARCHAR(100) NOT NULL,
 agent VARCHAR(128) NOT NULL, tool VARCHAR(128) NOT NULL, request_args MEDIUMTEXT NOT NULL,
 thread_id VARCHAR(200) NOT NULL, created_at VARCHAR(40) NOT NULL, expires_at BIGINT NOT NULL,
 decision VARCHAR(24), resolved_by VARCHAR(128),
 PRIMARY KEY(tenant_owner,request_id), INDEX daemon_approval_owner(tenant_owner, requester)
);

CREATE TABLE IF NOT EXISTS projects (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, title VARCHAR(200) NOT NULL,
 current_version VARCHAR(36), active_run VARCHAR(36), updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS gateway_sessions (
 id VARCHAR(36) PRIMARY KEY, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(id) REFERENCES projects(id)
);
CREATE TABLE IF NOT EXISTS gateway_deleted_sessions (
 id VARCHAR(36) PRIMARY KEY, FOREIGN KEY(id) REFERENCES gateway_sessions(id)
);
CREATE TABLE IF NOT EXISTS messages (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, role VARCHAR(20) NOT NULL,
 content MEDIUMTEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(project_id) REFERENCES projects(id)
);
CREATE TABLE IF NOT EXISTS generation_runs (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, status VARCHAR(30) NOT NULL,
 base_version VARCHAR(36), prompt MEDIUMTEXT NOT NULL, plan MEDIUMTEXT, error MEDIUMTEXT,
 created_at VARCHAR(40) NOT NULL, FOREIGN KEY(project_id) REFERENCES projects(id)
);
CREATE TABLE IF NOT EXISTS task_events (
 run_id VARCHAR(36) NOT NULL, event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_type VARCHAR(48) NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id), INDEX task_event_order(run_id,event_id)
);
CREATE TABLE IF NOT EXISTS gateway_task_context (
 run_id VARCHAR(36) PRIMARY KEY, context_json MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS project_versions (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, version_number INT NOT NULL,
 summary VARCHAR(500) NOT NULL, files MEDIUMTEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(project_id, version_number), FOREIGN KEY(project_id) REFERENCES projects(id)
);

CREATE TABLE IF NOT EXISTS run_options (
 run_id VARCHAR(36) PRIMARY KEY, mode VARCHAR(20) NOT NULL, model VARCHAR(100) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS run_stage_models (
 run_id VARCHAR(36) NOT NULL, stage VARCHAR(20) NOT NULL, model VARCHAR(100) NOT NULL,
 PRIMARY KEY(run_id,stage), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS message_details (
 message_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36), mode VARCHAR(20) NOT NULL,
 model VARCHAR(100) NOT NULL, asset_id VARCHAR(36),
 FOREIGN KEY(message_id) REFERENCES messages(id)
);
CREATE TABLE IF NOT EXISTS image_assets (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, prompt MEDIUMTEXT NOT NULL,
 model VARCHAR(100) NOT NULL, media_type VARCHAR(40) NOT NULL, image_data MEDIUMBLOB NOT NULL,
 created_at VARCHAR(40) NOT NULL, FOREIGN KEY(project_id) REFERENCES projects(id)
);

CREATE TABLE IF NOT EXISTS run_checkpoints (
 run_id VARCHAR(36) PRIMARY KEY, execution_token VARCHAR(36),
 files MEDIUMTEXT, failure MEDIUMTEXT, attempt INT NOT NULL DEFAULT 0,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS agent_checkpoints (
 run_id VARCHAR(36) PRIMARY KEY, snapshot MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS agent_steps (
 run_id VARCHAR(36) NOT NULL, step_number INT NOT NULL,
 action VARCHAR(30) NOT NULL, summary VARCHAR(1000) NOT NULL,
 outcome VARCHAR(20) NOT NULL, observation MEDIUMTEXT NOT NULL, updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(run_id,step_number), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS agent_usage (
 run_id VARCHAR(36) PRIMARY KEY, model_calls INT NOT NULL, metered_calls INT NOT NULL,
 prompt_tokens BIGINT NOT NULL, completion_tokens BIGINT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS agent_model_usage (
 run_id VARCHAR(36) NOT NULL, model VARCHAR(255) NOT NULL, metered_calls BIGINT NOT NULL,
 input_tokens BIGINT NOT NULL, output_tokens BIGINT NOT NULL,
 PRIMARY KEY(run_id,model), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS agent_token_budget (
 run_id VARCHAR(36) PRIMARY KEY, charged_tokens BIGINT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS agent_model_cost (
 run_id VARCHAR(36) NOT NULL, model VARCHAR(255) NOT NULL, usd DECIMAL(38,18) NOT NULL,
 PRIMARY KEY(run_id,model), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS run_skills (
 run_id VARCHAR(36) PRIMARY KEY, snapshot MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS run_patterns (
 run_id VARCHAR(36) PRIMARY KEY, pattern VARCHAR(40) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS run_workflow_templates (
 run_id VARCHAR(36) PRIMARY KEY, snapshot MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS workflow_node_budget (
 run_id VARCHAR(36) NOT NULL, node_id VARCHAR(80) NOT NULL, charged_tokens BIGINT NOT NULL,
 PRIMARY KEY(run_id,node_id), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS agent_step_metrics (
 run_id VARCHAR(36) NOT NULL, step_number INT NOT NULL, execution_time_ms BIGINT NOT NULL,
 PRIMARY KEY(run_id,step_number), FOREIGN KEY(run_id,step_number) REFERENCES agent_steps(run_id,step_number)
);
CREATE TABLE IF NOT EXISTS agent_dag_nodes (
 run_id VARCHAR(36) NOT NULL, node_id VARCHAR(80) NOT NULL, dependencies MEDIUMTEXT NOT NULL,
 status VARCHAR(20) NOT NULL, output MEDIUMTEXT NOT NULL, execution_time_ms BIGINT NOT NULL,
 PRIMARY KEY(run_id,node_id), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS run_approval_requirements (
 run_id VARCHAR(36) PRIMARY KEY, reason VARCHAR(500) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS completion_text_deltas (
 seq BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL, execution_token VARCHAR(36) NOT NULL,
 content MEDIUMTEXT NOT NULL, INDEX completion_delta_cursor(run_id,seq),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS scheduled_tasks (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(128) NOT NULL,
 name VARCHAR(200) NOT NULL, description VARCHAR(1000) NOT NULL,
 cron_expression VARCHAR(128) NOT NULL, timezone VARCHAR(128) NOT NULL,
 task_query MEDIUMTEXT NOT NULL, task_context MEDIUMTEXT NOT NULL,
 status VARCHAR(16) NOT NULL, next_run_epoch BIGINT, last_run_epoch BIGINT,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 INDEX scheduled_task_due(status,next_run_epoch), INDEX scheduled_task_owner(owner_id,status)
);
CREATE TABLE IF NOT EXISTS scheduled_task_executions (
 schedule_id VARCHAR(36) NOT NULL, run_id VARCHAR(36) NOT NULL UNIQUE, scheduled_epoch BIGINT NOT NULL,
 PRIMARY KEY(schedule_id,scheduled_epoch),
 FOREIGN KEY(schedule_id) REFERENCES scheduled_tasks(id),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS scheduled_task_timeouts (
 schedule_id VARCHAR(36) PRIMARY KEY, timeout_seconds INT NOT NULL,
 FOREIGN KEY(schedule_id) REFERENCES scheduled_tasks(id)
);
CREATE TABLE IF NOT EXISTS scheduled_task_budgets (
 schedule_id VARCHAR(36) PRIMARY KEY, limit_usd DECIMAL(28,18) NOT NULL,
 FOREIGN KEY(schedule_id) REFERENCES scheduled_tasks(id)
);
CREATE TABLE IF NOT EXISTS scheduled_run_budgets (
 run_id VARCHAR(36) PRIMARY KEY, limit_usd DECIMAL(28,18) NOT NULL, charged_usd DECIMAL(28,18) NOT NULL DEFAULT 0,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS scheduled_run_deadlines (
 run_id VARCHAR(36) PRIMARY KEY, deadline_epoch BIGINT NOT NULL,
 INDEX scheduled_deadline_due(deadline_epoch),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS temporal_run_dispatches (
 run_id VARCHAR(36) PRIMARY KEY, FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS temporal_schedule_sync (
 schedule_id VARCHAR(36) PRIMARY KEY, applied_version VARCHAR(40) NOT NULL,
 FOREIGN KEY(schedule_id) REFERENCES scheduled_tasks(id)
);

CREATE TABLE IF NOT EXISTS project_prompt_embeddings (
 run_id VARCHAR(36) NOT NULL, embedding_profile VARCHAR(64) NOT NULL,
 vector_json MEDIUMTEXT NOT NULL, PRIMARY KEY(run_id,embedding_profile),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS run_context_summaries (
 run_id VARCHAR(36) PRIMARY KEY, summary MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS workspace_document_indexes (
 version_id VARCHAR(36) PRIMARY KEY,
 FOREIGN KEY(version_id) REFERENCES project_versions(id)
);
CREATE TABLE IF NOT EXISTS workspace_document_chunks (
 version_id VARCHAR(36) NOT NULL, file_number INT NOT NULL, file_path VARCHAR(240) NOT NULL,
 chunk_number INT NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL, content TEXT NOT NULL,
 PRIMARY KEY(version_id,file_number,chunk_number),
 FOREIGN KEY(version_id) REFERENCES workspace_document_indexes(version_id)
);

CREATE TABLE IF NOT EXISTS workspace_document_vectors (
 version_id VARCHAR(36) NOT NULL, file_number INT NOT NULL, chunk_number INT NOT NULL,
 embedding_profile VARCHAR(64) NOT NULL, vector_json MEDIUMTEXT NOT NULL,
 PRIMARY KEY(version_id,file_number,chunk_number,embedding_profile),
 FOREIGN KEY(version_id,file_number,chunk_number)
 REFERENCES workspace_document_chunks(version_id,file_number,chunk_number)
);

CREATE TABLE IF NOT EXISTS agent_run_memories (
 run_id VARCHAR(36) NOT NULL, memory_key VARCHAR(64) NOT NULL, memory_scope VARCHAR(36) NOT NULL,
 query_text TEXT NOT NULL, result_text TEXT NOT NULL,
 PRIMARY KEY(run_id,memory_key), INDEX agent_memory_scope(memory_scope,run_id),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS swarm_human_messages (
 run_id VARCHAR(36) NOT NULL, message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 message_text MEDIUMTEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 INDEX swarm_message_run(run_id,message_id), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS swarm_input_snapshots (
 run_id VARCHAR(36) NOT NULL, stage VARCHAR(100) NOT NULL, closes_input BOOLEAN NOT NULL,
 payload MEDIUMTEXT NOT NULL, PRIMARY KEY(run_id,stage), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS scheduled_daemon_targets (
 schedule_id VARCHAR(36) PRIMARY KEY, requester VARCHAR(36) NOT NULL, agent_name VARCHAR(100) NOT NULL,
 FOREIGN KEY(schedule_id) REFERENCES scheduled_tasks(id)
);
CREATE TABLE IF NOT EXISTS scheduled_daemon_runs (
 run_id VARCHAR(36) PRIMARY KEY, requester VARCHAR(36) NOT NULL, agent_name VARCHAR(100) NOT NULL,
 connection_id VARCHAR(36), INDEX daemon_run_requester(requester,connection_id),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS daemon_task_event_details (
 event_id BIGINT PRIMARY KEY, run_id VARCHAR(36) NOT NULL, daemon_seq BIGINT NOT NULL, payload_json MEDIUMTEXT NOT NULL,
 UNIQUE(run_id,daemon_seq), FOREIGN KEY(event_id) REFERENCES task_events(event_id)
);

CREATE TABLE IF NOT EXISTS daemon_claim_leases (
 run_id VARCHAR(36) PRIMARY KEY, expires_epoch BIGINT NOT NULL, INDEX daemon_claim_expiry(expires_epoch),
 FOREIGN KEY(run_id) REFERENCES scheduled_daemon_runs(run_id)
);

CREATE TABLE IF NOT EXISTS gateway_google_accounts (
 subject_hash VARCHAR(64) NOT NULL, tenant_hash VARCHAR(64) NOT NULL, PRIMARY KEY(subject_hash,tenant_hash)
);
CREATE TABLE IF NOT EXISTS gateway_google_email_bindings (
 email_hash VARCHAR(64) PRIMARY KEY, subject_hash VARCHAR(64) NOT NULL,
 INDEX gateway_google_email_subject(subject_hash)
);
CREATE TABLE IF NOT EXISTS gateway_refresh_tokens (
 token_hash VARCHAR(64) PRIMARY KEY, subject VARCHAR(128) NOT NULL, tenant VARCHAR(128) NOT NULL,
 expires_epoch BIGINT NOT NULL, INDEX gateway_refresh_expiry(expires_epoch)
);

CREATE TABLE IF NOT EXISTS run_gateway_memory (
 run_id VARCHAR(36) PRIMARY KEY, memory_text MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS gateway_blobs (
 run_id VARCHAR(36) NOT NULL, field_path VARCHAR(128) NOT NULL, content MEDIUMTEXT NOT NULL,
 expires_epoch BIGINT NOT NULL, PRIMARY KEY(run_id,field_path), INDEX gateway_blob_expiry(expires_epoch),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS daemon_gateway_connections (
 connection_id VARCHAR(36) PRIMARY KEY, tenant_owner VARCHAR(36) NOT NULL, requester VARCHAR(36) NOT NULL,
 expires_epoch BIGINT NOT NULL, INDEX daemon_connection_owner(tenant_owner,requester,expires_epoch),
 INDEX daemon_connection_expiry(expires_epoch)
);

CREATE TABLE IF NOT EXISTS run_gateway_memory_capture (
 run_id VARCHAR(36) PRIMARY KEY, memory_scope VARCHAR(66) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS gateway_memory_entries (
 run_id VARCHAR(36) PRIMARY KEY, memory_scope VARCHAR(66) NOT NULL, content TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL, INDEX gateway_memory_scope_order(memory_scope,created_at,run_id),
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS run_tool_approvals (
 run_id VARCHAR(36) NOT NULL, step_number INT NOT NULL, approval_id VARCHAR(36) NOT NULL UNIQUE,
 tool_name VARCHAR(64) NOT NULL, reason VARCHAR(500) NOT NULL, decision VARCHAR(12) NOT NULL,
 PRIMARY KEY(run_id,step_number), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);

CREATE TABLE IF NOT EXISTS run_plan_reviews (
 run_id VARCHAR(36) PRIMARY KEY, version INT NOT NULL, approval_id VARCHAR(36) NOT NULL,
 revision_until_epoch BIGINT NOT NULL DEFAULT 0, decision VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
CREATE TABLE IF NOT EXISTS run_plan_revisions (
 run_id VARCHAR(36) NOT NULL, version INT NOT NULL, plan_text MEDIUMTEXT NOT NULL,
 feedback TEXT NOT NULL, created_at VARCHAR(40) NOT NULL, PRIMARY KEY(run_id,version),
 FOREIGN KEY(run_id) REFERENCES run_plan_reviews(run_id)
);

CREATE TABLE IF NOT EXISTS supervisor_plan_reviews (
 run_id VARCHAR(36) PRIMARY KEY, decided_at VARCHAR(40),
 FOREIGN KEY(run_id) REFERENCES run_plan_reviews(run_id)
);

CREATE TABLE IF NOT EXISTS run_node_tool_approvals (
 run_id VARCHAR(36) NOT NULL, node_key VARCHAR(36) NOT NULL, node_label VARCHAR(240) NOT NULL,
 approval_id VARCHAR(36) NOT NULL UNIQUE, tool_name VARCHAR(64) NOT NULL, reason VARCHAR(500) NOT NULL,
 action_json MEDIUMTEXT NOT NULL, decision VARCHAR(12) NOT NULL,
 PRIMARY KEY(run_id,node_key), FOREIGN KEY(run_id) REFERENCES generation_runs(id)
);
