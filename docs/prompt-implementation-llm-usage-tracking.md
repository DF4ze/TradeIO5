# Prompt d'implémentation — Traçabilité du coût LLM (usage tokens par appel)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le contexte des discussions précédentes. Il couvre une brique d'infrastructure générique (mesure réelle du coût des appels LLM), **pas** une feature applicative précise. Fait suite à `docs/prompt-implementation-llm-model-tiers.md` (niveaux LLM LOW/MEDIUM/HIGH), déjà implémenté.

Avant de commencer, lire dans l'ordre :
1. `service/connector/OpenAIService.java` — `ask(String userInput, LlmTier tier)`, seul point d'entrée actuel vers l'API OpenAI. Utilise `client.responses().create(...)` en mode **non-streamé** (important : en streaming, `usage()` remonte des zéros dans le SDK OpenAI, bug connu côté fournisseur — non-streamé ici, donc pas concerné).
2. `model/enumerate/LlmTier.java` et `configuration/properties/OpenAIProperties.java` (`ModelTiers` : `low`/`medium`/`high`) — système de niveaux déjà en place, à ne pas modifier.
3. `model/enumerate/OpenAIModel.java` — catalogue des modèles concrets.
4. `model/entity/tree/scenario/ScenarioEventEntity.java` — patron à suivre pour une entité de log simple (append-only, colonnes JSON pour le détail polymorphe si besoin, `Instant occurredAt`).
5. `service/tree/opinion/advisor/OpenAIAdvisor.java` — seul appelant actuel de `OpenAIService.ask`, à ne pas casser.

## Contexte / besoin

Coût LLM à surveiller par cas d'usage (ex. futur pipeline de veille média `docs/etude-veille-media-youtube.md`, qui fera deux appels par vidéo à des niveaux différents). Le SDK OpenAI Java expose déjà `Response.usage()` (`inputTokens()`, `outputTokens()`, `totalTokens()`) sur chaque appel non-streamé — cette donnée n'est aujourd'hui ni lue ni conservée dans `OpenAIService.ask` (seulement loggée en `debug`, l'objet `Response` entier, jamais parsé pour l'usage).

Objectif : capter cette donnée à chaque appel et la rendre interrogeable (par période, par tier, par cas d'usage), sans construire de dashboard (le projet n'en a pas et n'en a pas besoin pour l'instant — une requête SQL directe suffit).

## À faire (proposition de design, à valider/adapter à l'implémentation)

1. Ajouter un paramètre **`callSite`** (`String`, identifiant libre du site d'appel, ex. `"media-watch:classification"`, `"media-watch:extraction"`, `"opinion:openai-advisor"`) à `OpenAIService.ask(...)` — ou une surcharge qui l'accepte, pour ne pas casser silencieusement l'appelant existant (`OpenAIAdvisor`) sans lui donner un identifiant explicite. Décider si `callSite` doit être une `String` libre ou un `enum` fermé (probable tension future : un `enum` protège contre les fautes de frappe mais oblige à toucher ce fichier à chaque nouveau site d'appel — trancher à l'implémentation).
2. Nouvelle entité `LlmCallLogEntity` (patron `ScenarioEventEntity`, §point 4 ci-dessus) : `id`, `callSite`, `tier` (`LlmTier`), `model` (nom du modèle concret résolu), `inputTokens`, `outputTokens`, `totalTokens`, `occurredAt` (`Instant`). Pas de coût en euros stocké directement dans cette table (les tarifs changent dans le temps et ne doivent pas invalider l'historique déjà écrit) — le coût se calcule à la lecture, voir point 4.
3. Dans `OpenAIService.ask(...)`, après l'appel : extraire `response.usage()` (`Optional<ResponseUsage>` — gérer le cas absent sans lever d'exception, juste logger un warning et ne rien persister) et persister une ligne `LlmCallLogEntity`.
4. Table de tarifs configurable (par modèle, prix par million de tokens input/output) — probablement `application.properties` (`tradeio.openai.pricing.<model>.input`/`.output`) plutôt qu'en base, pour rester simple et versionné avec le code. Un service `LlmCostCalculator` (ou méthode utilitaire) qui prend un `LlmCallLogEntity` (ou une liste, pour l'agrégation) et retourne un coût en euros/dollars à partir de cette table — utilisé à la demande (requête/rapport), pas stocké.
5. Pas de job de reporting automatique à construire dans ce lot (pas de dashboard, pas de besoin identifié d'alerte) — une requête `LlmCallLogRepository` (somme des tokens par `callSite`/`tier` sur une période) suffit pour V1. Prévoir au moins une méthode de repository pratique pour ça (ex. agrégation par `callSite` sur les 7 derniers jours).

## Hors scope

Ne pas construire de dashboard, d'alerting, ni la feature "veille média YouTube" elle-même (`docs/etude-veille-media-youtube.md`) — uniquement l'instrumentation générique que ce pipeline (et d'autres futurs appelants de `OpenAIService`) utilisera pour mesurer son coût réel.
