---
statut: journal
modifié_le: 2026-11-06
empreinte: 682d3db006
---
# Étude — Console admin multi-utilisateurs (filtres + actions groupées)

Rédigée le 2026-11-06, analyse seule (aucun code). Onglet concerné : Administration > Console (`fragments/admin/console.html`, `static/assets/js/admin/console.js`).

## 1. Besoin

- Les plans de tous les users se mélangent => filtrer par **utilisateur**, **jour**, **statut**.
- VPS HS au moment d'une planification => pouvoir **relancer pour tous les users d'un jour** : passe live, planifier, réinitialiser, armer, exécuter, réconcilier.
- L'exécution **plan par plan** reste possible (le workflow 7 étapes actuel est conservé).

## 2. Existant (constats)

- `GET /api/admin/execution/plans` accepte déjà `from`, `to`, `userId`, `status`.
- `POST /execution/plan?day` et `POST /rainbow-live/run?day` sont déjà globaux (tous users).
- Par plan seulement : `POST /plans/{id}/reset`, `POST /run?planId`, `POST /bindings/{id}/arm`.
- Le `<select>` de plans ne passe pas à l'échelle ; l'état des étapes est calculé côté JS pour un seul plan (`context()`).

## 3. Décisions (Clem, 2026-11-06)

- Actions groupées en **endpoints bulk côté serveur** (pas de boucle JS).
- **Exécution groupée autorisée**, et exécution individuelle conservée.

## 4. UX proposée

### 4.1 Barre de filtres
`Jour [◀ date ▶] · Utilisateur [Tous ▾] · Statut [Tous ▾] · ⟳`
Actions déjà globales sur le jour : `Lancer la passe`, `Resynchroniser`, `Planifier tous`.

### 4.2 Tableau de plans (remplace le `<select>`)
Colonnes : ☑ · Jour · User · Rév · Statut · Actifs/étapes · Armé · Confirmé · Blocage/erreur · ▸.
- Chips de comptage par statut (clic = filtre).
- Case d'en-tête = tout ce qui est filtré ; raccourci « tous les EXPIRED/BLOCKED du jour ».
- Ligne ▸ = détail inline (résumé, étapes, `explainBlocks`) + **les 7 étapes actuelles pour ce plan** (exécution individuelle).

