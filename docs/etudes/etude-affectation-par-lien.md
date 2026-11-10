---
statut: journal
modifié_le: 2026-11-10
empreinte: 83558f81de
---
# Étude — Affectation choisie par lien (comblement du manque Lien ↔ Affectation)

Statut : **étude du 2026-11-10**, décisions de Clem du même jour intégrées (§5), aucun code. État actuel du code : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md). Modèle des liens : [`etude-liens-strategie-provider-actif.md`](etude-liens-strategie-provider-actif.md).

## 1. Constat

Observé en dev le 2026-11-10 : un actif BTC ajouté sur un nouvel exchange (lien créé, « activé » côté lien), puis « Lancer la passe » dans la console Admin ⇒ `0 traité(s), 0 ignoré(s), 0 erreur(s) · 21 ms`.

Cause : la passe ne joue que les **Affectations** `enabled` (`RainbowLiveExecutionService#collectAffectations`). Or :

- `RainbowLiveBindingService#create` utilise `affectationService.forAsset(user, actif)`, qui crée l'Affectation avec `enabled=false` et ne l'active jamais ;
- l'activation de l'Affectation (`PATCH /api/rainbow-live/affectations/{id}/enabled`, Espace Expert) est une action séparée que rien ne rappelle au moment du lien ;
- une Affectation désactivée ⇒ ses liens sont ignorés **sans aucun signal** (ni compteur « ignoré », ni erreur, ni alarme Docteur).

Deux problèmes distincts : (A) l'activation est portée par la mauvaise entité ; (B) le lien ne peut pas choisir quelle Affectation il utilise (une seule par `(user, actif)`).

## 2. État du code (vérifié)

| Élément | Aujourd'hui |
|---|---|
| `AssetAffectation` | unique `(user_id, asset_symbol)` ; `enabled` ; pas de nom (`label()` calculé « Up / Down ») ; porte runs (`RainbowLiveRun`, unique `(affectation, jour)`) et états moteur (`RainbowLiveEngineState`) |
| `RainbowLiveBinding` | FK `affectation` déjà présente (posée à la création) ; unique `(user, actif, wallet)` ; `LiveSlot` porte déjà `affectationId` |
| Signal | **calculé par Affectation**, partagé entre ses liens ; source des candles = lien le plus prioritaire ayant un exchange, WARN + « substitué » si les sources des liens diffèrent |
| Presets | portent déjà `referenceProvider` (provider de calibration) ; `ExpertAffectationService` renvoie déjà des `providerGaps` en information |
| Usages de `findByUserAndAssetSymbol` (suppose 1 Affectation/actif) | `AssetAffectationService` (`ensure`, `forAsset`, `assign`), `ExpertAssetService`, `SimulationRequestResolver`, `AbstractExpertPresetService` (`usedBy`), `AffectationMigrationService` |
| API Expert | `GET\|PUT /api/expert/affectations/{asset}` : clé = actif |

La FK `binding → affectation` existe donc déjà : le besoin est surtout de **rendre l'Affectation choisissable** et de **déplacer l'activation**.

## 3. Proposition

### 3.1 Activation dérivée des liens (corrige A)

