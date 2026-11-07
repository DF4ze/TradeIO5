# Étude — Espace Expert (paramétrage Trend / Rainbow par actif)

Statut : cadrage en cours (mis à jour le 2026-11-06), rien d'implémenté. Journal de conception, pas une référence d'architecture.
Plan d'implémentation : [`../prompts/prompt-roadmap-espace-expert.md`](../prompts/prompt-roadmap-espace-expert.md).

## Décisions

**Accès et habillage**
- Page visible et servie uniquement si `displayMode = EXPERT` (contrôle serveur, redirection vers `/app` sinon). Plus de page `/user`.
- Coquille, menu latéral et charte de `/app`. Chemin sous `/app/expert/…`.
- Paramétrage uniquement : aucune notion de passe, planification, plan, exécution, audit.

**Onglets** : Trend · Rainbow · Affectation (« Lien » est déjà pris : provider × actif). Types de Trend : UP et DOWN.

**Droits**
- Presets système (Trend et Rainbow) : modifiables par l'ADMIN seul.
- L'USER consulte, **simule** et **duplique** un preset système en preset user, qu'il modifie librement ; sur un preset non modifiable on l'invite à dupliquer.
- Paramétrage propre à chaque utilisateur.

**Trame commune Trend / Rainbow**
1. En-tête : paramètres modifiables (verrouillés selon les droits).
2. Graphique Lightweight Charts (déjà embarqué, cf. Fiche Actif) qui formalise le preset : Trend = prix + phases UP/DOWN + bascules ; Rainbow = prix + bandes + achats/ventes simulés.
3. Fenêtre de résultats = équivalent du tableau en bas à gauche du pine (`rainbow_dca_v3_atr_moon.pine`, source de vérité), calculé par le rejeu Java.
- Onglet Affectation : même graphique avec phases UP/DOWN et preset Rainbow actif sur chaque phase.

**Simulation**
- Hors régime : position gelée (ni achat, ni vente, aucun capital ajouté), reprise telle quelle à la phase suivante du régime.
- Bouton « Simuler » (pas de recalcul à chaque frappe), brouillon distinct du preset enregistré.
- Un preset Rainbow se simule sur un Trend choisi (preset Trend + signal UP/DOWN) ; par défaut le Trend affecté.
- Un preset Rainbow UP ne simule que les phases UP : en phase DOWN, rien n'est affiché ni compté. Les résultats concernent ce seul preset.
- Si les données de l'actif n'ont jamais été chargées, la simulation les récupère : fetch via le pipeline existant (cache DB, fallback autre provider), avec garde-fous (un chargement à la fois par actif/source, limites de débit par utilisateur, progression affichée) pour ne pas déclencher de ban.
- Éligibilité dynamique : l'historique requis se déduit du preset (fenêtres et périodes d'indicateurs : SMA, ATR, fenêtres Trend, ATH…). Simulation refusée avec message explicite tant que les points disponibles sont insuffisants. Aucun indicateur n'est calculé avec moins de données que sa période.

**Nouvel actif**
- Icônes embarquées dans l'application (monogramme en secours) ; nom en cache DB.
- Un actif exploré mais jamais affecté reste en base (statut « simulable ») ; nettoyage reporté (cf. TODO).

**Actif et provider**
- Preset rattaché à l'**actif** ; l'enregistrement mémorise aussi le **provider** des tests. Il sert à recharger le provider pour de nouvelles simulations et à faire le lien lors du binding.
- L'onglet Affectation signale tout écart entre le provider du preset et celui des liens réels de l'utilisateur.
- Le sélecteur de provider d'un actif permet de visualiser et simuler sur ses candles (PAXG diffère notablement selon le provider).
- Duplication d'un preset depuis un autre actif possible.
- Le moteur (binding) ne tourne que sur les actifs dont les stratégies sont **entièrement affectées** (Trend + Rainbow UP + Rainbow DOWN).

## Constat sur l'existant

