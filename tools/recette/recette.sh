#!/usr/bin/env bash
# Recette complète sur copie de la prod. Usage (VPS, utilisateur oklm) :
#   nohup tools/recette/recette.sh /chemin/tradeio-X.jar > recette-run.out 2>&1 &
# Lire ensuite le rapport : $REPORT_DIR/latest.md (verdict en tête). Procédure : docs/operations/recette.md
# Aucun SQL manuel : seul le démarrage du jar migre (Flyway). Rien n'est écrit en prod (dump en lecture seule).
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/lib.sh"

JAR=${1:-}
[ -f "$JAR" ] || { echo "usage: $0 <jar>" >&2; exit 2; }
JAR=$(readlink -f "$JAR")
STAMP=$(ts)
mkdir -p "$REPORT_DIR" "$BACKUP_DIR" "$REC_DIR/logs"
REPORT="$REPORT_DIR/recette-$STAMP.md"
OUT="$REC_DIR/recette-$STAMP.out"
LOCK="$REPORT_DIR/RUNNING"
JAR_SHA=$(sha "$JAR")
PID=""

finish() { # écrit le rapport, pose/retire le tampon OK, quitte
  local verdict="OK"; [ "$KO" -gt 0 ] && verdict="KO"
  [ -n "$PID" ] && stop_app
  {
    echo "# Recette $STAMP — **$verdict**"
    echo
    echo "- jar : \`$(basename "$JAR")\` (sha256 \`${JAR_SHA:0:16}\`)"
    echo "- KO : $KO · WARN : $WARN · journal complet : \`$OUT\`"
    printf '%s\n' "${REPORT_LINES[@]}"
  } > "$REPORT"
  ln -sfn "$REPORT" "$REPORT_DIR/latest.md"
  rm -f "$LOCK" "$REPORT_DIR"/recette-ok-*.stamp
  [ "$verdict" = "OK" ] && date +%s > "$REPORT_DIR/recette-ok-$JAR_SHA.stamp"
  log "Verdict $verdict — rapport : $REPORT"
  [ "$verdict" = "OK" ] && exit 0 || exit 1
}

stop_app() {
  kill "$PID" 2>/dev/null
  for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
  kill -0 "$PID" 2>/dev/null && { kill -9 "$PID" 2>/dev/null; check warn "arrêt forcé (kill -9) de la recette"; }
  PID=""
}

[ -e "$LOCK" ] && { echo "Une recette est déjà en cours ($LOCK)." >&2; exit 3; }
echo "$$ $STAMP" > "$LOCK"

# ---------------------------------------------------------------------------------------------
section "1. Préconditions"
db_load "$PROD_PROPS" PDB "jdbc:mysql://localhost:3306/tradeio5" || { check ko "properties prod lisibles" "$PROD_PROPS"; finish; }
[ -f "$REC_PROPS" ] || { check ko "application-recette.properties présent" "$REC_PROPS"; finish; }
db_load "$REC_PROPS" RDB || { check ko "properties recette lisibles"; finish; }

[ "$RDB_NAME" != "$PDB_NAME" ] && [[ "$RDB_NAME" == *recette* ]] \
  && check ok "base cible distincte de la prod" "$RDB_NAME (prod : $PDB_NAME)" \
  || { check ko "garde-fou base cible" "la base recette doit contenir 'recette' et différer de la prod ($RDB_NAME / $PDB_NAME)"; finish; }

[ "$(prop "$REC_PROPS" tradeio.execution.mode)" = "OFF" ] && check ok "tradeio.execution.mode=OFF" \
  || check ko "tradeio.execution.mode doit valoir OFF en recette"
[ "$(prop "$REC_PROPS" tradeio.execution.live-unlocked)" = "false" ] && check ok "live-unlocked=false" \
  || check ko "tradeio.execution.live-unlocked doit valoir false en recette"
BAD_CRON=$(grep -E '^[[:space:]]*tradeio\..*cron[[:space:]]*=' "$REC_PROPS" | grep -vE '=[[:space:]]*-[[:space:]]*$' || true)
[ -z "$BAD_CRON" ] && check ok "tous les crons désactivés (-)" || check ko "cron actif en recette" "$BAD_CRON"
[ -z "${TRADEIO_MASTER_KEY:-}" ] && check ok "TRADEIO_MASTER_KEY absente de l'environnement" \
  || check ko "TRADEIO_MASTER_KEY présente dans l'environnement : la recette ne doit jamais l'avoir"
[ "$(prop "$REC_PROPS" server.port)" = "$REC_PORT" ] && check ok "port recette $REC_PORT" \
  || check ko "server.port doit valoir $REC_PORT en recette"
