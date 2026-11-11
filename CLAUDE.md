# TradeIO-5 — Contexte Claude

Back Spring (Java 21) + front `/app` : DCA intelligent crypto (Rainbow ATR), multi-user. Propriétaire : Clem (échanges en français, réponses courtes/techniques).

## Démarrage de session
1. Lire [`docs/README.md`](docs/README.md) (table de routage : « lire si… »). Ne charger que les docs utiles à la tâche.
2. Coder => lire [`docs/CODING_RULES.md`](docs/CODING_RULES.md).
3. **Avant de poser une question ou proposer un choix** : vérifier `docs/decisions/` (positions tranchées de Clem) et `docs/known-gaps/`. Ne pas re-questionner un point tranché.

## Règles transverses
- Cotation : **tout en USDC** (tous providers, Revolut X inclus). USDT abandonné.
- Fiscalité FR : aucun noeud fiat (`ExecutionDefaults.FIAT_CURRENCIES`). Prévenir Clem **seulement** si une de ses demandes sort du cadre.
- `docs/etudes/`, `docs/calibration/`, `docs/archive/` = journaux datés : **jamais une référence** d'état courant (voir `architecture/`, `decisions/`).
- Toute décision tranchée ou changement d'architecture => écrit dans `docs/` **dans la même session** (mettre à jour, supprimer ce qui n'est plus vrai ; pas d'historique dans les docs courants).
- Source de vérité = repo. La mémoire Claude ne garde que préférences de comportement + pointeurs : pas de faits projet en mémoire.
- Ne jamais re-décrire une décision dans un 2e doc : la lier.
- Doc déplacée/archivée => `python tools/doc-police/doc_police.py archive …` (réécrit les liens). Jamais de lien cassé.
- Ouvrir une étude : `docs/etudes/`. Elle est archivée une fois sa décision reprise dans `architecture/` ou `decisions/`.
- Schéma DB : Flyway actif. Toute modif d'entité = nouveau `V<n>__*.sql` (idempotent), jamais de migration appliquée modifiée, jamais d'`ALTER` manuel. Répéter en recette avant la prod : [`docs/operations/flyway.md`](docs/operations/flyway.md), [`docs/operations/recette.md`](docs/operations/recette.md).
- Outils utilitaires => `tools/` + référence dans `docs/`.
- Calibration/bench : mode itératif ; demander avant de relancer un bench coûteux.
- Prompts d'implémentation : pas conservés (dossier supprimé).

## Environnement
- JDK 21 pour le projet. Secrets gitignorés (jamais en clair dans le repo/docs). Détails : [`docs/operations/configuration.md`](docs/operations/configuration.md).
- Pine de référence Rainbow : `tools/pine/rainbow_dca_v3_atr_moon.pine` (le Java s'y conforme).

## Police de la doc
`python tools/doc-police/doc_police.py index|check|archive` — charte : [`docs/CHARTE.md`](docs/CHARTE.md). Passage hebdo planifié (branche `docs/police-AAAA-SS`, jamais main).
