# Flyway et défauts d'initialisation

Vérifié le : 2026-10-11 (`pom.xml`, `application.properties`, `src/main/resources/db/migration/V1__init.sql`, `src/test/.../flyway/FlywaySchemaTest`, `configuration/initializer/*`).

## État

Flyway est **préparé mais non activé** : dépendances `flyway-core` + `flyway-mysql` (versions du BOM Spring Boot), `spring.flyway.enabled=false` (`application.properties` et propriétés de test), `V1__init.sql` généré depuis les entités. Le schéma reste géré par `spring.jpa.hibernate.ddl-auto` (`update` en dev/prod, `create-drop` en test). L'activation attend que les entités se stabilisent : tant qu'elle n'est pas faite, **aucune migration à écrire**.

Tables du plan d'ordres dry-run (`rainbow_live_order_plan`, `rainbow_live_order_plan_asset`, `rainbow_live_order_step`, FK `ON DELETE CASCADE`) et colonne `rainbow_live_binding.execution_enabled` : créées par `ddl-auto=update` tant que Flyway est inactif, présentes dans `V1__init.sql` (cf. [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md)). Défaut `execution_enabled = false` porté par la colonne (donnée utilisateur, pas un défaut d'initializer).

Passerelle actif non stable : table `transit_stock` et colonnes `transit_stock.finish_side` et `rainbow_live_binding.route_key` (remplace `bridge_pair`), dans `V1__init.sql`.

Lot d'exécution réelle (cf. [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md)) : tables `execution_control` et `rainbow_live_order_event`, colonnes d'exécution des étapes et des plans, `rainbow_live_binding.first_live_{approved,confirmed}_at`, `admin_action_log` (journal admin append-only), `transaction.fee_currency`, `api_credentials.scope` (+ contrainte `uk_credential_user_provider_scope`), toutes dans `V1__init.sql`.

**Base existante en `ddl-auto=update` : `ApiCredential.scope` demande un SQL manuel, une seule fois, avant le déploiement** (Hibernate n'ajoute pas de défaut à la colonne et ne supprime jamais l'ancien index unique, qui interdirait une clé `TRADE` à côté de la clé `READ`) :

```sql
ALTER TABLE api_credentials ADD COLUMN scope ENUM('READ','TRADE') NOT NULL DEFAULT 'READ';
ALTER TABLE api_credentials DROP INDEX uk_credential_user_provider;
ALTER TABLE api_credentials ADD CONSTRAINT uk_credential_user_provider_scope UNIQUE (user_id, web_provider_id, scope);
ALTER TABLE api_credentials MODIFY api_key VARCHAR(512) NOT NULL, MODIFY secret_key VARCHAR(512), MODIFY passphrase VARCHAR(512);
```

**Base existante : unicité du binding `(user_id, asset_symbol, wallet_id)`** : Hibernate crée la nouvelle contrainte mais garde l'ancienne (qui interdit un 2ᵉ binding du même actif). Le `DROP` direct échoue (index requis par la FK `user_id`) ⇒ créer la nouvelle d'abord si elle n'existe pas :

```sql
ALTER TABLE rainbow_live_binding ADD CONSTRAINT uk_rainbow_live_binding_user_asset_wallet UNIQUE (user_id, asset_symbol, wallet_id);
ALTER TABLE rainbow_live_binding DROP INDEX uk_rainbow_live_binding_user_asset;
```

**Base existante : snapshots live par lien** (`rainbow_live_binding_run`, créée par `ddl-auto=update`) : les anciens snapshots live portés par `rainbow_live_run` (`t2355_live_*` / `t0005_live_*`) ne sont pas migrés par Hibernate (colonnes laissées en place, ignorées). Copie optionnelle, une seule fois, avant le déploiement (un snapshot par binding actuel du preset) :

