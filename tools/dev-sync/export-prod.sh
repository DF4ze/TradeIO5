#!/usr/bin/env bash
# Export de la prod pour l'environnement de dev (VPS, utilisateur oklm). LECTURE SEULE sur la prod.
# Produit $BACKUP_DIR/prod-pour-dev-<ts>.sql.gz : schéma complet + données de paramétrage ; les tables volumineuses
# (marché, journaux, décisions) sont exportées sans données. Import : tools/dev-sync/import-dev.sh. Doc : docs/operations/dev-sync.md
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/../recette/lib.sh"

# Tables dont on ne copie PAS les données (rechargées/recalculées en dev).
NODATA="candle etf_flow_snapshot llm_call_logs events decision_snapshots scenario_snapshots video_contents media_claims job_run portfolio_snapshot"

db_load "$PROD_PROPS" PDB "jdbc:mysql://localhost:3306/tradeio5" || { log "properties prod illisibles"; exit 1; }
mkdir -p "$BACKUP_DIR"
OUT="$BACKUP_DIR/prod-pour-dev-$(ts).sql.gz"

EXIST=$(mysql_as PDB -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$PDB_NAME' AND table_type='BASE TABLE'")
SKIP=(); for t in $NODATA; do grep -qx "$t" <<<"$EXIST" && SKIP+=("$t"); done
IGN=(); for t in "${SKIP[@]}"; do IGN+=("--ignore-table=$PDB_NAME.$t"); done

{
  mysqldump_as PDB --single-transaction --routines --triggers "${IGN[@]}" "$PDB_NAME" || exit 1
  [ "${#SKIP[@]}" -gt 0 ] && { mysqldump_as PDB --no-data --skip-add-drop-table "$PDB_NAME" "${SKIP[@]}" || exit 1; }
} | gzip > "$OUT"
[ "${PIPESTATUS[0]}" -eq 0 ] && [ -s "$OUT" ] || { log "export en échec"; rm -f "$OUT"; exit 1; }
log "OK $(du -h "$OUT" | cut -f1) → $OUT (tables sans données : ${SKIP[*]})"
echo "$OUT"
