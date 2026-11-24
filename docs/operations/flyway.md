---
statut: courant
vérifié_le: 2026-11-11
modifié_le: 2026-11-24
empreinte: ef76d60387
---
# Flyway et défauts d'initialisation

Vérifié le : 2026-11-11 (`pom.xml`, `application.properties`, `db/migration/`, `FlywaySchemaTest`, `configuration/initializer/*`).

## État

Flyway est **actif** (local, recette, VPS prod depuis le 2026-11-11 ; prod à la version **V8** depuis le 2026-11-24) : `spring.flyway.enabled=true`, `baseline-on-migrate=true`, `baseline-version=1` (`application.properties`), `spring.jpa.hibernate.ddl-auto=validate` (`application-dev/prod.properties`, gitignorés ; le fichier externe `/home/oklm/TradeIO/application-prod.properties` prime en prod). Les tests gardent `spring.flyway.enabled=false` (H2 `create-drop`).

- Base **vide** : Flyway applique `V1__init.sql` puis les migrations suivantes.
- Base **existante sans historique** (issue de `ddl-auto=update`) : baseline à V1 (aucune exécution de V1), puis migrations `V2+`. Le schéma de ces bases correspondait à V1 au 2026-11-11.
- `V1__init.sql` est généré depuis les entités (Hibernate, dialecte MySQL) : types, `enum` MySQL natifs, unicités, FK `ON DELETE CASCADE`.

## Règles

- Le schéma de référence = **entités JPA**. Toute modification d'entité (table, colonne, contrainte, valeur d'enum) = un **nouveau fichier `V<n>__description.sql`** dans `src/main/resources/db/migration/`. Plus aucun `ALTER` manuel.
- Une migration appliquée n'est **jamais modifiée** (checksum vérifié) ; on corrige par une migration suivante.
- Migration **idempotente** (rejouable en dev/recette) : conditionner chaque DDL par `information_schema` + `PREPARE`/`EXECUTE` (compatible MySQL 8 et MariaDB 10.11).
- Ne jamais supprimer dans une migration ce que le code lit encore : retirer d'abord le code, puis écrire la migration (un `V2` qui supprimait `preset_id` avant le retrait du code a fait échouer la recette du 2026-11-11).
- `ddl-auto=validate` : un écart entité/schéma empêche le démarrage.
- **Appli locale lancée dans JetBrains** : elle redémarre à chaque modif de fichier et applique la migration en cours d'écriture ; modifier ensuite ce fichier fait échouer le checksum au redémarrage suivant. Procédure : arrêter l'appli via le MCP JetBrains, écrire code + migration + tests, puis la relancer dans JetBrains une fois tout prêt.

`V6__base_rates_par_profil.sql` (taux de base par profil + marge k, 9 colonnes de `rainbow_modulation_control` ; appliquée en prod le 2026-11-24 ; les 8 colonnes de taux sont reprises puis supprimées par V9) : [`../architecture/08d-rainbow-modulation.md`](../architecture/08d-rainbow-modulation.md).

`V7__new_asset_proposal.sql` (table `new_asset_proposal`, propositions d'accueil d'un nouvel actif ; appliquée en prod le 2026-11-24) : [`../architecture/15m-pipeline-nouvel-actif.md`](../architecture/15m-pipeline-nouvel-actif.md).

