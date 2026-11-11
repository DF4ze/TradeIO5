---
statut: courant
vérifié_le: 2026-11-11
modifié_le: 2026-11-11
empreinte: efb901308e
---
# Flyway et défauts d'initialisation

Vérifié le : 2026-11-11 (`pom.xml`, `application.properties`, `db/migration/`, `FlywaySchemaTest`, `configuration/initializer/*`).

## État

Flyway est **actif** (local, recette, VPS prod depuis le 2026-11-11) : `spring.flyway.enabled=true`, `baseline-on-migrate=true`, `baseline-version=1` (`application.properties`), `spring.jpa.hibernate.ddl-auto=validate` (`application-dev/prod.properties`, gitignorés ; le fichier externe `/home/oklm/TradeIO/application-prod.properties` prime en prod). Les tests gardent `spring.flyway.enabled=false` (H2 `create-drop`).

- Base **vide** : Flyway applique `V1__init.sql` puis les migrations suivantes.
- Base **existante sans historique** (issue de `ddl-auto=update`) : baseline à V1 (aucune exécution de V1), puis migrations `V2+`. Le schéma de ces bases correspondait à V1 au 2026-11-11, aux restes legacy près (ci-dessous).
- `V1__init.sql` est généré depuis les entités (Hibernate, dialecte MySQL) : types, `enum` MySQL natifs, unicités, FK `ON DELETE CASCADE`.

## Règles

- Le schéma de référence = **entités JPA**. Toute modification d'entité (table, colonne, contrainte, valeur d'enum) = un **nouveau fichier `V<n>__description.sql`** dans `src/main/resources/db/migration/`. Plus aucun `ALTER` manuel.
- Une migration appliquée n'est **jamais modifiée** (checksum vérifié) ; on corrige par une migration suivante.
- Migration **idempotente** (rejouable en dev/recette) : conditionner chaque DDL par `information_schema` + `PREPARE`/`EXECUTE` (compatible MySQL 8 et MariaDB 10.11).
- Ne jamais supprimer dans une migration ce que le code lit encore (cf. legacy ci-dessous).
- `ddl-auto=validate` : un écart entité/schéma empêche le démarrage.

## Restes legacy (tolérés par `validate`, à nettoyer dans un V2 avec le code legacy)

Tant que `AffectationMigrationService` (migration des anciens presets vers les Affectations) existe, il lit `rainbow_live_binding|rainbow_live_engine_state|rainbow_live_run.preset_id` et la table `rainbow_live_preset`. Présents en prod hors V1 : colonnes `preset_id` (+ FK, uniques `uk_rainbow_live_*_preset_day`), tables `rainbow_asset_strategy` et `rainbow_live_user_settings` (sans entité), FK `rainbow_live_preset.asset_strategy_id`. Nettoyage : décision « legacy après une semaine » ([`../decisions/flyway-defauts-presets.md`](../decisions/flyway-defauts-presets.md)) ; **supprimer le code legacy avant les colonnes**.

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

Initializers existants : `AssetInitializer` (1), `AssetGroupInitializer` (2, groupe `USD` = USDC seul), `RoleInitializer` (10), `UserInitializer` (20), `HistoricalDataInitializer` (5, recharge `candle` et `etf_flow_snapshot` depuis les fichiers de backup si la table est vide), `ApiCredentialInitializer` (40, tous profils : clés du user System (CoinStats, Coinalyze, Twelve Data, Finnhub, SoSoValue) et clés Binance/Kraken/OKX d'OKlm lues dans les propriétés `tradeio.<fournisseur>.apiKey|secretKey` (OKX : `tradeio.okx.read.*` et `tradeio.okx.trade.*`, avec `passphrase`) des `application-*.properties` (gitignorés) ; clé absente => warning, rien semé), `WalletInitializer` (45), `WebProviderInitializer` (dont OKX, `https://www.okx.com`), `ContentSourceInitializer` (50), `ExecutionControlInitializer` (70, ligne `execution_control` kill switch engagé), `RainbowModulationControlInitializer` (71, ligne `rainbow_modulation_control` aux défauts de `ModulationDefaults`, jamais modifiée ensuite), `ExpertBasePresetInitializer` (61, 9 presets système de base Trend / Rainbow Bull / Rainbow Bear, jamais modifiés ensuite), `AffectationMigrationInitializer` (62, migration idempotente des anciens presets utilisateur vers les Affectations ; les Affectations des nouveaux utilisateurs sont créées à leur première utilisation), cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md).

## Données non recréées par les initializers

À sauvegarder avant de supprimer une base :

- Comptes utilisateurs autres que `OKlm` et `System` (créés à la main).
- Clés `api_credentials` dont la propriété correspondante est absente du `application-*.properties` du profil actif (à renseigner pour recréer sans saisie).
- État `enabled` des `provider` modifié manuellement (l'initializer repose `true`).
- Données historiques de marché (`candle`, `etf_flow_snapshot`) : sauvegardées chaque semaine par `HistoricalDataBackupJob` (`tradeio.backup.dir`, défaut `backup/historical`, fichiers `<table>.tsv.gz`, jamais écrasés par une table vide) et rechargées au démarrage par `HistoricalDataInitializer` si la table est vide et le fichier présent. Le dossier de backup doit être conservé hors de la base supprimée.
- Données d'exploitation : `video_contents`/`media_claims`/`llm_call_logs` (coût LLM), `transaction`.
- Branche décisionnelle (`decision_snapshots`, `scenario_snapshots`, `events`) : en pause ; un mécanisme de sauvegarde récurrente reste à définir si elle reprend.
