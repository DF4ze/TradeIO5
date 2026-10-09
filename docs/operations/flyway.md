# Flyway et défauts d'initialisation

Vérifié le : 2026-10-08 (`pom.xml`, `application.properties`, `src/main/resources/db/migration/V1__init.sql`, `src/test/.../flyway/FlywaySchemaTest`, `configuration/initializer/*`).

## État

Flyway est **préparé mais non activé** : dépendances `flyway-core` + `flyway-mysql` (versions du BOM Spring Boot), `spring.flyway.enabled=false` (`application.properties` et propriétés de test), `V1__init.sql` généré depuis les entités. Le schéma reste géré par `spring.jpa.hibernate.ddl-auto` (`update` en dev/prod, `create-drop` en test). L'activation attend que les entités se stabilisent : tant qu'elle n'est pas faite, **aucune migration à écrire**.

## Principe

- Le schéma de référence est celui des **entités JPA**, jamais celui d'une base existante.
- `V1__init.sql` recrée le schéma complet sur une base vide (MySQL/MariaDB). Pas de baseline : l'environnement cible est une base créée de zéro.
- Les valeurs par défaut de l'application (assets, providers, rôles, templates de presets…) ne sont **pas** dans les migrations : elles sont créées au démarrage par les initializers (cf. règle ci-dessous).

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

Initializers existants : `AssetInitializer` (1), `RoleInitializer` (10), `UserInitializer` (20), `HistoricalDataInitializer` (5, recharge `candle` et `etf_flow_snapshot` depuis les fichiers de backup si la table est vide), `ApiCredentialInitializer` (40, tous profils : clés du user System (CoinStats, Coinalyze, Twelve Data, Finnhub, SoSoValue) et clés Binance/Kraken d'OKlm lues dans les propriétés `tradeio.<fournisseur>.apiKey|secretKey` des `application-*.properties` (gitignorés) ; clé absente => warning, rien semé), `WalletInitializer` (45), `WebProviderInitializer`, `ContentSourceInitializer` (50), `RainbowLivePresetTemplateInitializer` (60, templates de presets Rainbow ; chaque utilisateur en reçoit une copie à sa première utilisation, cf. [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md)).

## Données non recréées par les initializers

À sauvegarder avant de supprimer une base :

- Comptes utilisateurs autres que `OKlm` et `System` (créés à la main).
- Clés `api_credentials` dont la propriété correspondante est absente du `application-*.properties` du profil actif (à renseigner pour recréer sans saisie).
- État `enabled` des `provider` modifié manuellement (l'initializer repose `true`).
- Données historiques de marché (`candle`, `etf_flow_snapshot`) : sauvegardées chaque semaine par `HistoricalDataBackupJob` (`tradeio.backup.dir`, défaut `backup/historical`, fichiers `<table>.tsv.gz`, jamais écrasés par une table vide) et rechargées au démarrage par `HistoricalDataInitializer` si la table est vide et le fichier présent. Le dossier de backup doit être conservé hors de la base supprimée.
- Données d'exploitation : `video_contents`/`media_claims`/`llm_call_logs` (coût LLM), `transaction`.
- Branche décisionnelle (`decision_snapshots`, `scenario_snapshots`, `events`) : en pause ; un mécanisme de sauvegarde récurrente reste à définir si elle reprend.
