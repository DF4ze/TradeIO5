# Prompt d'implémentation — Espace Expert, lot 2 : services et API des presets / Affectations, droits

Contexte : TradeIO-5 (Spring Boot / JPA / Thymeleaf / Lombok, Java 21, Maven). Lire dans cet ordre avant toute chose :
1. `docs/README.md` (la doc doit rester à jour : tout ajout, suppression ou modification d'architecture est documenté, sans historique ; ce qui n'est plus valable est supprimé).
2. `docs/etudes/etude-espace-expert.md` (décisions, modèle en place, API esquissée).
3. `docs/prompts/prompt-roadmap-espace-expert.md` (règles transverses ; tu fais le **lot 2**).
4. `docs/architecture/08-rainbow-live.md` (modèle `TrendPreset` / `RainbowPreset` / `AssetAffectation` livré au lot 1, migration, purge des états moteur) et `docs/api/rest-endpoints.md`.
5. `docs/known-gaps/nettoyages.md` (y renvoyer tout nettoyage différé).

## Objectif

Exposer en API REST la consultation, la création, la duplication, la modification et la suppression des presets Trend / Rainbow, et la lecture / l'écriture de l'Affectation par actif, avec les droits USER / ADMIN et la restriction au mode EXPERT. **Aucun écran** (lot 4) et **aucune simulation** (lot 3) dans ce lot.

## État de départ (lot 1 livré)

- Entités `TrendPreset`, `RainbowPreset` (champs : `assetSymbol`, `referenceProvider`, `name`, `origin` = `PresetOrigin` SYSTEM | USER, `owner`, `baseRole` = `BasePresetRole`, `revision`, `createdAt` / `updatedAt`, réglages typés ; `RainbowPreset` = `RainbowAtrConfig` + `analysisWindowMonths`), `AssetAffectation` (user, actif, Trend + Rainbow UP + Rainbow DOWN, `enabled`). Contraintes d'unicité (propriétaire + actif + nom ; un preset de base par actif et rôle).
- Services : `AssetAffectationService` (`ensureAffectations`, `forAsset`, `list`, `get`, `setEnabled`, `assign` qui vérifie actif et propriété des presets et **purge les états moteur** si l'un des trois change), `ExpertBasePresetService`, `AffectationConfigResolver`, `RainbowLivePresetEventService`.
- Admin : `AdminActionLog` / `AdminActionLogService` avec l'enum `AdminAction` (à compléter).
- Mode d'affichage : `DisplayMode` (SIMPLE | ADVANCED | EXPERT) lu via `UserPreferencesService.Preferences`.
- `reference_provider` existe déjà sur les deux entités de preset (nullable). L'étude dit encore « non porté » : corriger l'étude.

## Décisions déjà prises (ne pas rouvrir)

**Accès**
- Tout `/api/expert/**` exige l'authentification **et** `displayMode = EXPERT` côté serveur (sinon 403 avec un code d'erreur explicite, pas une redirection). Défaut retenu : l'ADMIN est soumis à la même règle (il active le mode Expert comme les autres).
- Presets `SYSTEM` : lecture et simulation pour tous les utilisateurs Expert, **modification réservée au rôle ADMIN** (`hasRole('ADMIN')`), jamais supprimables, jamais renommés au point de casser leur `baseRole`. Presets `USER` : visibles et modifiables par leur seul propriétaire ; un utilisateur ne voit jamais les presets USER d'un autre.
- Un USER qui tente de modifier un preset SYSTEM reçoit une erreur dédiée qui l'invite à dupliquer (code d'erreur stable exploitable par l'écran du lot 4).

**Modification d'un preset utilisé**
- Preset USER modifié alors qu'une Affectation de son propriétaire l'utilise : **reset moteur immédiat** (même règle que `assign` : états purgés, passe suivante repart propre), événement `RainbowLivePresetEvent` enregistré.
- Preset SYSTEM modifié par un ADMIN : **propagation à toutes les Affectations qui l'utilisent + reset moteur** pour chacune ; une ligne `AdminActionLog` (nouvelle valeur `AdminAction.PRESET`, cible `trend-preset:<id>` / `rainbow-preset:<id>`) avec l'ancien / nouveau contenu résumé et le **nombre d'Affectations touchées** ; événement de preset par utilisateur concerné.
- Chaque modification incrémente `revision`. Concurrence optimiste : la requête de modification porte la `revision` lue ; décalage ⇒ 409.

**Suppression**
- Preset USER encore utilisé par une Affectation ⇒ **409** listant les Affectations qui l'utilisent (actif, rôle UP / DOWN / Trend) ; sinon suppression. Presets SYSTEM jamais supprimables (403).

**Duplication**
- `POST …/{id}/duplicate` crée un preset USER du caller, depuis n'importe quel preset visible (SYSTEM ou le sien), **sur le même actif ou sur un autre actif** (paramètre `assetSymbol` cible). Nom par défaut dérivé de l'original, unique pour (propriétaire, actif) (suffixe incrémental). `referenceProvider` copié si ce provider porte l'actif cible, sinon vide. La duplication d'un preset ne l'affecte jamais d'elle-même.

