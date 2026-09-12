# TradeIO5

Application Spring Boot (Java 21) qui calcule des signaux de décision d'investissement crypto multi-utilisateurs à partir de données de marché, macro et média, et les expose en interface web et en tools MCP pour un agent IA externe.

**Documentation complète et vérifiée sur le code : [`docs/README.md`](docs/README.md).**

## Point important avant de lire quoi que ce soit d'autre

TradeIO5 est un moteur de **signal de décision**, pas un système d'exécution d'ordres. Il produit des `Decision`/`ActionStep` en base ; rien dans le code n'appelle un exchange pour passer un ordre réel, et le sizing des positions est un placeholder constant au 2026-09-12. Détail : [`docs/known-gaps/decision-to-order-gap.md`](docs/known-gaps/decision-to-order-gap.md).

## Stack

Spring Boot 3.3.4, Java 21, Spring Data JPA + MySQL, Spring Security (JWT + clé API), Spring AI (serveur MCP), client OpenAI-compatible pour les advisors LLM. Détail complet : [`docs/architecture/01-overview.md`](docs/architecture/01-overview.md).

## Licence

Apache License 2.0 with Commons Clause — usage personnel/interne autorisé, modification et contribution autorisées, revente du logiciel ou d'un service principalement basé dessus interdite. Voir le fichier `LICENSE`.