### 4.3 Barre d'actions groupées (≥1 sélection)
Affiche `éligibles X / sélectionnés N · M users`. Actions : Réinitialiser, Armer, Exécuter, Réconcilier.
Inéligibles ignorés et listés avec leur raison (jamais d'échec global pour un plan inéligible).
- **Exécuter** : modale de récap (`N plans, M ordres, total USDC, users`) + saisie de « EXÉCUTER ».

### 4.4 Résultat
Tableau par plan `planId · user · DONE/SKIPPED/FAILED · raison` + bouton « Relancer les erreurs ».

### 4.5 Parcours VPS HS
1. Jour J => `Lancer la passe` => `Planifier tous` (idempotent : `unchanged` pour les plans existants).
2. Chip EXPIRED/BLOCKED => tout sélectionner => Réinitialiser.
3. Vérifier armements/confirmations => Exécuter la sélection.

## 5. Back : endpoints bulk

`POST /api/admin/execution/plans/bulk/{reset|arm|run|reconcile}` — body `{ "planIds": [..] }`.
Réponse : `[{ planId, userId, username, outcome: DONE|SKIPPED|FAILED, reason, summary }]`.

Règles :
- **Éligibilité revérifiée serveur** par plan, avec les mêmes conditions que les endpoints unitaires (reset : aucun ordre parti ; run : PLANNED + armé + confirmé + kill switch levé ; reconcile : en vol).
- Traitement **séquentiel** (rate limits exchange) ; un échec n'arrête pas le lot.
- Garde-fous inchangés (LIVE, kill switch, plafonds par ordre/jour, double validation du 1er ordre : l'**armement admin** est groupable, la **confirmation propriétaire** reste individuelle).
- `admin_action_log` : une ligne par plan + un `batchId` commun.
- Un seul lot à la fois (verrou) ; taille max configurable (ExecutionDefaults).
- Idempotence : `clOrdId` sans révision => relancer un lot ne double pas un ordre déjà parti.

### Points à vérifier à l'implémentation
- **Réengagement du kill switch** : aujourd'hui automatique à la fin de chaque `run`. En lot, il doit être différé à la **fin du lot** (sinon les plans suivants sont refusés). Prévoir un `run` interne sans réengagement + un réengagement unique final, y compris en cas d'exception.
- Durée : le lot est synchrone ; si > timeout HTTP pour beaucoup de users, passer en job asynchrone + polling (Q ouverte).
- `reset` d'un jour ouvert recalcule le signal par user (`recomputeDay`) : coût linéaire en nb de users.

## 6. Fichiers impactés (prévision)

`ExecutionPlanAdminController` / `ExecutionAdminController` (ou nouveau `ExecutionBulkAdminController`) + service de lot, DTO de résultat, `console.html`, `console.js`, `admin-core.js` (helpers tableau/modale), tests (éligibilité, lot partiel, réengagement unique, journal).
Docs à mettre à jour au codage : `architecture/10-execution-plan.md`, `architecture/11-front-app.md`.

## 7. Perf / parallélisation (analyse 2026-11-06)

Existant : `RainbowLiveExecutionService#runPass` est `synchronized` et séquentiel (users puis presets), aucun executor. Les séries D1 sont déjà chargées **une fois par (actif, source)** et mémorisées dans `PassContext`. Le rejeu ATR / pas Trend Mix est refait **par preset** (chaque user a sa copie du preset système, qui suit une `RainbowAssetStrategy`). Le plan lit les runs déjà stockés en base (étape « calculer puis stocker puis appliquer » déjà en place).
Point faible : `recomputeDay(user, ..)` crée un `PassContext` neuf => un reset groupé de N users rechargerait les séries N fois. Le lot de reset doit **partager un seul `PassContext`**.

Évolution possible, seulement si la mesure le justifie :
1. Mesurer d'abord (durées chargement séries / signal / liens+wallets dans `PassSummary`).
2. Signal mémorisé par clé (actif, source, config de stratégie[, état Trend Mix]) ; le Trend Mix reste par preset tant que son état persisté diffère.
3. Parallélisme par domaine de rate-limit (exchange/clé API), threads virtuels (Java 21), jamais deux ordres simultanés sur une même clé ; phase signal indépendante des bindings users.

## 8. Décisions de cadrage (Clem, 2026-11-06)

- Lot **synchrone**, petite taille max configurable au départ, à relever selon durée et ressources serveur mesurées.
- Pas de job asynchrone/polling pour l'instant.
- Parallélisation « intelligente » (calcul par asset/provider puis application user par user) : retenue comme évolution après mesure (§7).

## 9. Réalisé (2026-11-06)

- [x] Endpoints bulk serveur, dryRun, éligibilité unique (`AdminBulkPlanService`), lot synchrone borné (`tradeio.execution.bulk.max-plans`, défaut 10), exécution groupée avec saisie `EXECUTER`, exécution individuelle conservée.
- [x] Kill switch en lot : `runWithoutReengage` + rengagement unique en fin de lot.
- [x] Reset groupé sans rechargement des séries D1 (`RecomputeBatch`) ; durées de passe dans `PassSummary` (mesure avant toute parallélisation, §7).
- [x] Console : filtres, chips, tableau, actions groupées, détail 7 étapes. Écart : modale `<dialog>` native (la page Admin n'embarque plus Bootstrap). RECONCILE exige le kill switch levé (comme l'unitaire, qui passe par `execute`).
- [ ] Hors lot : parallélisation, mémoïsation du signal, job asynchrone, confirmation propriétaire groupée.