- Le modèle Trend / Rainbow / Affectation est en place (lot 1, section suivante). Reste à construire : API `/api/expert/*`, droits USER / ADMIN, écrans, simulation.
- Actifs BTC / ETH / PAXG : seeds d'`AssetInitializer` et commentaires d'entités. `Asset` = symbole, nom, décimales (pas d'icône).
- Découverte d'actifs possible : `InstrumentCatalog` tient en base la liste des paires spot par exchange (un appel public par exchange : Kraken, OKX, Bitvavo, Gate.eu, One Trading, Revolut X) avec `tickSz` / `lotSz` / `minSz`. Binance n'y figure pas ; les sources de données de marché (`MarketDataSource`) et les exchanges ne sont pas les mêmes ensembles.
- `AssetProvider.maxHorizonDays` borne l'historique de certains providers (ex. Kraken) : l'éligibilité doit se juger par source.

## Modèle (décidé, en place)

- **`TrendPreset`** : réglages Trend typés (fenêtres, seuils, confirmation, SMA / ATR, mèches, `rangeMapping`), actif, origine (SYSTEM | USER), propriétaire, révision. Un preset de base par actif (`baseRole TREND`).
- **`RainbowPreset`** : `RainbowAtrConfig` + fenêtre d'analyse, actif, origine, propriétaire, révision. **Neutre** : c'est l'Affectation qui l'installe sur UP ou sur DOWN (le même preset peut occuper les deux emplacements). Deux presets de base par actif (`RAINBOW_BULL`, `RAINBOW_BEAR`).
- **`AssetAffectation`** (user, actif) : un `TrendPreset`, un `RainbowPreset` UP, un `RainbowPreset` DOWN, `enabled`. Complète = exploitable. Le binding la référence (via user + actif). L'état moteur (`RainbowLiveEngineState`), les runs et l'historique `RainbowLivePresetEvent` lui sont rattachés ; changer ses presets (`assign`) purge les états moteur, qui se ré-amorcent à la passe suivante.
- 9 presets système de base (3 par actif), créés au démarrage et jamais modifiés ; les anciens presets utilisateur sont migrés vers des Affectations au démarrage (cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md)).
- Provider de référence des presets : non porté par le modèle actuel (à décider avec l'écran).

## API (esquisse)

- `GET /api/expert/assets` : catalogue (statut découvert / simulable / exploitable, providers, éligibilité).
- `/api/expert/trend-presets` et `/api/expert/rainbow-presets` : liste filtrée par actif, création, modification (droits), `POST /{id}/duplicate`, suppression.
- `GET|PUT /api/expert/affectations/{actif}`.
- `POST /api/expert/simulations` (type Trend | Rainbow, preset ou brouillon, actif, provider, Trend de référence, période) : tâche asynchrone si des données sont à charger (`202` + identifiant) ; `GET /api/expert/simulations/{id}` : progression puis résultat (candles, régimes, bandes, évènements, métriques, éligibilité).
- Cœur de simulation existant : `RainbowAtrReplay.run(ds, sets, setOfBar, …)` avec `RainbowSetSelector.select(régimes, rangeMapping)`. Simulation d'un preset sur son seul régime : il faut un indice « barre inactive » (position gelée) que le rejeu n'a pas aujourd'hui.

## Fenêtre de résultats : lignes du pine

Toutes reprises pour commencer, tri ultérieur. Source : tableau en bas à gauche de `rainbow_dca_v3_atr_moon.pine`, à rattacher à `RainbowAtrResult` (+ config du preset).

| # | Ligne du pine | Source |
|---|---|---|
| 0 | Paramétrage | actif / preset simulé |
| 1 | Plage visible | période simulée |
| 2 | Total $ investi | `invested` |
| 3 | Total $ vendu | `saleProceeds` |
| 4 | Total $ restant | `currentValue` |
| 5 | Gain total | `totalGain()` |
| 6 | Somme gains réalisés | `realizedGain` |
| 7 | PnL % réel | `realizedPercent()` |
| 8 | PnL % potentiel | `potentialPercent()` |
| 9 | PnL % potentiel DCA fixe | `fixedPnlPercent()` |
| 10 | Modulation ATH (distance fin de plage) | `lastAthDistance` + réglage ATH |
| 11 | To the moon | `moonModeLast` |
| 12 | Bag protégé | `moonReserveQty × lastPrice` |
| 13 | Réserve moon | réglage du preset (cliquet / figée) |

Le pine rejoue sur la **plage visible** (état vide au début de la plage). Un résultat suivant le zoom du graphique demande un nouveau rejeu serveur par plage ; à défaut, sélecteur de plage explicite. Le pine affiche aussi un histogramme de taille de position (bas du graphique), non repris pour l'instant.

## Graphique et plage de rejeu

- Base du graphique v1 : le rejeu Java existant (`tools/rainbow-replay/replay-template.html`, généré par `RainbowAtrReplayMain`, Lightweight Charts). Il affiche déjà : prix + bornes ATR/zones/ATH, marqueurs achat/vente/Bull-Bear/To the moon/stop moon, courbes PnL, exposition actif/stable/total, taille de position (bag) et réserve moon, détail par jour, tableau des jeux.
- Reprise en v1 : tout ce qui précède, taille du bag incluse. Le moteur reste côté Java (aucun recalcul en JS).
- Différence avec le rejeu : une seule simulation par requête (pas les 7 modes), servie par l'API.
- Plage : fixe et définie par l'utilisateur (sélecteur début/fin), comme le pine ; ATH et machine moon calculés sur tout l'historique, démarrage à froid en début de plage.

## Chantier à part : découverte et création des actifs

Hors Espace Expert (l'Espace Expert le consomme). À l'ajout d'un provider : récupérer les actifs qu'il propose, avec toutes les données nécessaires (nom, décimales, paires). Décimales en **mode automatique** à partir du `lotSz` du catalogue d'instruments (surcharge Admin possible), avec fallback sur un autre provider quand le premier ne les fournit pas. Icônes embarquées, noms en cache. Étude dédiée à ouvrir ; l'Espace Expert suppose seulement qu'un actif découvert existe et porte ses données.

## Décisions complémentaires

- Preset Rainbow neutre ; l'Affectation définit le régime (UP ou DOWN). Pour simuler un preset, on choisit le preset Trend de référence et le signal UP ou DOWN.
- L'indicateur Trend final ne renvoie que **UP / DOWN**. Le `rangeMapping` (RANGE → précédent / UP / DOWN) reste un réglage interne du `TrendPreset` qui résout RANGE avant la sortie. Barres sans régime encore résolu (début de série) : inactives.
- Presets système modifiables directement par le rôle ADMIN (trace dans `admin_action_log`).
- **Presets de base BTC / ETH / PAXG à conserver impérativement.** Leurs valeurs sont dans le code (`RainbowAtrPresets` : jeux « Perso Bear / Bull » ; `RainbowLiveTrendConfigs.defaults` ; `RainbowLiveDefaultPresets`), semées par `RainbowAssetStrategyInitializer` (jamais écrasées ensuite, System les modifie en base). Le nouvel initializer sème 1 `TrendPreset` + 2 `RainbowPreset` système par actif à partir de ces constantes. Aucune comparaison avec la base n'est nécessaire (valeurs non retouchées en base). Seuls les presets de base sont repris ; les presets utilisateur vivent en base uniquement, jamais en dur dans le code.

## Points ouverts

- Cycle de vie d'un actif : découvert → données chargées (simulable) → stratégies complètes (exploitable) → lié à un binding. La création / découverte relève du chantier à part ci-dessus.
- Résultats suivant le zoom (rejeu serveur par plage) ou sélecteur de plage explicite.

## TODO (non à implémenter maintenant)

- Nettoyage des actifs explorés jamais affectés (cf. [`../known-gaps/nettoyages.md`](../known-gaps/nettoyages.md)).
- Vue Admin : quel preset est affecté à quel utilisateur.