`V8__asset_base_rate.sql` (table `asset_base_rate`, ligne de taux dédiée par actif écrite à la validation d'une proposition ; appliquée en prod le 2026-11-24 avec V6 et V7) : [`../architecture/15m-pipeline-nouvel-actif.md`](../architecture/15m-pipeline-nouvel-actif.md).

`V9__taux_de_base_unifies.sql` (appliquée en prod le 2026-11-24) : copie les 8 taux réels de `rainbow_modulation_control` vers 2 lignes `asset_base_rate` (BTC, ETH, `validated_by` = `migration V9`, `proposal_id` NULL) si elles n'existent pas, insère la table de calage si une ligne manque encore (base neuve), puis supprime les 8 colonnes `base_rate_<btc|eth>_<profil>` (`base_rate_margin_k` reste). Idempotente (information_schema + NOT EXISTS). Détail : [`../architecture/08d-rainbow-modulation.md`](../architecture/08d-rainbow-modulation.md).

`V10__actifs_dynamiques_socle.sql` (**pas encore appliquée en prod** : recette sur copie de prod obligatoire) : crée `asset_blocklist` (symbole unique) et `asset_eligibility_settings` (ligne unique, `min_volume_usd`), ajoute `volume_24h_quote` et `volume_read_at` à `exchange_instrument`, copie les 4 taux BTC vers une ligne `asset_base_rate` PAXG (`validated_by` = `migration V10`, `proposal_id` NULL) si PAXG est absent et BTC présent. Idempotente (information_schema + PREPARE/EXECUTE, `IF NOT EXISTS`, `NOT EXISTS`). Détail : [`../architecture/08c-rainbow-chemin-achat-wallet.md`](../architecture/08c-rainbow-chemin-achat-wallet.md).

## Restes legacy

`V5__nettoyage_legacy.sql` (appliquée en prod le 2026-11-23) a supprimé les restes du modèle d'avant les Affectations (colonnes `preset_id` et leurs FK / uniques, tables `rainbow_live_preset`, `rainbow_live_trend_config`, `rainbow_asset_strategy`, `rainbow_live_user_settings`, colonne `rainbow_live_preset_event.strategy_revision`), idempotente. Restent à nettoyer : [`../known-gaps/nettoyages.md`](../known-gaps/nettoyages.md).

## Base locale (dev)

- Base locale existante : le 1er démarrage pose le baseline V1 tout seul.
- Si une base a appliqué une migration retirée ou modifiée ensuite, `validate` refuse de démarrer : supprimer la ligne correspondante de `flyway_schema_history` (ou `flyway repair`) et remettre à la main le schéma dans l'état attendu. Une migration publiée n'est jamais retirée : on en ajoute une nouvelle.
- Écarts connus de la base locale vis-à-vis de V1 (sans impact tant qu'ils ne bloquent pas un `INSERT`) : colonne héritée `rainbow_modulation_control.rebalance_daily_cap_pct` (`NOT NULL`, sans entité). Une base locale trop dérivée se recrée depuis V1 + initializers.

## Générer / contrôler V1

| Commande | Effet |
|---|---|
| `mvn test -Dtest=FlywaySchemaTest -Dflyway.generate=true` | Réécrit `V1__init.sql` depuis les entités. **Ne plus l'utiliser** : V1 est figé ; une évolution d'entité = nouvelle migration. |
| `mvn test -Dtest=FlywaySchemaTest -Dflyway.check=true` | Échoue si `V1__init.sql` diverge des entités ; valable uniquement tant qu'aucune migration `V2+` ne change le schéma. |
| `mvn test` | Le schéma généré s'applique sur H2 (test de fumée). |

## Déployer une migration

Procédure complète (recette puis prod, retour arrière) : [`recette.md`](recette.md). Résumé : recette sur copie de la prod → dump prod → arrêt → démarrage (Flyway migre) → surveillance du journal. En cas de problème : lien `tradeio.jar` vers l'ancien jar ; restauration du dump seulement si une migration a modifié des données.

## Règle des défauts d'initialisation (valable dès maintenant)

Tout nouveau « défaut » de l'application (donnée de paramétrage devant exister au premier démarrage) est créé par un **initializer** (`configuration/initializer/`, `CommandLineRunner` + `@Order`), de façon **idempotente et élément par élément** : chaque élément absent est inséré, un élément existant n'est jamais écrasé (seuls les champs de synchronisation explicitement documentés peuvent l'être, ex. `AssetInitializer` pour `AssetProvider`). Pas de test global du type `count() == 0`.

Tables de cache de données publiques (non seedées par un initializer) : `exchange_instrument` (catalogue des paires, rempli à la demande par `InstrumentCatalog`).

Initializers existants : `AssetInitializer` (1), `AssetGroupInitializer` (2, groupe `USD` = USDC seul), `RoleInitializer` (10), `UserInitializer` (20), `HistoricalDataInitializer` (5, recharge `candle` et `etf_flow_snapshot` depuis les fichiers de backup si la table est vide), `ApiCredentialInitializer` (40, tous profils : clés du user System (CoinStats, Coinalyze, Twelve Data, Finnhub, SoSoValue) et clés Binance/Kraken/OKX d'OKlm lues dans les propriétés `tradeio.<fournisseur>.apiKey|secretKey` (OKX : `tradeio.okx.read.*` et `tradeio.okx.trade.*`, avec `passphrase`) des `application-*.properties` (gitignorés) ; clé absente => warning, rien semé), `WalletInitializer` (45), `WebProviderInitializer` (dont OKX, `https://www.okx.com`), `ContentSourceInitializer` (50), `ExecutionControlInitializer` (70, ligne `execution_control` kill switch engagé), `AssetEligibilitySettingsInitializer` (72, ligne `asset_eligibility_settings` à 1 000 000 USD, jamais modifiée ensuite), `RainbowModulationControlInitializer` (71, ligne `rainbow_modulation_control` aux défauts de `ModulationDefaults`, jamais modifiée ensuite), `ExpertBasePresetInitializer` (61, 9 presets système de base Trend / Rainbow Bull / Rainbow Bear, jamais modifiés ensuite), cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md).

## Données non recréées par les initializers

À sauvegarder avant de supprimer une base :

- Comptes utilisateurs autres que `OKlm` et `System` (créés à la main).
- Clés `api_credentials` dont la propriété correspondante est absente du `application-*.properties` du profil actif (à renseigner pour recréer sans saisie).
- État `enabled` des `provider` modifié manuellement (l'initializer repose `true`).
- Données historiques de marché (`candle`, `etf_flow_snapshot`) : sauvegardées chaque semaine par `HistoricalDataBackupJob` (`tradeio.backup.dir`, défaut `backup/historical`, fichiers `<table>.tsv.gz`, jamais écrasés par une table vide) et rechargées au démarrage par `HistoricalDataInitializer` si la table est vide et le fichier présent. Le dossier de backup doit être conservé hors de la base supprimée.
- Données d'exploitation : `video_contents`/`media_claims`/`llm_call_logs` (coût LLM), `transaction`.
- Branche décisionnelle (`decision_snapshots`, `scenario_snapshots`, `events`) : en pause ; un mécanisme de sauvegarde récurrente reste à définir si elle reprend.
