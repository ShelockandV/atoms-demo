CREATE TABLE IF NOT EXISTS projects (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, title VARCHAR(200) NOT NULL,
 current_version VARCHAR(36), active_run VARCHAR(36), updated_at VARCHAR(40) NOT NULL
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
