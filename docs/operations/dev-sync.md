---
statut: courant
vérifié_le: 2026-11-11
modifié_le: 2026-11-11
empreinte: 0a0fc67f34
---
# Copie de la prod vers le dev (anti-dérive)

Objectif : que la base de dev (schéma, paramétrage, presets, liens, réglages) soit une copie de la prod. Le schéma de référence reste V1 + migrations Flyway ; ce flux ne remplace pas la recette ([`recette.md`](recette.md)).

## Procédure
1. **VPS** (utilisateur `oklm`) : `tools/dev-sync/export-prod.sh` — dump en lecture seule → `~/backups/prod-pour-dev-<ts>.sql.gz`. Schéma complet ; données omises pour `candle`, `etf_flow_snapshot`, `llm_call_logs`, `events`, `decision_snapshots`, `scenario_snapshots`, `video_contents`, `media_claims`, `job_run`, `portfolio_snapshot` (structure conservée).
2. Récupérer le fichier sur le poste de dev (scp).
3. **Poste de dev** : Linux/Git Bash `tools/dev-sync/import-dev.sh <dump.sql.gz> --go` ; Windows `.\tools\dev-sync\import-dev.ps1 -Dump <dump.sql.gz> -Go` (sauvegarde auto de la base locale dans `backup\dev-avant-import-<ts>.sql` ; cherche `mysql.exe` dans le PATH puis `C:\Program Files\MySQL\MySQL Server 8.0\bin`) — **efface** la base locale (`application-dev.properties`), restaure, applique `clean-dev.sql`. Sans `--go` : simulation. Refuse tout hôte non local et toute base `*recette*`.
4. Démarrer le jar : Flyway migre si le code a avancé sur la prod.

## Nettoyage (`tools/dev-sync/clean-dev.sql`)
Clés de la prod : TRADE et clés en attente supprimées, clés READ des exchanges neutralisées (illisibles hors prod : clé maître). **Tes clés locales sont conservées** : `import-dev.sh` les sauvegarde avant d'effacer la base puis les réinjecte par (utilisateur, provider, scope) — mise à jour de la ligne prod équivalente, sinon insertion. Aucune ressaisie. `execution_enabled=0` sur tous les liens ; plans d'ordres en cours annulés ; kill switch engagé par la copie, puis **remis dans l'état qu'il avait en local**. 

## Points d'attention
- Les comptes utilisateurs (hash de mots de passe compris) et les transactions sont copiés : base de dev locale uniquement, jamais partagée.
- `application-dev.properties` local porte `tradeio.execution.mode=LIVE` / `live-unlocked=true` : tes clés TRADE locales sont conservées, les liens ne sont pas exécutables après import ; le kill switch garde son état local.
- Écarts de schéma tolérés vs V1 : tables legacy ([`../known-gaps/nettoyages.md`](../known-gaps/nettoyages.md)).

## Éléments présents en local mais pas en prod
- **Provider local en plus** : recréé avant la réinjection des clés (code, nom, URL, activation), puis ses clés locales sont réinjectées.
- **Wallet, lien (binding), affectation purement locaux** : absents de la copie (la base est remplacée) ; à recréer depuis l'interface (page Exchanges pour le wallet, puis Actifs gérés / Expert pour le lien et l'affectation). Les clés n'ont pas à être ressaisies.
- **Clé locale sur un provider/utilisateur existant en prod** : remplace la clé prod de la même ligne ; le wallet de la prod qui la référence suit.
