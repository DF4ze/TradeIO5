#!/usr/bin/env bash
# Fonctions communes recette / déploiement. À sourcer (VPS, utilisateur oklm ou root).
# Procédure : docs/operations/recette.md

PROD_DIR=${PROD_DIR:-/home/oklm/TradeIO}
REC_DIR=${REC_DIR:-/home/oklm/TradeIO-recette}
BACKUP_DIR=${BACKUP_DIR:-/home/oklm/backups}
REPORT_DIR=${REPORT_DIR:-$REC_DIR/reports}
PROD_PROPS=${PROD_PROPS:-$PROD_DIR/application-prod.properties}
REC_PROPS=${REC_PROPS:-$REC_DIR/application-recette.properties}
PROD_PORT=${PROD_PORT:-9003}
REC_PORT=${REC_PORT:-9004}
SERVICE=${SERVICE:-tradeio}

ts() { date +%Y%m%d-%H%M%S; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# prop <fichier> <clé> : dernière valeur, sans espaces de fin.
prop() { grep -E "^[[:space:]]*$2[[:space:]]*=" "$1" | tail -1 | cut -d= -f2- | sed -E 's/^[[:space:]]+//; s/[[:space:]]*$//'; }

# db_load <fichier properties> <préfixe> [url par défaut] : <préfixe>_HOST/PORT/NAME/USER/PASS.
# La prod n'a pas toujours spring.datasource.url dans son fichier externe : défaut = celui de application.properties.
db_load() {
  local f=$1 p=$2 url
  url=$(prop "$f" spring.datasource.url); url=${url:-${3:-}}
  [ -n "$url" ] || { log "spring.datasource.url absent de $f"; return 1; }
  local rest=${url#jdbc:*://}; rest=${rest%%\?*}
  local hostport=${rest%%/*} name=${rest#*/}
  printf -v "${p}_HOST" '%s' "${hostport%%:*}"
  local port=${hostport#*:}; [ "$port" = "$hostport" ] && port=3306
  printf -v "${p}_PORT" '%s' "$port"
  printf -v "${p}_NAME" '%s' "$name"
  printf -v "${p}_USER" '%s' "$(prop "$f" spring.datasource.username)"
  printf -v "${p}_PASS" '%s' "$(prop "$f" spring.datasource.password)"
}

# mysql_as <préfixe> [args mysql...] ; mysqldump_as idem. Mot de passe via MYSQL_PWD (jamais en argument).
mysql_as() { local p=$1; shift; local h=${p}_HOST po=${p}_PORT u=${p}_USER pw=${p}_PASS
  MYSQL_PWD=${!pw} mysql -h"${!h}" -P"${!po}" -u"${!u}" "$@"; }
mysqldump_as() { local p=$1; shift; local h=${p}_HOST po=${p}_PORT u=${p}_USER pw=${p}_PASS
  MYSQL_PWD=${!pw} mysqldump -h"${!h}" -P"${!po}" -u"${!u}" "$@"; }

# counts <préfixe> <base> : "table<TAB>lignes" pour chaque table (hors historique Flyway).
counts() {
  local p=$1 db=$2 t
  mysql_as "$p" -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY 1" |
  while read -r t; do printf '%s\t%s\n' "$t" "$(mysql_as "$p" -N "$db" -e "SELECT COUNT(*) FROM \`$t\`")"; done
}

# --- Rapport -----------------------------------------------------------------------------------
REPORT_LINES=(); KO=0; WARN=0
# check <ok|warn|ko> <libellé> [détail]
check() {
  local lvl=$1 label=$2 detail=${3:-}
  case $lvl in ok) icon="OK  ";; warn) icon="WARN"; WARN=$((WARN+1));; *) icon="KO  "; KO=$((KO+1));; esac
  REPORT_LINES+=("- **$icon** $label${detail:+ — $detail}")
  log "$icon $label${detail:+ — $detail}"
}
# section <titre>
section() { REPORT_LINES+=("" "## $1"); }
# block <titre> <fichier> [lignes max] : extrait en bloc de code
block() {
  REPORT_LINES+=("" "$1" '```')
  if [ -s "$2" ]; then while IFS= read -r l; do REPORT_LINES+=("$l"); done < <(head -n "${3:-40}" "$2" | cut -c1-300)
  else REPORT_LINES+=("(vide)"); fi
  REPORT_LINES+=('```')
}

sha() { sha256sum "$1" | cut -d' ' -f1; }

# errors_in <fichier> : lignes ERROR hors motifs autorisés (allowed-errors.txt).
errors_in() {
  local f=$1 allowed
  allowed=$(grep -vE '^\s*(#|$)' "$HERE/allowed-errors.txt" 2>/dev/null)
  if [ -n "$allowed" ]; then grep -E ' ERROR ' "$f" | grep -vE "$(printf '%s' "$allowed" | paste -sd'|')"
  else grep -E ' ERROR ' "$f"; fi
}

http_code() { curl -s -o /dev/null -m 20 -w '%{http_code}' "$@"; }
