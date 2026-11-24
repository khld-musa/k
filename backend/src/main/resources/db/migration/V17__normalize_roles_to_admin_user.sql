-- [ROLE NORMALIZATION] Consolidate legacy roles into two clean roles: ADMIN and USER
-- OPERATOR, VIEWER, API_USER → USER

UPDATE users
SET role = 'USER'
WHERE role IN ('OPERATOR', 'VIEWER', 'API_USER');

-- Update default for new rows
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'USER';

-- Update column comment
COMMENT ON COLUMN users.role IS 'User role: ADMIN (full access) or USER (standard access)';

