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
- Hors régime (barre inactive) : aucun NOUVEL armement, achat de zone, DCA fixe ni capital ajouté ; l'automate To the moon, les sorties moon et les trailing stops (achat et vente) déjà armés continuent de vivre, pour garder le réalisme (un stop armé avant la sortie du régime peut se déclencher hors régime). Le DCA fixe de comparaison n'investit que sur les barres actives. Plage par défaut : 24 derniers mois ; ATH et automate moon calculés sur tout l'historique.
- Bouton « Simuler » (pas de recalcul à chaque frappe), brouillon distinct du preset enregistré.
- Un preset Rainbow se simule sur un Trend choisi (preset Trend + signal UP/DOWN) ; par défaut le Trend affecté.
- Un preset Rainbow UP ne simule que les phases UP : en phase DOWN, rien n'est affiché ni compté. Les résultats concernent ce seul preset.
- Si les données de l'actif n'ont jamais été chargées, la simulation les récupère : fetch via le pipeline existant (cache DB, fallback autre provider signalé), avec garde-fous (un chargement à la fois par actif/source, une simulation active par utilisateur, limites de débit, progression) pour ne pas déclencher de ban.
- Résultats : calcul à la demande conservé en mémoire côté serveur (30 min, un par utilisateur / type / actif, relancé en remplacement). Le front marque un résultat périmé dès qu'un réglage change et relance.
- Éligibilité dynamique : points D1 requis = max(chaud Rainbow SMA/ATR + 1, chaud Trend, fenêtre d'analyse × 30 j), jugés par provider (bougies en cache + profondeur D1 du provider ; `maxHorizonDays` ne vaut que pour l'H1). Simulation refusée avec message explicite tant que les points disponibles sont insuffisants. Aucun indicateur n'est calculé avec moins de données que sa période.

**Nouvel actif**
- Icônes embarquées dans l'application (monogramme en secours) ; nom en cache DB.
- Un actif exploré mais jamais affecté reste en base (statut « simulable ») ; nettoyage reporté (cf. TODO).

**Actif et provider**
- Preset rattaché à l'**actif** ; il porte aussi un **provider de référence** (`referenceProvider`, optionnel, doit porter l'actif). Il sert à recharger le provider pour de nouvelles simulations et à faire le lien lors du binding.
- L'onglet Affectation signale tout écart entre le provider du preset et celui des liens réels de l'utilisateur.
- Le sélecteur de provider d'un actif permet de visualiser et simuler sur ses candles (PAXG diffère notablement selon le provider).
- Duplication d'un preset depuis un autre actif possible.
- Le moteur (binding) ne tourne que sur les actifs dont les stratégies sont **entièrement affectées** (Trend + Rainbow UP + Rainbow DOWN).

## Constat sur l'existant

- Le modèle Trend / Rainbow / Affectation est en place (lot 1, section suivante). L'API `/api/expert/*` (presets, Affectations, droits USER / ADMIN) est en place (lot 2). Le moteur de simulation et son API sont en place (lot 3). Les écrans (`/app/expert/{trend,rainbow,affectation}`) sont en place (lot 4), ainsi que `GET …/{id}/impact` (ADMIN) pour l'impact d'une modification de preset SYSTEM. Choix : zones inactives en gris clair, événements trailing stop / To the moon / stop moon visibles ; panneaux prix + poche + PnL ouverts, exposition repliée, options mémorisées par type d'écran.
- Actifs BTC / ETH / PAXG : seeds d'`AssetInitializer` et commentaires d'entités. `Asset` = symbole, nom, décimales (pas d'icône).
- Découverte d'actifs possible : `InstrumentCatalog` tient en base la liste des paires spot par exchange (un appel public par exchange : Kraken, OKX, Bitvavo, Gate.eu, One Trading, Revolut X) avec `tickSz` / `lotSz` / `minSz`. Binance n'y figure pas ; les sources de données de marché (`MarketDataSource`) et les exchanges ne sont pas les mêmes ensembles.
- `AssetProvider.maxHorizonDays` borne l'historique de certains providers (ex. Kraken) : l'éligibilité doit se juger par source.

## Modèle (décidé, en place)

- **`TrendPreset`** : réglages Trend typés (fenêtres, seuils, confirmation, SMA / ATR, mèches, `rangeMapping`), actif, origine (SYSTEM | USER), propriétaire, révision. Un preset de base par actif (`baseRole TREND`).
- **`RainbowPreset`** : `RainbowAtrConfig` + fenêtre d'analyse, actif, origine, propriétaire, révision. **Neutre** : c'est l'Affectation qui l'installe sur UP ou sur DOWN (le même preset peut occuper les deux emplacements). Deux presets de base par actif (`RAINBOW_BULL`, `RAINBOW_BEAR`).
- **`AssetAffectation`** (user, actif) : un `TrendPreset`, un `RainbowPreset` UP, un `RainbowPreset` DOWN, `enabled`. Complète = exploitable. Le binding la référence (via user + actif). L'état moteur (`RainbowLiveEngineState`), les runs et l'historique `RainbowLivePresetEvent` lui sont rattachés ; changer ses presets (`assign`) purge les états moteur, qui se ré-amorcent à la passe suivante.
- 9 presets système de base (3 par actif), créés au démarrage et jamais modifiés ; les anciens presets utilisateur sont migrés vers des Affectations au démarrage (cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md)).
- **Provider de référence** : porté par chaque preset (`referenceProvider`, nullable) ; copié à la duplication seulement si l'actif cible le porte.

## API

**En place (lot 2, contrat : [`../api/rest-endpoints.md`](../api/rest-endpoints.md))**
- `GET /api/expert/assets` : catalogue (statut découvert / simulable / exploitable, providers, éligibilité).
- `/api/expert/trend-presets` et `/api/expert/rainbow-presets` : liste filtrée par actif, création, modification (droits), `POST /{id}/duplicate`, suppression.
- `GET|PUT /api/expert/affectations/{actif}` (l'écart de provider est renvoyé en information).

**Simulations (lot 3, contrat : [`../api/rest-endpoints.md`](../api/rest-endpoints.md))**
- `POST /api/expert/simulations` (202 + identifiant), `GET /{id}` (progression puis résultat), `DELETE /{id}`. Types : `TREND` (Trend seul), `RAINBOW` (un preset Rainbow sur le seul régime choisi d'un Trend), `AFFECTATION` (Trend + Rainbow UP / DOWN, comme le live). Cœur : `RainbowAtrReplay` avec `RainbowSetSelector`-équivalent par régime ; barre inactive = `RainbowAtrReplay.inactive(k)`.

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
- Différence avec le rejeu : une seule simulation par requête (pas les 7 modes), servie par l'API ; les colonnes sont les mêmes (`RainbowReplayColumns.COLS`).
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