```sql
INSERT INTO rainbow_live_binding_run (binding_id, run_day, computed_at,
  t2355_status,
  t2355_wallet_id,
  t2355_fetched_at,
  t2355_cash_usd,
  t2355_cash_detail,
  t2355_position_qty,
  t2355_tradable_qty,
  t2355_cash_reserved,
  t2355_block_reason,
  t2355_action_type,
  t2355_action_amount_usdc,
  t2355_action_quantity,
  t0005_status,
  t0005_wallet_id,
  t0005_fetched_at,
  t0005_cash_usd,
  t0005_cash_detail,
  t0005_position_qty,
  t0005_tradable_qty,
  t0005_cash_reserved,
  t0005_block_reason,
  t0005_action_type,
  t0005_action_amount_usdc,
  t0005_action_quantity)
SELECT b.id, r.run_day, NOW(6),
  r.t2355_live_status,
  r.t2355_live_wallet_id,
  r.t2355_live_fetched_at,
  r.t2355_live_cash_usd,
  r.t2355_live_cash_detail,
  r.t2355_live_position_qty,
  r.t2355_live_tradable_qty,
  r.t2355_live_cash_reserved,
  r.t2355_live_block_reason,
  r.t2355_live_action_type,
  r.t2355_live_action_amount_usdc,
  r.t2355_live_action_quantity,
  r.t0005_live_status,
  r.t0005_live_wallet_id,
  r.t0005_live_fetched_at,
  r.t0005_live_cash_usd,
  r.t0005_live_cash_detail,
  r.t0005_live_position_qty,
  r.t0005_live_tradable_qty,
  r.t0005_live_cash_reserved,
  r.t0005_live_block_reason,
  r.t0005_live_action_type,
  r.t0005_live_action_amount_usdc,
  r.t0005_live_action_quantity
FROM rainbow_live_run r
JOIN rainbow_live_binding b ON b.preset_id = r.preset_id
WHERE r.t2355_live_status IS NOT NULL OR r.t0005_live_status IS NOT NULL;
```

`rainbow_live_order_step.wallet_id` (nullable) est ajoutée par `ddl-auto=update` ; les étapes existantes restent à `NULL`. Le format de `clOrdId` change (il encode le wallet) : un plan non exécuté est simplement re-planifié (TTL 5 min), un plan déjà exécuté garde ses anciens identifiants.

Catalogue : colonnes `exchange_instrument.cost_min`, `native_pair`, `fee_currency` (nullables) ajoutées par `ddl-auto=update`, présentes dans `V1__init.sql`. **Base existante : `admin_action_log.action` est un `ENUM` MySQL natif, Hibernate n'y ajoute pas les valeurs `ORDER_VALIDATE`, `ORDER_SEND` et `ORDER_READ`** ⇒ SQL manuel avant le déploiement :

```sql
ALTER TABLE admin_action_log MODIFY action ENUM('ARM_BINDING','AUTO_REENGAGE','CREDENTIAL_ENABLED','CREDENTIAL_STORED','KILL_SWITCH','ORDER_READ','ORDER_SEND','ORDER_VALIDATE','PLAN','RECONCILE','RUN') NOT NULL;
``` Le catalogue se remplit à nouveau au prochain rafraîchissement (ou `refresh(provider)`) : tant que `native_pair` est nul, Kraken ne peut recevoir aucun ordre (`PAIR_UNKNOWN`).

## Principe

- Le schéma de référence est celui des **entités JPA**, jamais celui d'une base existante.
- `V1__init.sql` recrée le schéma complet sur une base vide (MySQL/MariaDB). Pas de baseline : l'environnement cible est une base créée de zéro.
- Les valeurs par défaut de l'application (assets, providers, rôles, stratégies Actif Rainbow…) ne sont **pas** dans les migrations : elles sont créées au démarrage par les initializers (cf. règle ci-dessous).

## Générer / contrôler V1 (avant activation)

| Commande | Effet |
|---|---|
| `mvn test -Dtest=FlywaySchemaTest -Dflyway.generate=true` | (Ré)écrit `db/migration/V1__init.sql` depuis les entités (génération de script JPA via Hibernate, dialecte MySQL, sans connexion à une base). À relancer après chaque évolution d'entité tant que Flyway n'est pas activé. |
| `mvn test -Dtest=FlywaySchemaTest -Dflyway.check=true` | Échoue si `V1__init.sql` diverge des entités. |
| `mvn test` (sans option) | Vérifie que le schéma généré s'applique sur une base vide (H2 en mode MySQL, test de fumée). |

Relire `V1__init.sql` avant l'activation (types, `enum` MySQL natifs des `@Enumerated(STRING)`, contraintes d'unicité, FK `ON DELETE CASCADE`).

