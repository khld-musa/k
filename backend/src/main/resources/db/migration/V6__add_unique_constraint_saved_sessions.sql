ALTER TABLE saved_ssh_sessions ADD CONSTRAINT uk_user_id_name UNIQUE (user_id, name);
