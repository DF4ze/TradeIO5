# Étude — Bench automatique du Trend Mix sur un nouvel actif

Statut : analyse du 2026-10-09, **aucun code**. Prompt : [`../prompts/prompt-analyse-bench-auto-trend-mix-nouvel-actif.md`](../prompts/prompt-analyse-bench-auto-trend-mix-nouvel-actif.md). Contexte : [`../known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §1-2, [`../architecture/09-trend.md`](../architecture/09-trend.md).

## 0. Cadrage (réponses de Clem du 2026-10-09)

- Périmètre = **Trend Mix entier** : régression 3 fenêtres **et** SMA ± k×ATR (réglage de la SMA inclus, jugé séparément, voir K19-K20), composition « 1er des deux à switcher ». Le choix/réglage des jeux Bull/Bear est **hors périmètre**.
- Actif de validation : **ETH** ; **PAXG** en confirmation. BTC = référence (réglage manuel connu = `Params.defaults()`).
- Switchs/an : pas de plafond arbitraire → voir K1 (mesure relative à la volatilité).
- **Critère principal = qualité de la Trend en soi** (K1-K7). Le Rainbow piloté par la Trend (K8-K11, rejeu complet Trend Mix + Rainbow, jeux Bull/Bear fixes) sert de veto seulement.
- **Échelle visée : entre swing et cycle** → vérité terrain ZigZag ≈ 20-25 % (BTC), adaptatif à la volatilité des autres actifs.
- **Actif non tradable (K17/K18)** : le bench propose quand même un réglage, avec un avertissement visible dans le rapport (pas de refus).
- Sortie = proposition de `Params` validée par Clem avant tout preset. Aucune écriture automatique.

Principe directeur : **la Trend se juge d'abord en elle-même** (K1-K7), pas sur le gain du Rainbow. Raison : les jeux Bull/Bear BTC/ETH/PAXG ont été réglés à la main *avec* la Trend par défaut → un KPI de bout en bout favorise mécaniquement le réglage par défaut (biais in-sample). Le bout en bout sert de **veto**, pas de critère de classement.

## 1. KPI (point d'entrée)

Principes issus de la méthode manuelle de Clem sous TradingView (2026-10-09) :
- **Rapidité aux fronts majeurs** : la bascule doit suivre vite un sommet (ATH) et le début de la 1re grosse hausse après un creux.
- **Anti-flip-flop** : pas de Bear court au milieu d'un Bull ; un Bear d'environ 1 mois est toléré, plus court = à résorber.
- **Arbitrage rapidité / flip-flop** : un réglage qui résorbe un flip-flop mais déplace les bascules des fronts majeurs est rejeté (la perte de détection rapide coûte plus que le gain).
- **Asymétrie de sécurité** : un Bear à tort coûte du gain (ventes), un Bull à tort met le capital en risque → on préfère se tromper côté Bear. Les KPI pénalisent donc plus le Bull tardif/à tort que le Bear tardif/à tort.

**Repères de marché** (nécessaires pour les retards ; seul point arbitraire, d'où les garde-fous du §1.5) : deux niveaux de ZigZag sur clôtures D1, à seuil adaptatif `c × ATR% médian de l'actif` (c fixé pour que BTC retombe sur ≈ 20-25 % « swing » et ≈ 30-40 % « majeur »). Fronts majeurs = pivots du niveau « majeur » ; ceux qui sont des ATH sont signalés à part (poids ×2). Les KPI de flip-flop (K3-K4) **n'utilisent aucun repère** : ils se lisent sur la seule série de régimes. « Switch » = changement UP↔DOWN du Mix (RANGE n'est pas un switch).

### 1.1 Qualité de la Trend en soi

| # | KPI | Définition / formule | Seuil | Risque couvert |
|---|---|---|---|---|
| K1 | Nervosité normalisée | `switchs / pivots ZigZag « swing »` | zone [1,0 ; 2,5] (alerte hors zone) | Trend qui switche tous les 10 j, ou qui rate les retournements ; remplace un plafond de switchs/an (un actif volatil a plus de pivots) |
| K2 | Durée médiane de régime | médiane (jours) des segments UP/DOWN | ≥ 0,5 × défaut BTC (lot 1) | micro-régimes |
| K3 | **Flip-flop Bear en Bull** | segments Bear < 30 j encadrés par deux Bull : nombre/an et jours cumulés (1 mois = toléré) | à calibrer lot 1 sur le défaut BTC | le défaut que Clem résorbe à la main |
| K4 | **Flip-flop Bull en Bear** | idem, Bull < 30 j encadré par deux Bear ; **poids ×2** (Bull à tort = risque capital) | idem | achats mal placés |
| K5a | **Retard au sommet** | jours entre le front haut majeur (ATH signalé ×2) et la bascule DOWN ; part de la baisse déjà subie à la bascule (perte%) | à calibrer lot 1 ; critère le plus strict | Bull qui persiste après un sommet |
| K5b | **Retard au creux** | part de la 1re hausse (≥ X % depuis le creux majeur) déjà passée à la bascule UP | plus tolérant que K5a (asymétrie de sécurité) | Bear qui persiste après un creux |
| K6 | **Invariance des fronts** | bascules proches des fronts majeurs : déplacement (jours) quand un paramètre est perturbé d'un cran ; part de bascules qui bougent de > 3 j | ≥ 80 % de bascules stables | « une modification d'un moindre % fait tout bouger » : réglage instable rejeté |
| K7 | Accord équilibré | métrique spec §10.2 | information seulement (biais « couverture ») | Trend décorrélée du marché |
| K7b | **Exposition à contre-sens** | jours Bull pendant les baisses majeures (sommet→creux) vs jours Bear pendant les hausses majeures ; ratio pondéré 2:1 | Bull à contre-sens ≤ défaut | biais de sécurité |

**Arbitrage K3-K4 vs K5-K6** : pas de somme pondérée. Un candidat est préféré s'il réduit K3 sans dégrader K5a/K5b/K6 au-delà d'une marge fixée avec Clem au lot 1 (taux d'échange : « combien de jours de retard au sommet vaut un jour de flip-flop résorbé », décision de Clem avec exemples chiffrés issus du BTC).

### 1.2 Rainbow piloté par la Trend (veto, rejeu complet Trend Mix + Rainbow, jeux Bull/Bear **fixes** fournis, non réglés)

Une Trend n'a pas de gain en soi : K8-K11 mesurent ce que donne le Rainbow quand cette Trend choisit le jeu Bull/Bear.

| # | KPI | Définition | Seuil | Couvre |
|---|---|---|---|---|
| K8 | Résultat Rainbow vs DCA fixe | score A / excès du bench (capital déployé plafonné au budget DCA fixe) | ≥ défaut − tolérance | Trend qui dégrade le Rainbow |
| K9 | Pic d'exposition / capital déployé | max exposition, capital cumulé | ≤ 1,2 × défaut | réglage qui « achète » de la perf en déployant plus |
| K10 | Drawdown de valeur du portefeuille | max DD | ≤ défaut | risque |
| K11 | Jeu dégénéré | nb ventes et nb rachats sur la période | ≥ N (N = nb années) | « ne vend jamais » / « ne rachète jamais » |

### 1.3 Robustesse (le plus important)

| # | KPI | Définition | Seuil | Couvre |
|---|---|---|---|---|
| K12 | Walk-forward OOS/IS | train ancré expansif, test annuel, purge 10 j (même protocole que spec §10) ; ratio score OOS / IS | ≥ 0,6 ; ≥ 2/3 des folds ≥ défaut | surapprentissage |
| K13 | Plateau | chaque paramètre perturbé de ±10-20 % (cran voisin de grille) ; part des voisins qui restent aux vetos et perdent < 20 % du score | ≥ 80 % | pics isolés (leçon du bench Rainbow) |
| K14 | Stabilité inter-folds | écart relatif de chaque paramètre retenu d'un fold à l'autre | < 1 cran de grille sur ≥ 5 params/6 | réglage instable = bruit |
| K15 | Pire sous-période | K1, K5, K8 calculés séparément sur jambes haussières, baissières, latérales (ZigZag) | aucune sous-période sous le défaut − tolérance | bon en moyenne, catastrophique en bear |
| K16 | Ratio échantillon | paramètres libres / nb de régimes observés | ≤ 1/10 (6 libres → ≥ 60 régimes) | historique trop court |

### 1.3b Composants (régression / SMA)

| # | KPI | Définition | Seuil | Couvre |
|---|---|---|---|---|
| K19 | Ablation | K1-K7 de la régression seule, de la SMA seule et du Mix | le Mix doit être meilleur que chacun sur K5-K7 sans dégrader K1/K4 | un composant inutile ou mal réglé |
| K20 | Bascules dues à la SMA | part des switchs dont `source = SMA`, et part de ceux-ci proches d'un pivot ZigZag (± 25 % de la jambe) | information + alerte si > 50 % des bascules SMA tombent à contretemps | SMA (période, ATR, k) mal ajustée |

### 1.4 Garde-fous et avertissements

- Bornes de plausibilité par paramètre (grille §2), pas de valeur hors grille.
- **K17 tradabilité** : avertissement si ATR% médian < 1,5 % **ou** < 8 pivots ZigZag **ou** actif adossé (prix dans ±3 % d'un pivot de référence : stablecoin). Le bench propose alors quand même un réglage, mais le rapport l'affiche en tête comme **non fiable** (jamais en silence). À mesurer : PAXG (or) est probablement limite → c'est exactement le cas test de l'avertissement.
- **K18 historique minimal** : ≥ 3 ans D1 **et** ≥ 3 cycles complets (6 pivots) ; sinon avertissement « échantillon insuffisant ».

### 1.5 Références et agrégation

- **Baseline à battre** : Trend Mix `defaults()` appliqué tel quel. Un réglage spécifique n'est proposé que s'il **bat le défaut de façon significative** (voir Q2) ; sinon verdict « garder le défaut ».
- **Baselines « bêtes »** : (a) Bull-only, (b) Bear-only, (c) Trend aléatoire de même fréquence de switch (Monte-Carlo 200 tirages), (d) **jeu unique « Perso Generic »** (sans Trend). Règle (Clem) : le Rainbow piloté par la Trend avec `<ACTIF> Perso Bear/Bull` doit **battre (d)**, sinon la Trend n'apporte rien sur cet actif ; il doit aussi dépasser le 90e centile de (c). Point de vérification : « Perso Generic » existe dans le pine, mais `RainbowAtrPresets` ne porte que `bearBull(asset)` → à porter en Java (décidé, lot 0).
- **Agrégation** : cascade de vetos (K1-K6, K8-K11, K17-K18), puis classement des survivants sur **un seul** critère de robustesse (score de plateau K13 / OOS K12), jamais une somme pondérée. On retient le **centre du plus grand plateau**, pas l'argmax. Effet : aucun KPI ne peut être sacrifié au profit d'un autre, et on évite le « paramétrage qui dérive ».
- **Pouvoir discriminant (contrôle obligatoire, lot 1)** : un critère ne doit être ni éliminatoire pour tous, ni vert pour tous. Pour chaque veto, la part de la grille qui le passe doit rester dans [10 % ; 90 %] ; sinon le seuil est recalé sur les percentiles du voisinage du défaut BTC. Les vetos ne rejettent que les cas franchement dégénérés ; le reste est classé par scores continus.
- **Calibration des seuils** : les seuils « relatifs au défaut » (K2, K7-K11) sont fixés au lot 1 en mesurant le défaut BTC ; le harnais doit **valider le réglage manuel connu** (il passe tous les vetos, il est dans un plateau).

### 1.6 Décisions subjectives à poser à Clem
K1 (zone 1,0-2,5), taux d'échange flip-flop / retard (§1.1), pondération 2:1 de la sécurité, marge de proposition vs défaut.

## 2. Périmètre et espace de recherche

Libres (6 familles) : fenêtres régression (3-4 triplets : 7/14/30, 14/30/60, 21/45/90, 30/60/120), ENTER {0,20…0,45 pas 0,05 → 6}, confirm {5, 7, 10, 14, 20}, SMA {50, 75, 100, 150, 200}, k {0,75…2,5 pas 0,25 → 8}, ATR {5, 10, 14}.
Figés : échelle de pente 400 (plateau 200-∞ mesuré spec §11), EXIT = 1/3 d'ENTER (couplé, 0,10 pour 0,30), poids égaux (le pine expose des poids, Java les fige à 1), mèche basse oui / haute non (choix de Clem).
Taille : 4 × 6 × 5 × 5 × 8 × 3 ≈ 14 400 combinaisons. **Grille complète** (pas de coordinate ascent), puis lecture des plateaux (K13) et affinage local d'un cran autour du centre retenu seulement.

Coût (estimation à mesurer au lot 1) : régressions et SMA/ATR précalculées par fenêtre/période (≈ 15 séries distinctes, partagées entre combinaisons) ; la machine Mix est un passage O(n) sur ~3 300 bougies → ≪ 1 ms ; K1-K7 idem. Le rejeu Rainbow (K8-K11) domine (quelques ms) → quelques minutes pour toute la grille × folds, sur un poste.

## 3. Données et protocole

- Historique D1 : BTC/ETH Binance depuis 2017-08, PAXG Binance depuis 2020-08 (`tools/calibration/*_klines_d1_full.json`, script `fetch_binance_klines_d1.py`). Pour un actif arbitraire : seul Binance a un historique long ; Kraken/OKX sont sautés via `maxHorizonDays` ([`04-market-data.md`](../architecture/04-market-data.md)). Le Bucket stocke du H1 et agrège en D1 : 9 ans de D1 = ~79 000 H1 (ring 100 000) → fetch lourd pour un nouvel actif, à confronter à la politique de cache DB (« tout fetch stocké ») et au risque de ban (à vérifier). Recommandation : le bench lit un **fichier D1** (comme `RainbowAtrReplayMain`), alimenté par le script de fetch ; pas de dépendance DB/réseau en V1.
- Walk-forward : ancré expansif, test par année civile, purge 10 j ; BTC/ETH ≈ 6 folds (2021→2026), PAXG 3 folds (2024→2026, trop peu pour être une preuve : PAXG confirme la mécanique et l'avertissement K17, pas un gain).
- Historique trop court (K18) : proposition avec avertissement.
- ATH par actif (`AthReference`) : nécessaire seulement pour K8-K11 (rejeu Rainbow) ; calculé depuis la 1re bougie comme le rejeu actuel.
- Prix PAXG : Clem achète sur Kraken, la source de calibration est Binance → écart de prix à noter (cf. `tradeio5_exchanges…`).

## 4. Forme de la livraison

Outil de test/CLI `TrendMixBenchMain` (src/test, même style que `RainbowAtrReplayMain`), pas de service ni endpoint (YAGNI ; un mode « Auto » UI est une intention ultérieure, cf. chantiers §7).
Sorties dans le dossier de l'actif : `rapport.md` (KPI du défaut, du meilleur plateau, baselines, verdict), CSV (grille complète + folds), `trend-replay.html` autonome (réutilise le gabarit `replay-template.html`, ajoute la barre de régime du Mix pour valider à l'œil) et un bloc **valeurs à saisir dans le pine** (`rainbow_trend_dca_v1.pine`, source de vérité) + le `new Params(...)` Java correspondant. Clem valide, puis transforme en preset (`RainbowLiveTrendConfigs`, règle des défauts dans les Initializers) : aucune écriture automatique.

## 5. Questions

Tranchées (2026-10-09) : critère principal = qualité de la Trend ; échelle entre swing et cycle ; actif non tradable = proposition + avertissement ; périmètre = régression + SMA.

Tranchées aussi : source des bougies = **Binance** (PAXG compris) ; veto Rainbow = `ETH Perso Bear/Bull` pilotés par la Trend doivent battre le jeu sans Trend (§1.5 d) ; K1 en ratio switchs/retournements accepté, repères ZigZag à garder souples (§1 principes).

Tranchées au mieux (Clem : « fais au mieux », 2026-10-09), révisables au lot 1 :
1. **« Perso Generic » est à porter en Java** (ce n'est pas le jeu global du bench) depuis `rainbow_dca_v4_atr_moon.pine`, pine = source de vérité, en préalable du lot 1 (baseline d, §1.5).
2. **Niveaux ZigZag** (adaptatifs, calés sur BTC) : swing ≈ 20 %, majeur ≈ 35 %. « 1re grosse hausse » = la jambe du creux majeur jusqu'au pivot suivant (pas de seuil X supplémentaire) ; K5b = part de cette jambe déjà passée à la bascule.
3. **Pondération sécurité** : 2:1 (Bull à tort / Bear à tort).
4. **Taux d'échange provisoire** : 1 jour de retard au sommet ≈ 3 jours de flip-flop Bear résorbés (arbitrage K3 vs K5a) ; ajusté au lot 1 sur des cas BTC chiffrés présentés à Clem.
5. **Marge de proposition vs défaut** : un réglage n'est proposé « meilleur » que si son amélioration dépasse 2 × l'écart-type des variations d'un cran du défaut BTC (bruit mesuré) ; sinon verdict « garder le défaut ».

## 6. Feuille de route

| Lot | Contenu | Acceptation |
|---|---|---|
| 0 | Port Java de « Perso Generic » (pine v4) dans `RainbowAtrPresets` | rejeu = pine sur BTC — **livré** (`generic()` + test ; comparaison TradingView à faire par Clem) |
| 1 | **Livré** (`TrendMixBenchMain`, cf. [`09-trend.md`](../architecture/09-trend.md)) — Harnais de mesure K1-K11, K17-K18 **sans optimisation**, sur BTC ; perturbations ±1 cran du défaut ; baselines (Bull-only, Bear-only, Trend aléatoire) | le défaut BTC passe tous les vetos et se situe dans un plateau ; la Trend aléatoire est nettement séparée ; Mix Java = pine (test `TrendMixCalculatorTest` vert) ; seuils figés avec Clem |
| 2 | Grille complète + walk-forward + K12-K16 + sélection du plateau — **abandonné** (§7) | — |
| 3 | Restitution, run PAXG — **abandonné** (§7) | — |
| 4 | Preset via `RainbowLiveTrendConfigs` — **abandonné** (§7) | — |

## 7. Verdict (2026-10-09) : pas d'edge, bench automatique abandonné

- **Grille du Trend Mix** (≈ 14 400 combinaisons, BTC et ETH, critère = excès annuel du Rainbow piloté, walk-forward ancré 2021-2026, choix par plateau) : aucun edge robuste. BTC +63 points cumulés dont +64 sur la seule année 2023 (±2 les autres), ETH −112, choix joint −3. Spread de la grille très faible (BTC : médiane −301, max −286, défaut −303).
- **Trend oracle** (vision parfaite des pivots ZigZag) : guère mieux que le défaut (BTC −298 / −300 vs −303 ; ETH −288 / −292 vs −288), et le meilleur point de la grille la dépasse : l'écart est du bruit. Le levier du Rainbow est dans les jeux Bull/Bear, pas dans les réglages de la Trend.
- **Recherche de jeux Bull/Bear** sous garde-fous (ventes/an dans [0,5× ; 2×] du jeu manuel, exposition moyenne ≥ 0,8×, score de Clem) : hors échantillon, moins bon que les jeux manuels dans les 4 cas (cumul OOS BTC Bull −117, BTC Bear −44, ETH Bull −104, ETH Bear −102), alors qu'en échantillon complet elle les « bat » : surapprentissage. Les jeux manuels ont vu tout l'historique, la comparaison leur est favorable.
- **Décision** : les lots 2-4 (grille + walk-forward, restitution, preset) ne sont pas réalisés. Réglages du Trend Mix et des jeux : manuels. Un nouvel actif reçoit le Trend Mix par défaut ; le harnais du lot 1 (`TrendMixBenchMain`) sert à contrôler la qualité de la Trend (K1-K7b, avertissements K17/K18).
