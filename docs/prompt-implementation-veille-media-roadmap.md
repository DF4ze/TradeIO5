# Roadmap d'implémentation — Veille média YouTube multi-chaînes

Ce document est le point d'entrée pour lancer l'implémentation dans une nouvelle conversation. Il séquence les étapes dans l'ordre de dépendance réel et indique, pour chacune, quel prompt/doc donner tel quel. Ne pas lancer une étape avant que la précédente ne soit terminée (compilée + testée) : chaque étape suivante suppose que les classes de l'étape d'avant existent déjà.

Contexte complet de la feature (pourquoi ce découpage, décisions prises, verdicts d'architecture) : `docs/etude-veille-media-youtube.md`. Ce fichier-ci ne le répète pas — il ne fait que séquencer.

## Ordre des étapes

| # | Étape | Statut | Prompt/doc à donner à la session d'implémentation |
|---|---|---|---|
| 0 | Niveaux LLM (LOW/MEDIUM/HIGH) | ✅ Fait | `docs/prompt-implementation-llm-model-tiers.md` |
| 1 | Traçabilité coût LLM (usage tokens) | ✅ Fait — vérifié dans le code (2026-07-10) : `OpenAIService.ask(..., callSite)`, `LlmCallLogEntity`, `LlmCallLogRepository`, `LlmCostCalculator` | `docs/prompt-implementation-llm-usage-tracking.md` |
| 2 | Lot 1a — Entités + clients (RSS générique, transcript générique) | ⬜ À faire | `docs/etude-veille-media-youtube.md` §3 (modèle de données) + §4 "Lot 1" |
| 3 | **Lot 1b — Job planifié d'ingestion (`@Scheduled`)** | ⬜ À faire, bloqué par #2 | `docs/etude-veille-media-youtube.md` §4 "Lot 1" — premier `@Scheduled` du projet (`service/scheduler/` vide à ce jour), voir détail ci-dessous |
| 4 | Lot 2 — Classification + extraction LLM (2 passes) | ⬜ À faire, bloqué par #1 et #3 | `docs/etude-veille-media-youtube.md` §2 + §4 "Lot 2" + §5 (portion transcript passe 1) |
| 5 | Lot 3 — Intégration Opinion/décision | ⬜ À faire, bloqué par #4 | `docs/etude-veille-media-youtube.md` §1 + §4 "Lot 3" + §5 (décroissance/agrégation des claims) |
| 6 | Lot 4 — Observabilité/ops | ⬜ À faire, bloqué par #5 | `docs/etude-veille-media-youtube.md` §4 "Lot 4" |

**Pourquoi cet ordre précisément** :
- #1 (usage tracking) avant #4 : déjà fait, donc non bloquant en pratique, mais gardé en tête de liste — c'est bien pour ça qu'on l'a fait avant que le Lot 2 ne commence à appeler `OpenAIService` massivement (mesure dès le premier appel réel).
- #2 avant #3 : le job planifié a besoin des entités `ContentSource`/`VideoContent` et des clients RSS/transcript pour avoir quelque chose à orchestrer.
- #3 (scheduler) séparé de #2 et avant #4 : c'est la première tâche planifiée du projet — mérite son propre passage plutôt que d'être noyée dans les entités/clients. Décisions propres à cette étape, à ne pas improviser en même temps que le reste : fréquence de poll (probablement alignée sur la cadence ~4 vidéos/semaine de Cryptolyze, largement moins fréquent qu'un scan H1 de marché), idempotence (ne pas retraiter une vidéo déjà vue — stocker le dernier `videoId` connu par `ContentSource` plutôt que de rejouer tout le flux RSS à chaque poll), gestion d'erreur par source (une chaîne en échec ne doit pas bloquer le poll des autres).
- #4 (Lot 2) a besoin des `VideoContent` produits par #3 pour avoir de la matière à classifier/extraire.
- #5 avant #6 : `MediaMarketOpinion` lit les `MediaClaim` que le Lot 2 produit — rien à agréger sans ça.
- #6 en dernier : traçabilité/gestion d'erreurs sur un pipeline qui existe déjà, pas de sens à le faire avant.

## Prompt unique pour l'implémentation (2026-07-10)

**`docs/prompt-implementation-veille-media-full.md`** couvre désormais, en un seul prompt autonome, tout ce qui reste à faire (étapes #2 à #6 ci-dessus : entités/clients, scheduler, classification/extraction, intégration Opinion, observabilité), au même niveau de détail que `prompt-implementation-lot1-indicateurs.md` (classes/champs exacts, patterns de code précis à suivre, tests attendus par lot). C'est ce fichier qu'il faut injecter dans la nouvelle conversation d'implémentation, pas ce tableau (ce tableau reste la vue d'ensemble/suivi de statut).

Un point technique important y a été découvert et documenté (absent d'`etude-veille-media-youtube.md`, qui restait à un niveau conceptuel) : `OpenAIService.ask(...)` désérialise toujours sa réponse en `LlmAdvice` (DTO spécifique trading BUY/SELL/HOLD) — inutilisable tel quel pour la classification et l'extraction de claims, qui ont besoin d'autres formats JSON. Le prompt complet traite ça comme le tout premier point du Lot 2 (généraliser `OpenAIService` avec une surcharge `<T> T ask(..., Class<T> responseType)`, rétrocompatible).

## Suivi

Une fois une étape terminée, mettre à jour son statut dans le tableau ci-dessus (✅) avant de lancer la suivante — c'est ce tableau qui sert de source de vérité sur l'avancement, pas la mémoire d'une conversation précédente.
