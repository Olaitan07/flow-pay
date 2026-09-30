-- Credentials are now owned by auth-service (Epic 2). user-service no longer stores password hashes.
ALTER TABLE customers DROP COLUMN password_hash;
