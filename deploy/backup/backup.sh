#!/bin/sh
set -eu
umask 077
cd /opt/portfolio-api
. ./backup.env
: "${BACKUP_BUCKET:?}" "${RCLONE_CONFIG_LS_ACCESS_KEY_ID:?}" "${RCLONE_CONFIG_LS_SECRET_ACCESS_KEY:?}"
export RCLONE_CONFIG_LS_ACCESS_KEY_ID RCLONE_CONFIG_LS_SECRET_ACCESS_KEY
export RCLONE_CONFIG_LS_TYPE=s3 RCLONE_CONFIG_LS_PROVIDER=AWS RCLONE_CONFIG_LS_REGION=us-east-2
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
docker exec portfolio-production-postgres-1 pg_dump -U portfolio -d portfolio -Fc > "$work/db.dump"
tar -C /opt/portfolio-api -cf "$work/recommendations.tar" recommendations
(cd "$work" && sha256sum db.dump recommendations.tar > SHA256SUMS)
sha256sum portfolio-api.jar >> "$work/SHA256SUMS"
tar -C "$work" -cf - db.dump recommendations.tar SHA256SUMS | age -R backup-recipient.txt > "$work/archive.age"
name="daily/portfolio-v2-$timestamp.tar.age"
rclone copyto "$work/archive.age" "ls:$BACKUP_BUCKET/$name"
local_sum=$(sha256sum "$work/archive.age" | cut -d' ' -f1)
remote_sum=$(rclone cat "ls:$BACKUP_BUCKET/$name" | sha256sum | cut -d' ' -f1)
[ "$local_sum" = "$remote_sum" ] || { echo "backup verification failed for $name" >&2; exit 1; }
rclone delete --min-age 30d "ls:$BACKUP_BUCKET/daily"
echo "backup ok $name $local_sum"
