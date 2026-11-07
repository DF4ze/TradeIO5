# Roadmap — Espace Expert (paramétrage Trend / Rainbow par actif)

**Statut : lots 1 à 5 réalisés (2026-11-07).** État courant : [`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md) et [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md) ; reste ouvert : [`../etudes/etude-espace-expert.md`](../etudes/etude-espace-expert.md). Trace de la réflexion conservée ci-dessous ; les prompts de lot exécutés ne sont plus à jour.

## Règles transverses (tous les lots)

- Doc à jour dans le même lot (`docs/README.md` → architecture) : pas d'historique, supprimer ce qui n'est plus valable.
- Valeurs par défaut (presets de base) uniquement dans les Initializers, jamais écrasées ensuite ; presets utilisateur en base seulement, jamais en dur.
- Le pine `tools/pine/rainbow_dca_v3_atr_moon.pine` reste la source de vérité de la logique Rainbow.
- Style : `isEmpty()` / `getFirst()` / `getLast()`, `assertTrue` / `assertFalse` directs, pas de locales redondantes, constantes métier mutualisées.
- Tout besoin de nettoyage différé → [`../known-gaps/nettoyages.md`](../known-gaps/nettoyages.md).
- Aucune requête exchange en dehors du pipeline de cache DB existant ; build via le MCP JetBrains, tests lancés par le lot et résultat annoncé.
- Poser les questions ouvertes au démarrage du lot avant de coder (ne pas deviner).
- Le graphique de référence est le rejeu `tools/rainbow-replay/` (`RainbowAtrReplayMain`) ; ne pas relancer sa génération sans accord (coûteux).

## Vue d'ensemble

| Lot | Contenu | Dépend de |
|---|---|---|
| 1 ✅ | Modèle `TrendPreset` / `RainbowPreset` / `AssetAffectation` + presets de base + migration des données + moteur live lu via l'Affectation + retrait `ui-mode` et `FIXED` (prompt : `prompt-implementation-espace-expert-lot1.md`) | — |
| 2 ✅ | Services et API presets / affectations, droits USER / ADMIN (prompt : `prompt-implementation-espace-expert-lot2.md`) | 1 |
| 3 ✅ | Moteur de simulation (barre inactive, plage, chargement de données, éligibilité ; prompt : `prompt-implementation-espace-expert-lot3.md`) | 1 et 2 |
| 4 ✅ | Écrans `/app/expert/{trend,rainbow,affectation}` + suppression `/user` (prompt : `prompt-implementation-espace-expert-lot4.md`) | 2, 3 |
| 5 ✅ | Nettoyage, code mort, doc finale (prompt : `prompt-implementation-espace-expert-lot5.md`) | 1 à 4 |
| Hors lots | Découverte et création des actifs | — |

## Lot 1 — Modèle et presets de base

**Objectif** : remplacer `RainbowLivePreset` / `RainbowLiveTrendConfig` / `RainbowAssetStrategy` par le modèle cible.

- Entités : `TrendPreset` (réglages Trend typés incl. `rangeMapping`, actif, provider de référence, origine SYSTEM|USER, révision), `RainbowPreset` (`RainbowAtrConfig` + fenêtre d'analyse, actif, provider, origine, révision ; **neutre**, pas de type UP/DOWN), `AssetAffectation` (user, actif → 1 Trend + 1 Rainbow UP + 1 Rainbow DOWN ; complète = exploitable).
- Initializer : sème par actif BTC / ETH / PAXG 1 `TrendPreset` + 2 `RainbowPreset` système à partir des constantes du code (`RainbowAtrPresets` Perso Bear/Bull, `RainbowLiveTrendConfigs.defaults`, `RainbowLiveDefaultPresets`). Ne jamais écraser ensuite.
- Retirer `RainbowLiveUiMode`, l'endpoint `/ui-mode`, `RainbowLiveUserSettings.uiMode` (redondant avec `displayMode`) et le mode `FIXED`.
- Trend final : sortie UP / DOWN uniquement ; `rangeMapping` interne ; barres sans régime résolu = inactives.
- Schéma : Flyway préparé mais non activé → attention aux colonnes orphelines avec `ddl-auto=update` (incident `rainbow_live_run.day`) ; sauvegarder la base avant.

**À trancher au démarrage** : sort des presets utilisateur, bindings, états moteur (`RainbowLiveEngineState`) et historique (`RainbowLivePresetEvent`) existants (migration ou reprise à zéro) ; rattachement de l'état moteur à l'Affectation.

**Critères** : build vert, tests existants adaptés, presets de base présents après démarrage à froid, aucune référence restante à `ui-mode` / `FIXED`.
**Doc** : `docs/architecture` (Rainbow live, modèle), étude (passer le modèle en « décidé »).

## Lot 2 — Services et API

- `GET /api/expert/assets` (catalogue : statut découvert / simulable / exploitable, providers, éligibilité).
- `/api/expert/trend-presets`, `/api/expert/rainbow-presets` : liste par actif, création, modification, `POST /{id}/duplicate` (y compris depuis un autre actif), suppression.
- `GET|PUT /api/expert/affectations/{actif}` ; écart de provider signalé.
- Droits : presets SYSTEM modifiables par ADMIN seul (trace `admin_action_log`) ; USER lit, simule, duplique ; presets USER propres à l'utilisateur ; accès restreint à `displayMode=EXPERT` côté serveur.
- Le moteur live ne tourne que sur les actifs à Affectation complète.

**Critères** : tests de droits (USER vs ADMIN, mode non EXPERT refusé), tests de duplication et d'affectation.

## Lot 3 — Moteur de simulation

- Indice « barre inactive » dans `RainbowAtrReplay` : position gelée hors régime (ni achat, ni vente, aucun capital ajouté), tests de non-régression sur le rejeu existant (0 écart).
- Simulation Trend (phases UP/DOWN, bascules) et Rainbow (preset sur son seul régime, Trend de référence au choix : preset Trend + signal UP/DOWN, par défaut l'affecté).
- Plage définie par l'utilisateur (début/fin) ; ATH et machine moon sur tout l'historique, démarrage à froid en début de plage.
- Éligibilité dérivée du preset (périodes SMA, ATR, fenêtres Trend, ATH…) ; refus explicite si points insuffisants ; prise en compte de `AssetProvider.maxHorizonDays`.
- Chargement de données à la demande via le cache DB et le fallback existants, avec garde-fous (un chargement à la fois par actif/source, limite par utilisateur, progression) ; `POST /api/expert/simulations` asynchrone (`202`) + `GET /{id}`.
- Résultat = toutes les lignes du tableau du pine (cf. étude) + séries du graphique (candles, régimes, bandes, évènements, PnL, exposition, taille du bag / réserve moon).

**Critères** : parité avec `RainbowAtrReplayMain` sur un jeu fixe, tests inactif / plage / éligibilité / garde-fous.

## Lot 4 — Écrans

- Routes `/app/expert/{trend|rainbow|affectation}` dans le shell `/app` (menu et charte), entrée de menu visible seulement si `displayMode=EXPERT` ; suppression de `/user` et `userPage.html`.
- Trame commune Trend / Rainbow : en-tête de paramètres (verrouillés selon droits, invitation à dupliquer), graphique, fenêtre de résultats ; sélecteurs actif et provider ; bouton « Simuler » (brouillon distinct de l'enregistré) ; sélecteur de plage.
- Graphique basé sur `tools/rainbow-replay/replay-template.html` (prix + bornes ATR / zones / ATH, achats / ventes / Bull-Bear / To the moon / stop moon, PnL, exposition, **taille du bag** dès la v1, détail par jour) ; Lightweight Charts déjà embarqué.
- Onglet Affectation : graphique avec phases UP/DOWN et preset actif par phase, alerte d'écart de provider.

**Critères** : parcours USER (consulter, simuler, dupliquer, modifier sa copie) et ADMIN (modifier un système) vérifiés dans le navigateur.

## Lot 5 — Finalisation

- Suppression du code mort, passage de `docs/known-gaps/nettoyages.md`, doc d'architecture finale, étude réduite à ce qui reste ouvert.

## Hors lots / TODO

- Découverte et création des actifs (ajout de provider → actifs proposés, décimales auto via `lotSz` avec fallbacks, icônes embarquées, noms en cache) : étude dédiée.
- Vue Admin : quel preset est affecté à quel utilisateur.
- Nettoyage des actifs explorés jamais affectés.
- Sauvegarde DB segmentée (système / candles / settings utilisateur) : voir `docs/securite/remarques-audit.md`.
