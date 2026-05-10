-- V7: pg_stat_statements + monitoring role для postgres_exporter
-- См. docs/architecture/09-observability.md §9.12.6 и docs/architecture/12-storage-operations.md §12.5.
--
-- Требует, чтобы расширение было в shared_preload_libraries (см. deploy/postgres/postgresql.conf).
-- Если параметр выставлен корректно — CREATE EXTENSION пройдёт; иначе миграция упадёт
-- и подскажет, что postgresql.conf нужно обновить и контейнер перезапустить.

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Read-only роль для postgres_exporter, доступ ко всем pg_stat_* views.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'monitoring') THEN
        EXECUTE format(
            'CREATE ROLE monitoring LOGIN PASSWORD %L',
            current_setting('stockyard.monitoring_password', true)
        );
    END IF;
END
$$;

GRANT pg_monitor TO monitoring;
GRANT CONNECT ON DATABASE stockyard TO monitoring;
