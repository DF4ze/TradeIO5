#!/usr/bin/env bash
# Déploiement prod. NE SE LANCE QUE SUR « GO » EXPLICITE de Clem. Usage (VPS, root, via ssh-gateway runAsUser root) :
#   deploy-prod.sh <jar> --go
# Garde-fous : refuse sans tampon « recette OK » (même sha256 de jar, < 24 h) ; backup de la base avant tout ;
# retour automatique au jar précédent si le démarrage échoue. Procédure : docs/operations/recette.md
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/lib.sh"

JAR=${1:-}; GO=${2:-}
[ -f "$JAR" ] && [ "$GO" = "--go" ] || { echo "usage: $0 <jar> --go (go explicite requis)" >&2; exit 2; }
[ "$(id -u)" -eq 0 ] || { echo "À lancer en root (systemctl)." >&2; exit 2; }
JAR=$(readlink -f "$JAR"); JAR_SHA=$(sha "$JAR")
STAMP=$(ts); mkdir -p "$REPORT_DIR" "$BACKUP_DIR"
REPORT="$REPORT_DIR/deploy-$STAMP.md"; JOURNAL="$REPORT_DIR/deploy-$STAMP.journal"
OKFILE="$REPORT_DIR/recette-ok-$JAR_SHA.stamp"
LINK="$PROD_DIR/tradeio.jar"
PREV_TARGET=""

finish() {
  local verdict="OK"; [ "$KO" -gt 0 ] && verdict="KO"
  { echo "# Déploiement prod $STAMP — **$verdict**"; echo
    echo "- jar : \`$(basename "$JAR")\` (sha256 \`${JAR_SHA:0:16}\`)"; echo "- KO : $KO · WARN : $WARN"
    printf '%s\n' "${REPORT_LINES[@]}"; } > "$REPORT"
  ln -sfn "$REPORT" "$REPORT_DIR/latest-deploy.md"
  chown -R oklm:oklm "$REPORT_DIR" "$BACKUP_DIR" 2>/dev/null
  log "Verdict $verdict — rapport : $REPORT"
  [ "$verdict" = "OK" ] && exit 0 || exit 1
}

rollback() {
  [ -n "$PREV_TARGET" ] || { check ko "retour arrière impossible" "lien précédent inconnu"; return; }
  log "Retour arrière vers $PREV_TARGET"
  systemctl stop "$SERVICE"; ln -sfn "$PREV_TARGET" "$LINK"; systemctl start "$SERVICE"
  sleep 20
  [ "$(http_code "http://127.0.0.1:$PROD_PORT/login")" = "200" ] \
    && check warn "retour arrière effectué, ancien jar redémarré" "$PREV_TARGET (restaurer le dump seulement si une migration a modifié des données)" \
    || check ko "retour arrière : l'ancien jar ne répond pas non plus" "intervention manuelle"
}

section "1. Garde-fous"
if [ -f "$OKFILE" ]; then
  AGE=$(( $(date +%s) - $(cat "$OKFILE") ))
  [ "$AGE" -lt 86400 ] && check ok "recette OK pour ce jar" "il y a $((AGE/60)) min" || { check ko "tampon de recette trop ancien (> 24 h)" "relancer la recette"; finish; }
else check ko "aucune recette OK pour ce jar (sha256 ${JAR_SHA:0:16})" "lancer tools/recette/recette.sh d'abord"; finish; fi
[ -f "$PROD_PROPS" ] && check ok "application-prod.properties présent" || { check ko "application-prod.properties introuvable"; finish; }
db_load "$PROD_PROPS" PDB "jdbc:mysql://localhost:3306/tradeio5" || { check ko "properties prod illisibles"; finish; }
PREV_TARGET=$(readlink -f "$LINK" 2>/dev/null || true)
[ -n "$PREV_TARGET" ] && check ok "jar actuel" "$PREV_TARGET" || check warn "lien $LINK absent : pas de retour arrière automatique"

section "2. Sauvegardes"
DUMP="$BACKUP_DIR/prod-avant-deploy-$STAMP.sql.gz"
if mysqldump_as PDB --single-transaction --routines --triggers --events "$PDB_NAME" 2>"$REPORT_DIR/dump-prod.err" | gzip > "$DUMP" \
   && [ "${PIPESTATUS[0]}" -eq 0 ] && [ -s "$DUMP" ]; then check ok "dump prod" "$(du -h "$DUMP" | cut -f1) → $DUMP"
else check ko "dump prod" "$(head -c 300 "$REPORT_DIR/dump-prod.err")"; finish; fi
cp -p "$PROD_PROPS" "$BACKUP_DIR/application-prod.properties.$STAMP" && check ok "properties prod sauvegardées"

section "3. Bascule"
NEWJAR="$PROD_DIR/$(basename "$JAR")"
[ "$JAR" = "$NEWJAR" ] || cp -p "$JAR" "$NEWJAR"
cmp -s "$JAR" "$NEWJAR" && check ok "jar copié" "$NEWJAR" || { check ko "copie du jar"; finish; }
SINCE=$(date '+%Y-%m-%d %H:%M:%S')
systemctl stop "$SERVICE" && check ok "systemctl stop $SERVICE" || { check ko "systemctl stop"; finish; }
ln -sfn "$NEWJAR" "$LINK"
systemctl start "$SERVICE"

STARTED=0
for _ in $(seq 1 90); do
  journalctl -u "$SERVICE" --since "$SINCE" --no-pager 2>/dev/null | grep -q 'Started TradeIO5' && { STARTED=1; break; }
  systemctl is-active --quiet "$SERVICE" || break
  sleep 2
done
journalctl -u "$SERVICE" --since "$SINCE" --no-pager > "$JOURNAL" 2>&1
if [ "$STARTED" -eq 1 ]; then check ok "démarrage" "Started TradeIO5"; else check ko "démarrage" "pas de 'Started TradeIO5'"; fi

section "4. Vérifications"
grep -E 'Flyway|Migrating schema|Successfully applied|Current version|up to date' "$JOURNAL" | cut -c1-250 > "$REPORT_DIR/deploy-flyway.log"
block "Flyway :" "$REPORT_DIR/deploy-flyway.log" 20
FAILED=$(mysql_as PDB -N "$PDB_NAME" -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0" 2>/dev/null || echo "?")
[ "$FAILED" = "0" ] && check ok "aucune migration en échec" || check ko "migration en échec" "$FAILED"
[ "$(http_code "http://127.0.0.1:$PROD_PORT/login")" = "200" ] && check ok "GET /login sur $PROD_PORT" "HTTP 200" || check ko "GET /login sur $PROD_PORT"
# ERROR : hors 401 de polling déjà connus ; la recette a validé le reste.
grep -E ' ERROR ' "$JOURNAL" | grep -vE '401' | cut -c1-300 > "$REPORT_DIR/deploy-errors.log" || true
[ -s "$REPORT_DIR/deploy-errors.log" ] && check ko "ERROR au démarrage" "$(wc -l < "$REPORT_DIR/deploy-errors.log") ligne(s)" || check ok "aucune ERROR hors 401 de polling"
block "ERROR :" "$REPORT_DIR/deploy-errors.log" 20

[ "$KO" -gt 0 ] && { section "5. Retour arrière automatique"; rollback; }
finish
