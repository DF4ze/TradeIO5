# Prompt d'implémentation — Espace Expert, lot 1 : modèle, presets de base, migration

Contexte : TradeIO-5 (Spring Boot / JPA / Thymeleaf / Lombok, Java 21, Maven). Lire dans cet ordre avant toute chose :
1. `docs/README.md` (la doc doit rester à jour : tout ajout, suppression ou modification d'architecture est documenté, sans historique ; ce qui n'est plus valable est supprimé).
2. `docs/etudes/etude-espace-expert.md` (décisions, modèle cible).
3. `docs/prompts/prompt-roadmap-espace-expert.md` (règles transverses et vue d'ensemble des lots ; tu fais le **lot 1**).
4. `docs/known-gaps/nettoyages.md` (y renvoyer tout nettoyage différé).

## Objectif

Remplacer le modèle actuel `RainbowLivePreset` / `RainbowLiveTrendConfig` / `RainbowAssetStrategy` par le modèle cible, **migrer les données existantes**, et garder le moteur live fonctionnel à l'identique (mêmes décisions, même résultat) à travers le nouveau modèle. Pas d'API ni d'écran Expert dans ce lot (lots 2 et 4).

## Décisions déjà prises (ne pas rouvrir)

- Entités :
  - `TrendPreset` : réglages Trend typés (fenêtres de régression, seuils, confirmation, SMA / ATR, mèches, `rangeMapping`), actif, **provider de référence**, origine `SYSTEM | USER`, propriétaire (null pour SYSTEM), révision.
  - `RainbowPreset` : `RainbowAtrConfig` + fenêtre d'analyse, actif, provider de référence, origine, propriétaire, révision. **Neutre** : aucun type UP/DOWN dans le preset.
  - `AssetAffectation` (user, actif) : 1 `TrendPreset` + 1 `RainbowPreset` UP + 1 `RainbowPreset` DOWN. Le même preset Rainbow peut occuper UP et DOWN. Complète = exploitable.
- L'indicateur Trend final ne renvoie que **UP / DOWN**. Le `rangeMapping` (RANGE → précédent / UP / DOWN) reste un réglage interne du `TrendPreset`. Barres sans régime résolu (début de série) : inactives.
- Le mode `FIXED` disparaît (l'Affectation est toujours complète). Le sélecteur `ui-mode` Rainbow (`RainbowLiveUiMode`, endpoint `/ui-mode`, `RainbowLiveUserSettings.uiMode`, usages dans `rainbow-live.js`, `demo.js`, `rainbowLive.html`, DTO) est supprimé : `displayMode` suffit.
- « Suivre / détacher » une stratégie Actif devient « affecter un preset système / dupliquer en preset user ».
- Presets de base : 1 `TrendPreset` + 2 `RainbowPreset` **système** par actif BTC / ETH / PAXG, semés par un Initializer à partir des constantes du code (`RainbowAtrPresets` jeux « Perso Bear / Bull », `RainbowLiveTrendConfigs.defaults`, `RainbowLiveDefaultPresets`), en s'inspirant de `RainbowAssetStrategyInitializer`. Jamais écrasés ensuite (modifiables par l'admin en base). Seuls ces presets de base sont dans le code ; les presets utilisateur vivent en base uniquement.
- **Migration en base** (pas de repartir à zéro) : chaque preset / binding / état moteur / historique existant est converti, rien n'est perdu. Cette migration est un runner idempotent au démarrage (ou équivalent), pas un script manuel.
- Schéma : Flyway reste préparé mais **non activé** ; on reste en `ddl-auto`. Attention aux colonnes orphelines non supprimées par `ddl-auto=update` (incident `rainbow_live_run.day` NOT NULL sans défaut) : lister les colonnes / tables devenues obsolètes et les traiter (nullable ou suppression via la migration), documenter le reste dans `docs/known-gaps/nettoyages.md`.
- Sauvegarde : rappeler à Clem de sauvegarder la base **avant** le premier démarrage avec la migration (cf. `docs/securite/remarques-audit.md`).

## Points à valider avec Clem avant de coder (poser les questions, ne pas deviner)

1. Presets existants en mode `FIXED` : quel `TrendPreset` leur donner dans l'Affectation migrée (Trend de base de l'actif ? ) et quels Rainbow sur UP / DOWN ?
2. Presets qui « suivent » une `RainbowAssetStrategy` : la migration les affecte aux presets système (comportement fidèle) ; confirmer.
3. Presets existants dont la config Bear / Bull a été modifiée par l'utilisateur : convertis en presets `USER` (nom d'origine conservé) ; confirmer le nommage.
4. Rattachement de `RainbowLiveEngineState` et de l'historique `RainbowLivePresetEvent` : à l'Affectation (user, actif) ? Que devient la clé de l'état au changement de preset d'une Affectation ?

