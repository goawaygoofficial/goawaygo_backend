#!/usr/bin/env bash
# Makes the owner's backup key pair (MVP 2, 5.7), once. The private key stays on this PC
# (~/.goawaygo/backup/backup-private.pem, like the app's upload key): only it can open a database backup. Keep a copy
# somewhere safe offline (a password manager or a USB stick): without it, no backup can be restored.
# The public key becomes the repository variable BACKUP_PUBLIC_KEY, used by .github/workflows/backup.yml.
#
#   Usage (Git Bash, gh signed in as the owner: gh auth switch -h github.com -u goawayofficial):
#     scripts/backup-keys.sh
set -euo pipefail

DIR="$HOME/.goawaygo/backup"
PRIVATE="$DIR/backup-private.pem"
PUBLIC="$DIR/backup-public.pem"
REPO=goawaygoofficial/goawaygo_backend

mkdir -p "$DIR"
chmod 700 "$DIR" 2>/dev/null || true
if [ -f "$PRIVATE" ]; then
  echo "A backup key already exists ($PRIVATE): kept. Delete it yourself only if you mean to replace it."
else
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$PRIVATE" 2>/dev/null
  chmod 600 "$PRIVATE" 2>/dev/null || true
  echo "Private key made: $PRIVATE (stays on this PC; keep an offline copy)"
fi
openssl pkey -in "$PRIVATE" -pubout -out "$PUBLIC"
gh variable set BACKUP_PUBLIC_KEY -R "$REPO" < "$PUBLIC"
echo "Public key set as the variable BACKUP_PUBLIC_KEY of $REPO (it can only encrypt, never decrypt)."