[ "$(prop "$REC_PROPS" spring.jpa.hibernate.ddl-auto)" = "validate" ] && check ok "ddl-auto=validate" \
  || check ko "spring.jpa.hibernate.ddl-auto doit valoir validate"
pgrep -f "[T]radeio.*recette|[-]-spring.profiles.active=recette" >/dev/null && check ko "une instance recette tourne déjà" || check ok "aucune instance recette en cours"
[ "$KO" -gt 0 ] && finish

# ---------------------------------------------------------------------------------------------
section "2. Copie de la prod"
DUMP="$BACKUP_DIR/prod-pour-recette-$STAMP.sql.gz"
if mysqldump_as PDB --single-transaction --routines --triggers --events "$PDB_NAME" 2>"$REPORT_DIR/dump.err" | gzip > "$DUMP" \
   && [ "${PIPESTATUS[0]}" -eq 0 ] && [ -s "$DUMP" ]; then
  check ok "dump prod (lecture seule)" "$(du -h "$DUMP" | cut -f1) → $DUMP"
else
  check ko "dump prod" "$(head -c 300 "$REPORT_DIR/dump.err")"; finish
fi

mysql_as RDB -e "DROP DATABASE IF EXISTS \`$RDB_NAME\`; CREATE DATABASE \`$RDB_NAME\` CHARACTER SET utf8mb4;" 2>"$REPORT_DIR/restore.err" \
  && zcat "$DUMP" | mysql_as RDB "$RDB_NAME" 2>>"$REPORT_DIR/restore.err" \
  && check ok "restauration dans $RDB_NAME" || { check ko "restauration" "$(head -c 300 "$REPORT_DIR/restore.err")"; finish; }

mysql_as RDB "$RDB_NAME" < "$HERE/clean-recette.sql" 2>"$REPORT_DIR/clean.err" \
  && check ok "nettoyage (clés TRADE/exchanges, exécution des liens)" || { check ko "nettoyage" "$(head -c 300 "$REPORT_DIR/clean.err")"; finish; }

counts RDB "$RDB_NAME" > "$REPORT_DIR/counts-before.tsv"
HIST_PRE=$(mysql_as RDB -N "$RDB_NAME" -e "SELECT IFNULL(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1" 2>/dev/null || echo 0)
check ok "état avant migration" "$(wc -l < "$REPORT_DIR/counts-before.tsv") tables ; version Flyway de la copie : ${HIST_PRE:-aucune}"

# ---------------------------------------------------------------------------------------------
section "3. Démarrage du jar (profil recette)"
AGENT_KEY=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
cd "$REC_DIR" || { check ko "dossier $REC_DIR"; finish; }
env -u TRADEIO_MASTER_KEY nohup java -Xmx768m -jar "$JAR" \
  --spring.profiles.active=recette \
  --server.address=127.0.0.1 \
  --tradeio.security.agent-api-key="$AGENT_KEY" \
  --tradeio.security.agent-api-key-admin=true \
  > "$OUT" 2>&1 &
PID=$!
STARTED=0
for _ in $(seq 1 90); do
  grep -q 'Started TradeIO5' "$OUT" && { STARTED=1; break; }
  kill -0 "$PID" 2>/dev/null || break
  sleep 2
done
if [ "$STARTED" -eq 1 ]; then check ok "démarrage" "Started TradeIO5 (pid $PID)"
else check ko "démarrage" "pas de 'Started TradeIO5' (processus $(kill -0 "$PID" 2>/dev/null && echo vivant || echo mort))"; PID_DEAD=1; fi