## Activer Flyway (procédure)

1. `FlywaySchemaTest` en mode check vert, `V1__init.sql` relu.
2. Sauvegarder la base cible (`mysqldump`) si elle contient des données à conserver.
3. Base vide : dans `application-dev/prod.properties` (gitignorés) mettre `spring.flyway.enabled=true` et `spring.jpa.hibernate.ddl-auto=validate`.
4. Premier démarrage : Flyway applique `V1`, Hibernate valide, les initializers peuplent les défauts.
5. Base non vide créée par `ddl-auto` : Flyway refuse de démarrer (schéma non vide sans historique). Soit recréer la base, soit poser un baseline explicite (`spring.flyway.baseline-on-migrate=true`, `spring.flyway.baseline-version=1`) après vérification que le schéma est identique à `V1`.
6. Tests : Flyway reste désactivé (`spring.flyway.enabled=false`), schéma `create-drop` sur H2.

## Règles une fois Flyway activé

- Toute modification d'entité (table, colonne, contrainte, valeur d'enum) = un nouveau fichier `V<n>__description.sql` dans `db/migration/`.
- Une migration appliquée n'est **jamais modifiée** (Flyway vérifie son checksum) ; on corrige par une migration suivante.
- `ddl-auto=validate` : un écart entité/schéma empêche le démarrage.

## Règle des défauts d'initialisation (valable dès maintenant)

Tout nouveau « défaut » de l'application (donnée de paramétrage devant exister au premier démarrage) est créé par un **initializer** (`configuration/initializer/`, `CommandLineRunner` + `@Order`), de façon **idempotente et élément par élément** : chaque élément absent est inséré, un élément existant n'est jamais écrasé (seuls les champs de synchronisation explicitement documentés peuvent l'être, ex. `AssetInitializer` pour `AssetProvider`). Pas de test global du type `count() == 0`.

Tables de cache de données publiques (non seedées par un initializer) : `exchange_instrument` (catalogue des paires, rempli à la demande par `InstrumentCatalog`).

Initializers existants : `AssetInitializer` (1), `AssetGroupInitializer` (2, groupe `USD` = USDC seul), `RoleInitializer` (10), `UserInitializer` (20), `HistoricalDataInitializer` (5, recharge `candle` et `etf_flow_snapshot` depuis les fichiers de backup si la table est vide), `ApiCredentialInitializer` (40, tous profils : clés du user System (CoinStats, Coinalyze, Twelve Data, Finnhub, SoSoValue) et clés Binance/Kraken/OKX d'OKlm lues dans les propriétés `tradeio.<fournisseur>.apiKey|secretKey` (OKX : `tradeio.okx.read.*` et `tradeio.okx.trade.*`, avec `passphrase`) des `application-*.properties` (gitignorés) ; clé absente => warning, rien semé), `WalletInitializer` (45), `WebProviderInitializer` (dont OKX, `https://www.okx.com`), `ContentSourceInitializer` (50), `RainbowAssetStrategyInitializer` (60, stratégies Actif Rainbow, jamais écrasées ensuite : System les modifie en base ; chaque utilisateur reçoit un preset qui la suit à sa première utilisation, cf. [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md)).

## Données non recréées par les initializers

À sauvegarder avant de supprimer une base :

- Comptes utilisateurs autres que `OKlm` et `System` (créés à la main).
- Clés `api_credentials` dont la propriété correspondante est absente du `application-*.properties` du profil actif (à renseigner pour recréer sans saisie).
- État `enabled` des `provider` modifié manuellement (l'initializer repose `true`).
- Données historiques de marché (`candle`, `etf_flow_snapshot`) : sauvegardées chaque semaine par `HistoricalDataBackupJob` (`tradeio.backup.dir`, défaut `backup/historical`, fichiers `<table>.tsv.gz`, jamais écrasés par une table vide) et rechargées au démarrage par `HistoricalDataInitializer` si la table est vide et le fichier présent. Le dossier de backup doit être conservé hors de la base supprimée.
- Données d'exploitation : `video_contents`/`media_claims`/`llm_call_logs` (coût LLM), `transaction`.
- Branche décisionnelle (`decision_snapshots`, `scenario_snapshots`, `events`) : en pause ; un mécanisme de sauvegarde récurrente reste à définir si elle reprend.