## Ce que tu dois faire

1. **Cartographier** l'existant (≈ 75 fichiers touchent ces classes : entités et repositories sous `model/entity/dca/bench` et `repository/dca/bench`, `RainbowLivePresetService`, `RainbowLivePresetConfigResolver`, `RainbowAssetStrategyService`, `RainbowTrendLiveService`, `RainbowLiveBindingService`, `RainbowLiveQueryService`, contrôleurs `RainbowLive*`, services d'exécution / plan d'ordres / portfolio, tests). Produire la liste des points de contact avant de modifier.
2. Créer les entités, repositories, DTO internes et `TrendPreset` / `RainbowPreset` / `AssetAffectation` (avec contraintes d'unicité : une Affectation par (user, actif), un seul preset système par (actif, rôle de base)).
3. Initializer des presets de base + runner de migration idempotent (rejouable sans doublon, journalisé).
4. Faire lire le moteur live via l'Affectation : le point d'entrée naturel est `RainbowLivePresetConfigResolver` + `RainbowTrendLiveService` (Trend → UP / DOWN → config Rainbow du preset UP ou DOWN). Le binding référence l'Affectation (user + actif) au lieu d'un preset. Comportement strictement identique à l'existant : les résultats d'un jeu d'entrées donné ne changent pas.
5. Supprimer `ui-mode` et `FIXED` (code, DTO, JS, template, tests), puis le code devenu mort.
6. Adapter ou remplacer les tests existants ; ajouter des tests : initializer (idempotence, présence des 9 presets de base après démarrage à froid), migration (cas : suit la stratégie, preset modifié, FIXED, binding + état moteur), non-régression moteur (même sortie qu'avant sur un cas figé), unicité des Affectations.
7. Mettre à jour la doc d'architecture (Rainbow live, modèle de données, front `/app` pour le retrait de `ui-mode`) et `docs/etudes/etude-espace-expert.md` (modèle passé en « décidé », points tranchés).

## Hors périmètre

API `/api/expert/*`, droits USER / ADMIN, écrans Expert, simulation, barre inactive dans `RainbowAtrReplay`, suppression de `/user` : lots 2 à 4. Ne pas relancer `RainbowAtrReplayMain` sans accord de Clem (coûteux).

## Critères d'acceptation

- Build vert (MCP JetBrains), tests verts ; annoncer le résultat réel, ne pas supposer.
- Démarrage à froid : 9 presets système (3 actifs × 1 Trend + 2 Rainbow) présents, aucune erreur de schéma.
- Démarrage sur une base existante : tout est migré, moteur identique, relancer le démarrage ne duplique rien.
- Plus aucune référence à `ui-mode`, `RainbowLiveUiMode`, `FIXED` dans le code ni la doc.
- Doc à jour, rien d'historisé, nettoyages différés consignés dans `docs/known-gaps/nettoyages.md`.

## Style (préférences de Clem)

`isEmpty()` / `getFirst()` / `getLast()` ; `assertTrue` / `assertFalse` directs ; pas de variables locales redondantes ; constantes métier mutualisées plutôt que dupliquées ; valeurs par défaut uniquement dans les Initializers.
