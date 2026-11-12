#!/usr/bin/env bash
# Remplace la base de DEV locale par une copie de prod (export-prod.sh). Poste de dev (bash + client mysql/mariadb).
# Usage : tools/dev-sync/import-dev.sh <dump.sql.gz> --go     (sans --go : affiche seulement la cible)
# Efface la base locale puis la recrée ; applique clean-dev.sql ; RÉINJECTE tes clés API locales (api_credentials) par
# (utilisateur, provider, scope). Le démarrage du jar applique Flyway. Doc : docs/operations/dev-sync.md
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
. "$HERE/../recette/lib.sh"
DUMP=${1:-}; GO=${2:-}
[ -f "$DUMP" ] || { echo "usage: $0 <dump.sql.gz> --go" >&2; exit 2; }
PROPS=${DEV_PROPS:-$ROOT/src/main/resources/application-dev.properties}
db_load "$PROPS" DDB "jdbc:mysql://localhost:3306/tradeio5" || exit 1
# Garde-fous : jamais la prod, jamais un hôte distant.
case "$DDB_HOST" in localhost|127.0.0.1) ;; *) log "refus : hôte '$DDB_HOST' non local"; exit 1;; esac
[[ "$DDB_NAME" == *recette* ]] && { log "refus : base '$DDB_NAME' (recette)"; exit 1; }
command -v mysql >/dev/null || { log "client mysql/mariadb introuvable dans le PATH"; exit 1; }
log "cible : $DDB_USER@$DDB_HOST:$DDB_PORT/$DDB_NAME ← $(basename "$DUMP")"
[ "$GO" = "--go" ] || { log "simulation : relancer avec --go (EFFACE la base $DDB_NAME)"; exit 0; }
# Sauvegarde des clés locales (SQL généré, QUOTE() gère l'échappement) avant d'effacer la base.
KEYS=$(mktemp); chmod 600 "$KEYS"; trap 'rm -f "$KEYS"' EXIT
if mysql_as DDB -N -B "$DDB_NAME" -e "SELECT 1 FROM api_credentials LIMIT 1" >/dev/null 2>&1; then
  mysql_as DDB -N -B --raw "$DDB_NAME" -e "SELECT CONCAT('INSERT INTO _local_keys VALUES (', QUOTE(u.username), ',', QUOTE(p.code), ',', QUOTE(ac.scope), ',', QUOTE(ac.api_key), ',', IFNULL(QUOTE(ac.secret_key),'NULL'), ',', IFNULL(QUOTE(ac.passphrase),'NULL'), ',', ac.enabled+0, ');') FROM api_credentials ac JOIN users u ON u.id=ac.user_id JOIN provider p ON p.id=ac.web_provider_id" > "$KEYS" \
    || { log "sauvegarde des clés locales en échec : abandon (base intacte)"; exit 1; }
  mysql_as DDB -N -B --raw "$DDB_NAME" -e "SELECT CONCAT('INSERT INTO _local_providers VALUES (', QUOTE(code), ',', QUOTE(name), ',', IFNULL(QUOTE(api_base_url),'NULL'), ',', enabled+0, ');') FROM provider" >> "$KEYS" \
    || { log "sauvegarde des providers locaux en échec : abandon (base intacte)"; exit 1; }
  mysql_as DDB -N -B --raw "$DDB_NAME" -e "SELECT CONCAT('INSERT INTO _local_kill VALUES (', kill_switch+0, ');') FROM execution_control LIMIT 1" >> "$KEYS" 2>/dev/null || true
  log "clés locales sauvegardées : $(wc -l < "$KEYS") ligne(s)"
else log "aucune clé locale à sauvegarder (base absente ou vide)"; fi
mysql_as DDB -e "DROP DATABASE IF EXISTS \`$DDB_NAME\`; CREATE DATABASE \`$DDB_NAME\` CHARACTER SET utf8mb4;" || exit 1
zcat "$DUMP" | mysql_as DDB "$DDB_NAME" || { log "restauration en échec"; exit 1; }
mysql_as DDB "$DDB_NAME" < "$HERE/clean-dev.sql" || { log "nettoyage en échec"; exit 1; }
# Réinjection des clés locales : mise à jour de la ligne prod équivalente, sinon insertion.
{
  echo "CREATE TEMPORARY TABLE _local_keys (username VARCHAR(20), code VARCHAR(32), scope VARCHAR(8), api_key VARCHAR(2048), secret_key VARCHAR(2048), passphrase VARCHAR(2048), enabled BIT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;"
  echo "CREATE TEMPORARY TABLE _local_providers (code VARCHAR(32), name VARCHAR(100), api_base_url VARCHAR(255), enabled BIT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;"
  echo "CREATE TEMPORARY TABLE _local_kill (kill_switch BIT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;"
  cat "$KEYS"
  cat "$HERE/reinject-local.sql"
} | mysql_as DDB "$DDB_NAME" || { log "réinjection des clés en échec"; exit 1; }
log "clés locales réinjectées"
log "OK : base $DDB_NAME = copie de prod nettoyée. Démarrer le jar (Flyway migre si besoin)."
