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
- Schéma DB : Flyway actif. Toute modif d'entité = nouveau `V<n>__*.sql` (idempotent), jamais de migration appliquée modifiée, jamais d'`ALTER` manuel. **Appli locale lancée dans JetBrains** : elle redémarre seule à chaque modif de fichier et applique la migration en cours d'écriture (checksum ensuite modifié => plantage). Donc : avant d'écrire une migration, **arrêter l'appli via le MCP JetBrains**, faire code + migration + tests, puis **la relancer dans JetBrains** une fois tout prêt. Recette **seulement** si modif de DB (`recette:tradeio-5`) (sinon inutile, sauf demande explicite ; déploiement `--sans-recette`) : [`docs/operations/flyway.md`](docs/operations/flyway.md), [`docs/operations/recette.md`](docs/operations/recette.md).
- **Gateway d'abord** (MCP `ssh-gateway`) : build/test/recette/déploiement uniquement via les opérations du playbook (`executeOperation` puis `waitOperation`) ; VPS via `getStatus`/`getServiceLogs`/`execCommand`. Jamais de script `.bat`/`.sh`/commande `mvn` ad hoc, jamais de `sleep` de polling ; opération manquante => l'ajouter au playbook. Table « quel outil pour quoi » : [`docs/operations/build-deploy.md`](docs/operations/build-deploy.md).
- Outils utilitaires => `tools/` + référence dans `docs/`.
- Calibration/bench : mode itératif ; demander avant de relancer un bench coûteux.
- Prompts d'implémentation : pas conservés (dossier supprimé).

## Environnement
- JDK 21 pour le projet. Secrets gitignorés (jamais en clair dans le repo/docs). Détails : [`docs/operations/configuration.md`](docs/operations/configuration.md).
- Pine de référence Rainbow : `tools/pine/rainbow_dca_v3_atr_moon.pine` (le Java s'y conforme).

## Police de la doc
`python tools/doc-police/doc_police.py index|check|archive` — charte : [`docs/CHARTE.md`](docs/CHARTE.md). Passage hebdo planifié (commits directement sur `master`, seul développeur). Inclut la relecture du `README.md` racine.
