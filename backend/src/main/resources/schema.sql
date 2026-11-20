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
