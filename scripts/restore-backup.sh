#!/usr/bin/env bash
# Restores a database backup (MVP 2, 5.7) made by .github/workflows/backup.yml: decrypts it with the owner's private
# key (scripts/backup-keys.sh), checks it, and loads it into the database you name. Run it on the owner's PC with
# Docker running. Download the copy first: Cloudflare → R2 → goawaygo-backups → prod → the file → Download.
#
#   Usage: scripts/restore-backup.sh <downloaded .tar file>
#   Asks for: the target database host, database name, user and password (hidden). For a practice restore use a
#   scratch database (e.g. goawaygo_restore_test in the Neon project goawaygo-staging), never prod's own database
#   unless you mean to replace its data.
set -euo pipefail

ARCHIVE="${1:?Usage: scripts/restore-backup.sh <goawaygo-prod-....tar>}"
PRIVATE="$HOME/.goawaygo/backup/backup-private.pem"
[ -f "$PRIVATE" ] || { echo "No private key at $PRIVATE (scripts/backup-keys.sh)." >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar -xf "$ARCHIVE" -C "$WORK"
openssl pkeyutl -decrypt -inkey "$PRIVATE" -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 \
  -in "$WORK/copy.key.enc" -out "$WORK/copy.key"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in "$WORK/goawaygo.dump.enc" -out "$WORK/goawaygo.dump" \
  -pass file:"$WORK/copy.key"
[ "$(sha256sum "$WORK/goawaygo.dump" | cut -d' ' -f1)" = "$(cat "$WORK/goawaygo.dump.sha256")" ] \
  || { echo "The copy is damaged: its checksum doesn't match." >&2; exit 1; }
echo "Decrypted and checked: $(($(stat -c %s "$WORK/goawaygo.dump") / 1024)) KB"

read -r -p "Target database host (without -pooler): " HOST
read -r -p "Target database name (e.g. goawaygo_restore_test): " NAME
read -r -p "Target user (its owner): " USER_NAME
read -r -s -p "Password (not shown): " PASSWORD
echo
case "$NAME" in
  goawaygo) read -r -p "This replaces PROD's data. Type 'replace prod' to go on: " sure
    [ "$sure" = "replace prod" ] || { echo "Stopped."; exit 1; } ;;
esac

MSYS_NO_PATHCONV=1 PGPASSWORD="$PASSWORD" docker run --rm -e PGPASSWORD -v "$WORK:/backup" postgres:17.11 \
  pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error \
  --dbname="host=$HOST dbname=$NAME user=$USER_NAME sslmode=require" /backup/goawaygo.dump
unset PASSWORD
echo "Restored into $NAME on $HOST."