# ---------------------------------------------------------------------------------------------
section "4. Flyway"
grep -E 'Flyway|Migrating schema|Successfully applied|Current version|up to date|baseline' "$OUT" | cut -c1-250 > "$REPORT_DIR/flyway.log"
block "Journal Flyway :" "$REPORT_DIR/flyway.log" 25
FAILED=$(mysql_as RDB -N "$RDB_NAME" -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0" 2>/dev/null || echo "?")
[ "$FAILED" = "0" ] && check ok "flyway_schema_history : aucune migration en échec" || check ko "migration Flyway en échec" "$FAILED"
HIST_POST=$(mysql_as RDB -N "$RDB_NAME" -e "SELECT IFNULL(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1" 2>/dev/null || echo "?")
check ok "version Flyway après démarrage" "$HIST_POST (avant : ${HIST_PRE:-aucune})"
mysql_as RDB -t "$RDB_NAME" -e "SELECT installed_rank,version,description,success,installed_on FROM flyway_schema_history ORDER BY installed_rank" > "$REPORT_DIR/history.txt" 2>/dev/null
block "Historique :" "$REPORT_DIR/history.txt" 30

# ---------------------------------------------------------------------------------------------
section "5. Smoke tests (clé agent éphémère, 127.0.0.1:$REC_PORT)"
if [ "$STARTED" -eq 1 ]; then
  B="http://127.0.0.1:$REC_PORT"; H="X-Api-Key: $AGENT_KEY"
  expect() { local exp=$1 label=$2; shift 2; local c; c=$(http_code "$@"); [[ "$c" =~ ^($exp)$ ]] && check ok "$label" "HTTP $c" || check ko "$label" "attendu $exp, reçu $c"; }
  expect 200 "GET /"                         "$B/"
  expect 200 "GET /login"                    "$B/login"
  expect 302 "GET /app (non authentifié)"    "$B/app"
  expect "302|401|403" "GET /api/admin/doctor sans clé (accès refusé)" "$B/api/admin/doctor"
  expect 200 "GET /api/admin/doctor (clé agent)" -H "$H" "$B/api/admin/doctor"
  expect 200 "POST /api/admin/doctor/run (lecture seule)" -X POST -H "$H" "$B/api/admin/doctor/run"
  expect 200 "GET /api/admin/doctor/alarms"  -H "$H" "$B/api/admin/doctor/alarms"
  expect 200 "GET /api/admin/decision/scenarios" -H "$H" "$B/api/admin/decision/scenarios"
  curl -s -m 30 -H "$H" "$B/api/admin/doctor" | head -c 4000 > "$REPORT_DIR/doctor.json"
  block "Rapport Docteur (début) :" "$REPORT_DIR/doctor.json" 15
  sleep 3
else
  check ko "smoke tests" "non exécutés (démarrage en échec)"
fi

# ---------------------------------------------------------------------------------------------
section "6. Journal et base"
errors_in "$OUT" | cut -c1-300 > "$REPORT_DIR/errors.log" || true
NERR=$(wc -l < "$REPORT_DIR/errors.log")
[ "$NERR" -eq 0 ] && check ok "aucune ERROR hors motifs autorisés" || check ko "$NERR ligne(s) ERROR inattendue(s)"
block "ERROR inattendues :" "$REPORT_DIR/errors.log" 25
grep -E 'Caused by:' "$OUT" | sort | uniq -c | sort -rn | head -10 | cut -c1-250 > "$REPORT_DIR/causes.log"
[ -s "$REPORT_DIR/causes.log" ] && { check warn "causes d'exceptions présentes" "voir détail"; block "Caused by :" "$REPORT_DIR/causes.log" 10; }
NWARN=$(grep -c ' WARN ' "$OUT" || true); check ok "WARN dans le journal" "$NWARN (informatif)"
grep -E ' WARN ' "$OUT" | cut -c1-220 | sed -E 's/^[0-9: .-]+//' | sort | uniq -c | sort -rn | head -10 > "$REPORT_DIR/warns.log"
block "WARN les plus fréquents :" "$REPORT_DIR/warns.log" 10

counts RDB "$RDB_NAME" > "$REPORT_DIR/counts-after.tsv"
join -t$'\t' -a1 -e MISSING -o 0,1.2,2.2 <(sort "$REPORT_DIR/counts-before.tsv") <(sort "$REPORT_DIR/counts-after.tsv") \
  | awk -F'\t' '$3=="MISSING" || $3<$2 {print}' > "$REPORT_DIR/counts-diff.tsv"
if [ -s "$REPORT_DIR/counts-diff.tsv" ]; then
  check ko "tables dont le nombre de lignes a baissé ou qui ont disparu (table / avant / après)" "à justifier (migration de nettoyage attendue ?)"
  block "Écarts :" "$REPORT_DIR/counts-diff.tsv" 20
else check ok "aucune perte de lignes par la migration" "comparaison table par table"; fi
join -t$'\t' <(sort "$REPORT_DIR/counts-before.tsv") <(sort "$REPORT_DIR/counts-after.tsv") | awk -F'\t' '$3>$2 {print $1": "$2" -> "$3}' > "$REPORT_DIR/counts-grew.txt"
[ -s "$REPORT_DIR/counts-grew.txt" ] && { REPORT_LINES+=("" "Tables ayant gagné des lignes (initializers/Doctor, informatif) :" '```'); while IFS= read -r l; do REPORT_LINES+=("$l"); done < "$REPORT_DIR/counts-grew.txt"; REPORT_LINES+=('```'); }

stop_app
finish
