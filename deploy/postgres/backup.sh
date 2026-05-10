#!/usr/bin/env bash
# PostgreSQL backup script for Stockyard.
# См. docs/architecture/12-storage-operations.md §12.1.5.
#
# Запускается из host crontab:
#   0 3 * * *  docker exec stockyard-postgres /backup.sh >> /var/log/stockyard-backup.log 2>&1
#
# Retention: 7 дней. Восстановление вручную через `pg_restore`.

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
DB_NAME="${POSTGRES_DB:-stockyard}"
DB_USER="${POSTGRES_USER:-stockyard}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

ts="$(date +%Y%m%d_%H%M)"
out="${BACKUP_DIR}/stockyard_${ts}.dump"

echo "[$(date -Iseconds)] backup start → ${out}"

pg_dump -U "${DB_USER}" -d "${DB_NAME}" \
  --format=custom --compress=9 \
  --file="${out}"

# Проверка целостности — pg_restore --list читает заголовок дампа.
pg_restore --list "${out}" > /dev/null

echo "[$(date -Iseconds)] backup ok ($(stat -c '%s' "${out}" 2>/dev/null || stat -f '%z' "${out}") bytes)"

# Ротация: удаляем дампы старше N дней.
find "${BACKUP_DIR}" -name 'stockyard_*.dump' -mtime "+${RETENTION_DAYS}" -delete

echo "[$(date -Iseconds)] rotation done (kept ${RETENTION_DAYS} days)"
