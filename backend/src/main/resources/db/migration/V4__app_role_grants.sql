-- V4: least-privilege runtime role. The application connects as die_app (DML only); Flyway runs as the
-- schema owner. The role is created by infrastructure (docker compose init script / Supabase SQL),
-- so this migration is a no-op where it does not exist.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'die_app') THEN
        GRANT USAGE ON SCHEMA public TO die_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO die_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO die_app;

        -- Tables created by later migrations (run as the same owner) get the same DML grants.
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO die_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO die_app;

        -- The audit trail is append-only and the migration history is not the app's to touch.
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM die_app;
        REVOKE ALL ON flyway_schema_history FROM die_app;
    END IF;
END
$$;