**Provider de référence**
- Éditable sur chaque preset ; validé contre les providers qui portent l'actif (`AssetProvider`). Utilisé plus tard pour la simulation (lot 3) et le lien au binding.
- `GET` d'une Affectation renvoie un **signalement d'écart de provider** : pour chacun des presets de l'Affectation, comparer son `referenceProvider` avec les providers des liens (`RainbowLiveBinding`) de l'utilisateur sur l'actif ; l'écart est une information, jamais un blocage.

## API à livrer

Préfixe `/api/expert`, JSON, DTO dédiés (ne pas exposer les entités), erreurs mappées dans le style de `RainbowLiveControllerAdvice` :

- `GET /assets` : actifs proposés (pour l'instant ceux dont les presets de base existent), providers qui les portent (avec `maxHorizonDays`), statut `exploitable` / `simulable` calculé depuis l'Affectation de l'utilisateur ; champ d'éligibilité présent mais renseigné par le lot 3 (`null` / « non évalué » d'ici là). Ne pas coder la découverte d'actifs (chantier à part).
- `GET /trend-presets?asset=` et `GET /rainbow-presets?asset=` : presets SYSTEM + USER du caller pour l'actif, avec origine, propriétaire (booléen « à moi »), `baseRole`, `revision`, `editable` (selon rôle et propriété) et les Affectations qui les utilisent (pour l'utilisateur courant).
- `GET /trend-presets/{id}` et `GET /rainbow-presets/{id}` : détail complet des réglages.
- `POST` (création d'un preset USER sur un actif, valeurs de départ = copie d'un preset donné ou défauts), `PUT /{id}` (modification, droits et concurrence ci-dessus), `DELETE /{id}`, `POST /{id}/duplicate`.
- `GET /affectations` (toutes celles de l'utilisateur), `GET /affectations/{actif}` (avec écart de provider) et `PUT /affectations/{actif}` (Trend, Rainbow UP, Rainbow DOWN ; s'appuie sur `AssetAffectationService.assign`, mêmes contrôles d'actif et de propriété, purge moteur). L'activation (`enabled`) reste gérée par les endpoints existants ; ne pas la dupliquer.
- Validation des réglages : réutiliser les validations existantes du Rainbow / Trend live s'il y en a ; sinon des bornes saines (périodes > 0, seuils cohérents `exit ≤ enter`, multiplicateurs positifs…) avec messages d'erreur par champ. Pas de modification de la logique du moteur.

## Ce que tu dois faire

1. Cartographier : `RainbowLiveController`, `RainbowLiveControllerAdvice`, `RainbowLiveBindingController`, `UserPreferencesController`, `AdminActionController`, configuration de sécurité ; repérer les conventions (DTO en `record`, gestion des erreurs, `@PreAuthorize`, utilisateur courant).
2. Garde EXPERT : composant réutilisable (interceptor / filtre ou méthode de service appelée par les contrôleurs) qui lit `displayMode` ; réutilisable par les écrans du lot 4.
3. Services : `TrendPresetService` et `RainbowPresetService` (ou un service commun si le code se mutualise proprement — mutualiser plutôt que dupliquer) pour lister / lire / créer / modifier / dupliquer / supprimer avec droits, concurrence, propagation et reset moteur ; compléter `AdminAction` et `AdminActionLogService` pour tracer les modifications de presets SYSTEM.
4. Contrôleurs `/api/expert/**` + DTO + mapping d'erreurs (403 mode non EXPERT, 403 preset SYSTEM non modifiable par un USER avec code « dupliquer », 404 hors périmètre propriétaire, 409 concurrence / suppression d'un preset utilisé, 400 validation).
5. Tests : droits (USER vs ADMIN, mode non EXPERT, presets d'un autre utilisateur invisibles), modification USER → reset moteur, modification SYSTEM → propagation + reset + ligne d'audit avec le décompte, suppression refusée tant qu'utilisé, duplication (même actif, autre actif, nom unique, provider), `assign` via l'API, écart de provider, concurrence optimiste, non-régression des endpoints Rainbow live existants.
6. Doc : `docs/api/rest-endpoints.md` (nouveaux endpoints), `docs/architecture/08-rainbow-live.md` (services, droits, règle de reset / propagation), étude (provider porté par les presets, API passée en « décidé », points tranchés), sans historique.

## Hors périmètre

Simulation et éligibilité calculée (lot 3), écrans et menu (lot 4), branchement live / nettoyage final (lot 5), découverte d'actifs (chantier à part), vue Admin « quel preset chez quel utilisateur » (TODO). Ne pas relancer `RainbowAtrReplayMain` sans accord de Clem (coûteux).

## Critères d'acceptation

- Build vert (MCP JetBrains), tests verts ; annoncer le résultat réel, ne pas supposer.
- Un USER ne peut jamais modifier ni supprimer un preset SYSTEM, ni voir / toucher le preset d'un autre ; un ADMIN peut modifier un SYSTEM et chaque modification est tracée dans `admin_action_log` avec le nombre d'Affectations touchées.
- Toute modification d'un preset utilisé purge les états moteur des Affectations concernées et enregistre l'événement.
- Aucune requête exchange déclenchée par ces endpoints.
- Doc à jour, rien d'historisé, nettoyages différés consignés dans `docs/known-gaps/nettoyages.md`.

## Style (préférences de Clem)

`isEmpty()` / `getFirst()` / `getLast()` ; `assertTrue` / `assertFalse` directs ; pas de variables locales redondantes ; constantes métier mutualisées plutôt que dupliquées ; valeurs par défaut uniquement dans les Initializers.
