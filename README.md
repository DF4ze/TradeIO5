# TradeIO5

Application Spring Boot (Java 21) de **DCA intelligent crypto** multi-utilisateurs : elle calcule des signaux de décision (Rainbow ATR, Trend) à partir de données de marché, macro et média, dimensionne des plans d'ordres, et peut les exécuter sur des exchanges (OKX, Kraken, One Trading, Bitvavo, Revolut X, Gate EU). Interface web `/app` et tools MCP pour un agent IA externe.

**Documentation complète et vérifiée sur le code : [`docs/README.md`](docs/README.md)** (table de routage). L'état d'exécution des ordres réels (verrous, kill switch, plafonds) est décrit dans [`docs/architecture/14-execution-reelle.md`](docs/architecture/14-execution-reelle.md) ; l'exécution `LIVE` est verrouillée par défaut.

## Stack

Spring Boot 3.3.4, Java 21, Spring Data JPA + MySQL/MariaDB (Flyway), Thymeleaf, Spring Security (JWT + clé API), Spring AI (serveur MCP), client OpenAI-compatible pour les advisors LLM. Détail : [`docs/architecture/01-overview.md`](docs/architecture/01-overview.md).

## Démarrer

JDK 21, puis `./mvnw spring-boot:run`. Propriétés et secrets (jamais en clair dans le repo) : [`docs/operations/configuration.md`](docs/operations/configuration.md). Migrations : [`docs/operations/flyway.md`](docs/operations/flyway.md).

## Licence

Apache License 2.0 with Commons Clause — usage personnel/interne autorisé, modification et contribution autorisées, revente du logiciel ou d'un service principalement basé dessus interdite. Voir le fichier [`LICENCE`](LICENCE).