- Suppression de `AssetAffectation.enabled` comme source de vérité : une Affectation est **jouée par la passe si au moins un lien non archivé la référence** (et si elle est complète).
- Le mode d'exécution du lien (`DISABLED` / `SIMULATION` / `ACTIVE`) reste le seul interrupteur d'**ordres** ; un lien `DISABLED` produit quand même signal + snapshot (c'est déjà l'intention : liens créés inactifs par l'onboarding).
- `collectAffectations` part des liens (`findByUser…ArchivedAtIsNull`) au lieu du flag.
- Visibilité : le résumé de passe et le Docteur signalent « N lien(s) sur une Affectation incomplète » (au lieu du silence actuel). Le message de la console affiche une aide quand 0 traité.
- **Décidé** : on joue le lien, pas l'Affectation. Une Affectation sans lien n'est jamais jouée (plus de run orphelin). Les runs historiques restent.

### 3.2 Affectations nommées, plusieurs par actif, choisies par le lien (traite B)

- `asset_affectation` : suppression de l'unique `(user, actif)` ; ajout de `name` (≤ 64, repli sur `label()` si nul) ; unique `(user, actif, name)`.
- Deux sortes d'Affectations : **Système** (trio des presets système de base de l'actif, suivis sans copie, à disposition de tous les utilisateurs, nommées « BTC Système », « ETH Système », « PAXG Système », non modifiables ni supprimables) et **Utilisateur** (propres à l'utilisateur, jamais partagées, nommées par lui). La définition Système est commune ; l'**état moteur et les runs restent par utilisateur** (une ligne `asset_affectation` par utilisateur, marquée `system=true`, créée par `ensureAffectations`), pour que le reset d'un lien ne touche pas les autres utilisateurs. Aucune Affectation n'est créée sans action de l'utilisateur, hormis l'instance Système matérialisée à son premier lien. Elle est rattachée par défaut au premier lien sur un actif.
- `binding.affectationId` modifiable (`PUT /bindings/{id}` et `POST` acceptent `affectationId` ; défaut = Affectation de base de l'actif). Contrôles : appartient à l'utilisateur, **même actif que le lien**, complète.
- Menu déroulant dans **Actifs gérés**, par lien. Liste = Affectations de l'actif uniquement, triée côté serveur :
  1. correspondance provider : tous les presets installés ont `referenceProvider` = provider du lien (puis correspondance partielle) ;
  2. Affectation de base (système) ;
  3. Affectations de l'utilisateur, par `updatedAt` décroissant.
  `GET /api/rainbow-live/affectations?asset=BTC&provider=REVOLUTX` renvoie `providerMatch` et l'ordre.
- Expert : les Affectations deviennent une liste par actif (créer, renommer, changer les presets, supprimer si non utilisée ⇒ sinon 409), les routes passent de `{asset}` à `{id}`.

### 3.3 Effets de bord

- **Source des candles** : avec une Affectation par provider, la source de référence devient exactement celle du lien ; le cas « source substituée » (WARN) disparaît pour ces liens.
- **Exposition** : inchangée, l'unicité `(user, actif, wallet)` du lien interdit deux liens du même actif sur un même wallet ⇒ pas de double exposition intra-wallet. Deux wallets du même actif avec deux Affectations = deux signaux indépendants (c'est le but).
- **Changement d'Affectation d'un lien** (depuis la page de l'actif) : **reset du moteur** sur la nouvelle Affectation : purge de ses états puis ré-amorçage par `RainbowAtrReplay.runFull` sur les données historiques à la passe suivante, comme `assign()`. Rien n'est bloqué et aucun ordre n'est refusé : la passe et l'exécution s'enchaînent, il n'y a donc pas d'évènement différé qui « traîne » ; le rejeu remet le moteur à jour. Le rejeu est déterministe : purger l'état d'une Affectation déjà utilisée par un autre lien de l'utilisateur redonne le même état. Événement `LIVE_SWITCH` (nom avant/après).
- **Historique** : `RainbowBandsQueryService`, `DecisionQueryService`, `OrderPlanService`, `OrderExecutor` lisent les runs via `binding.getAffectation()` ⇒ après changement, les bandes/décisions affichées suivent la **nouvelle** Affectation (cohérent avec le reset).
- **Presets en usage** (`PRESET_IN_USE`, `usedBy`, impact admin) : à adapter pour parcourir toutes les Affectations d'un actif.

### 3.4 Base de données (point d'attention)

Flyway est inactif, le schéma suit `ddl-auto=update` : retirer `@UniqueConstraint` **ne supprime pas l'index MySQL** `uk_asset_affectation_user_asset`, et retirer le champ `enabled` laisse une colonne `NOT NULL` sans défaut qui ferait échouer les insertions. SQL manuel à passer sur la base (dev puis VPS, après **sauvegarde de la base VPS**) avant démarrage de la nouvelle version : `DROP INDEX uk_asset_affectation_user_asset`, `DROP COLUMN enabled` ; colonne `name` et nouvel index créés par `ddl-auto`. À consigner dans [`../operations/flyway.md`](../operations/flyway.md).

## 4. Découpage proposé

- **Lot 1 — activation dérivée** (petit, ferme le trou) : §3.1 + `PATCH …/enabled` retiré + SQL `DROP COLUMN enabled` + tests (`RainbowLiveJobsTest`, `RainbowLiveExecutionServiceTest`, Expert/Affectations). Aucun changement d'UX utilisateur sauf message de passe.
- **Lot 2 — Affectations multiples nommées** : modèle (§3.2), API Expert par `id`, `binding.affectationId` modifiable, SQL `DROP INDEX`, adaptation des 5 usages de `findByUserAndAssetSymbol`.
- **Lot 3 — UI** : menu par lien dans Actifs gérés (`page-assets.js`, `fragments.html`), liste/création/renommage dans l'Espace Expert, avertissement de changement d'Affectation.

Documentation à mettre à jour avec chaque lot : `architecture/08-rainbow-live.md`, `architecture/13-espace-expert.md`, `api/rest-endpoints.md`, `operations/flyway.md`, `known-gaps/rainbow-dca-chantiers-ouverts.md`.

## 5. Décisions de Clem (2026-11-10)

Reprises dans [`../decisions/liens-strategie-provider-actif.md`](../decisions/liens-strategie-provider-actif.md) :
1. On joue le **lien** : une Affectation sans lien n'est jamais jouée.
2. Affectations **Système** (définition commune, rattachées par défaut au premier lien d'un actif) et **Utilisateur** (propres, non partagées) ; un utilisateur peut en créer plusieurs par actif, **nom unique par `(utilisateur, actif)`**, un lien n'en référence qu'**une**.
3. Changement d'Affectation d'un lien : reset du moteur et rejeu historique, sans blocage ni avertissement d'évènements différés.
4. Pas d'Affectation créée automatiquement hormis l'instance Système ; noms « BTC Système », « ETH Système », « PAXG Système ».

Restant : confirmer « définition Système commune, état moteur et runs par utilisateur » (§3.2).
