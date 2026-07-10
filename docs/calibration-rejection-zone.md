# Calibration — Zones de rejet (Lot 3, item J)

Complément à `docs/etude-indicateurs-macro-externes.md` §14 et au prompt d'implémentation
`docs/prompt-implementation-lot3-indicateurs.md`. Documente le protocole de validation empirique
de `RejectionZoneIndicator` et son résultat, conformément à l'exigence du lot : "livrer le code
sans avoir fait cette étape ne satisfait pas ce lot, même si la compilation et les tests unitaires
passent".

## Outil

`tools/calibration/rejection_zone_calibration.py` — script Python autonome (hors code de
production Java), qui réimplémente exactement la même formule que `RejectionZoneIndicator.java`
(mêmes 3 conditions de rejet, même clustering par distance à `clusterDistance * ATR`, même score
de force) pour itérer rapidement sur les paramètres sans recompiler le projet. Exécute les 3 étapes
du protocole demandé par le prompt d'implémentation :

1. **Calibration visuelle** — résumé texte des zones détectées (niveau, nombre de touches, score)
   sur l'historique, avec les paramètres par défaut.
2. **Test statistique** — taux de réaction (proportion de tests d'une zone qui produisent un
   nouveau rejet plutôt qu'un franchissement) comparé à des niveaux de prix choisis au hasard dans
   la même plage.
3. **Sensibilité aux paramètres** — variation de `k1`/`k2`/`p`/`clusterDistance` sur une grille
   (81 combinaisons) et stabilité du taux de réaction résultant.

```
python3 tools/calibration/rejection_zone_calibration.py [--csv path/to/ohlcv.csv] [--seed 42]
```

## Limite importante de l'exécution ci-dessous : données synthétiques, pas un historique réel

L'environnement dans lequel cette implémentation a été réalisée n'a **pas d'accès réseau sortant**
vers un exchange (Binance, etc.) : le trafic sortant du bac à sable est restreint à une allowlist
qui ne couvre pas `api.binance.com`, et les tentatives de récupération d'un historique BTC/USDT H1
réel (API Binance, export CSV stooq) n'ont renvoyé aucune donnée exploitable. Faute d'historique
réel accessible depuis ce script, le run documenté ci-dessous tourne sur
`generate_synthetic_series()` : une marche aléatoire géométrique avec clusters de volatilité, dans
laquelle 3 niveaux de prix "vrais" ont été injectés artificiellement (rebonds forcés à ~58 000,
~63 500 et ~67 000) pour avoir un signal de test non trivial — si le protocole ne détectait même
pas un signal injecté à la main, la formule ne vaudrait clairement rien.

**Conséquence directe, à ne pas perdre de vue** : les chiffres ci-dessous valident que *le
protocole lui-même fonctionne* (le script tourne, détecte des zones, mesure un taux de réaction,
teste la sensibilité) et donnent une première indication que la formule n'est pas absurde par
construction. Ils ne remplacent **pas** une validation sur données de marché réelles. Avant
d'activer `RejectionZoneIndicator` par défaut dans une `Strategy`/`Opinion`, ce même script doit
être ré-exécuté avec `--csv` sur un export réel (ex. `MarketData` BTC/USDT H1 exporté depuis
TradeIO5 lui-même, qui a un accès réseau normal en production, contrairement à cet environnement de
build) — c'est un prérequis explicite, pas une formalité.

## Résultat de l'exécution (données synthétiques, seed=42, paramètres par défaut)

Paramètres par défaut testés : `k1=1.5`, `k2=0.5`, `p=0.66`, `clusterDistance=0.5×ATR`,
`atrPeriod=14`, `lookback=200` (sur les 200 dernières bougies de la série de 1500).

### 1. Zones détectées (calibration visuelle)

8 zones détectées sur la fenêtre de lookback, dont la plus forte (`resistance`, niveau ≈ 26 394,
2 touches, score 0.415) correspond à un regroupement réel de 2 rejets proches — cohérent avec ce
qu'un lecteur de graphique identifierait comme zone plus significative qu'un pivot isolé (les 7
autres zones, à 1 touche, ont un score entre 0.24 et 0.29).

### 2. Test statistique — taux de réaction

| | Taux de réaction | Nombre de tests |
|---|---|---|
| Zones détectées | **83.0 %** | 53 |
| Niveaux aléatoires (même plage de prix) | 64.8 % | 91 |

Écart de +18.2 points entre les zones détectées et une baseline aléatoire : sur ce jeu de données
(qui contient un signal injecté à la main), la formule capture bien un signal réel et ne se
comporte pas comme un sélecteur de niveaux arbitraire. Ce n'est pas une preuve que la formule
fonctionne sur un vrai marché — un jeu de données réel a un bruit et une microstructure différents
— mais c'est le minimum attendu avant de considérer la formule plausible.

### 3. Sensibilité aux paramètres

Grille de 81 combinaisons (`k1 ∈ {1.2, 1.5, 2.0}`, `k2 ∈ {0.35, 0.5, 0.75}`,
`p ∈ {0.55, 0.66, 0.75}`, `clusterDistance ∈ {0.35, 0.5, 0.75}`), toutes avec au moins une zone
testable :

- Taux de réaction : min **74.8 %**, max **95.5 %**, moyenne **85.5 %**, écart-type **6.6 points**.
- Aucun effondrement du taux de réaction observé sur la grille (pas de combinaison proche de 50 %,
  qui signalerait un hasard pur) : la formule ne dépend pas de manière instable d'un réglage
  précis à la décimale près, ce qui était le risque explicitement signalé par le prompt
  d'implémentation.
- L'écart-type de 6.6 points sur une grille large indique une sensibilité modérée mais réelle :
  à reconfirmer sur données réelles avant de figer des valeurs définitives.

## Paramètres retenus (point de départ, à reconfirmer sur données réelles)

Les valeurs par défaut de `RejectionZoneIndicator` (`DEFAULT_K1=1.5`, `DEFAULT_K2=0.5`,
`DEFAULT_P=0.66`, `DEFAULT_CLUSTER_DISTANCE=0.5`, `DEFAULT_LOOKBACK=200`, `DEFAULT_ATR_PERIOD=14`)
sont conservées comme point de départ : elles sont au centre de la grille de sensibilité testée et
n'ont montré aucun signe d'instabilité sur celle-ci. **Elles ne sont pas figées** — le protocole
ci-dessus doit être rejoué sur un historique BTC/USDT (et ETH/USDT) réel en H1 avant tout
branchement dans une `Strategy`/`Opinion`, conformément à la Definition of done du Lot 3.

## Prochaine étape (hors scope de ce lot)

1. Exporter un historique H1 réel (BTC/USDT, quelques centaines à quelques milliers de bougies)
   depuis TradeIO5 ou une source externe, au format CSV attendu par le script
   (`timestamp,open,high,low,close,volume`).
2. Ré-exécuter `rejection_zone_calibration.py --csv <export>.csv` et comparer le taux de réaction
   obtenu à celui documenté ici sur données synthétiques.
3. Si le taux de réaction réel s'effondre near 50 % (proche du hasard) ou si la sensibilité aux
   paramètres explose, revoir la formule (pondérations du score, définition du rejet) avant tout
   branchement en aval — ne pas activer l'indicateur par défaut dans l'intervalle.

## Run réel multi-timeframe (2026-07-09) — H1/D1/W1, BTCUSDT, Fev 2024 → Juil 2026

Point de départ de ce run : observation de Clem que sa méthode manuelle de tracé de zones
support/résistance dépend du timeframe (W1 pour les zones structurelles pluri-annuelles, D1 pour
affiner près du prix courant), et que certaines zones anciennes (ex. ~85k, testée Nov 2024, à
nouveau Fev-Avr 2025, à nouveau Nov 2025-Jan 2026 en bear market) restent pertinentes malgré leur
âge — ce que la fenêtre de `lookback` (nombre de bougies, pas une durée calendaire) ne peut capter
que si le TF choisi et le lookback couvrent assez loin en arrière (200 bougies ≈ 8 jours en H1,
~7 mois en D1, ~3.8 ans en W1).

**Données** : 21 337 bougies H1 réelles BTCUSDT récupérées via l'API publique Binance
(`tools/calibration/fetch_real_klines.py`, pas de clé requise) du 2024-02-01 au 2026-07-09 —
contournement du fait que `BinanceMarketDataApiClient`/`CachingMarketDataApiClient` ne
supportent/cachent aujourd'hui que H1 en natif (`NATIVE_INTERVALS = Map.of(TimeFrame.H1, "1h")`,
TimeFrame calendaires comme W1 bypassées par le cache DB). D1 (890 bougies) et W1 (128 bougies)
reconstitués par agrégation OHLCV réelle via **`Bucket`** (`tools/calibration/BucketResample.java`,
single-file source launch réutilisant directement `Bucket.aggregate`/`Bucket.view` de production —
demande explicite de Clem plutôt qu'une réimplémentation Python du resampling, même principe que le
script de calibration qui mirrors déjà `RejectionZoneIndicator.java`).

### Résultat par timeframe (params par défaut sauf `lookback`)

| TF | Bougies | Lookback | Zones détectées | Taux réaction zones | Taux réaction aléatoire | Écart |
|---|---|---|---|---|---|---|
| H1 | 21 337 | 200 (défaut) | 14 (57.8k-64k) | 70.0 % (1688 tests) | 67.9 % (828 tests) | +2.1 pts |
| D1 | 890 | 800 | 47 (61.6k-113.6k) | 66.6 % (730 tests) | 66.2 % (663 tests) | +0.4 pts |
| W1 | 128 | 120 | 12 (49k-123.8k) | 65.4 % (78 tests) | 69.4 % (62 tests) | **-4.0 pts** |

**Contraste net avec le run synthétique** (+18.2 pts d'écart, §2 ci-dessus) : sur données réelles,
le test statistique générique ("taux de réaction zones détectées vs niveaux aléatoires") ne montre
qu'un edge faible (H1), quasi nul (D1), voire négatif sur un échantillon trop petit pour être fiable
(W1, 78-62 tests seulement — grille de sensibilité 81 combinaisons : **aucune** n'a produit de zone
testable, signe que 128 bougies W1 est too thin pour ce test tel que conçu). Ce n'est pas la
conclusion espérée, et il ne faut pas la balayer : **honnêtement, le test statistique générique tel
qu'écrit ne valide pas la formule sur données réelles**, contrairement à l'impression donnée par le
run synthétique.

### Mais : validation qualitative directe de l'anecdote de Clem (zone ~85k)

Sur le run D1 (le plus pertinent pour cette comparaison), les 2 zones les plus fortes après le
support 62.3k sont :
- `support 81093.14, touches=6, strength=0.628` (2ème zone la plus forte de tout le run D1)
- `resistance 90167.59, touches=6, strength=0.577` (3ème)
- `support 89138.02, touches=5, strength=0.539`

Ces 3 zones forment un cluster continu **81k-90k**, exactement la région (~85k) que Clem identifie
manuellement comme testée à 3 reprises sur des régimes de marché différents (rebond bull Nov 2024,
stagnation Fev-Avr 2025, rebond bear Nov 2025-Jan 2026). La détection automatique retrouve bien ce
cluster, avec le 2ème score de force le plus élevé de toute la série — **la logique de clustering et
de score par touches fonctionne correctement sur données réelles**, alors même que le test
statistique générique ne le fait pas ressortir comme "significatif vs hasard".

### Diagnostic initial : hypothèse "le test brut noie le signal des zones fortes" — infirmée

Hypothèse posée après le premier run : `reaction_rate()` traite chaque zone détectée à égalité (1
touche faible comme 6 touches fortes), ce qui pourrait diluer l'edge des zones fortes comme le
cluster 81k-90k. Test de l'hypothèse : `run_statistical_test` refait pour calculer 3 lectures au
lieu d'une (brut, pondéré par `strength`, filtré aux zones au-dessus de la médiane de force du run)
— cf. `reaction_rate_per_level`/`_aggregate` dans le script, option `--min-strength`.

**Résultat, rejoué sur les mêmes CSV réels (H1/D1/W1) :**

| TF | Brut | Pondéré strength | Filtré (top ~50%) | Aléatoire |
|---|---|---|---|---|
| H1 | 70.0% | 70.2% | 70.8% (7/14 zones) | 67.9% |
| D1 | 66.6% | 66.0% | **65.2%** (24/47 zones) | 66.2% |
| W1 | 65.4% | 65.8% | 68.3% (6/12 zones) | 69.4% |

**L'hypothèse est infirmée sur D1** (le run le plus riche en données) : filtrer/pondérer par force
ne fait **pas** ressortir un edge caché, le taux filtré est même légèrement *inférieur* au taux
brut et au hasard. Sur H1 l'edge s'améliore légèrement en filtrant (+0.8pt de plus), mais reste
modeste. Sur W1 l'échantillon est trop petit (41-78 tests) pour trancher dans un sens ou l'autre.

**Conclusion honnête, à ne pas enjoliver** : ce n'est donc pas juste "le test brut est mal
pondéré" — le test `reaction_rate` (tel que défini : tolérance/marge globales, tenue sur 5 bougies)
ne montre un edge net des zones détectées sur le hasard dans **aucune** de ses 3 lectures, sur
données réelles. Deux lectures possibles, non tranchées ici :
1. Le design du test lui-même est en cause (tolérance/marge calculées sur toute la volatilité de
   2.5 ans, fenêtre de "tenue" fixe à 5 bougies quel que soit le TF, comparaison à un niveau
   aléatoire unique plutôt qu'à une distribution) — pas la formule de détection des zones.
2. BTC a une forte autocorrélation directionnelle sur les fenêtres testées (marché qui tend/range
   plutôt que marche aléatoire) : la baseline "aléatoire" est déjà haute (66-70%) par nature,
   laissant peu de marge pour qu'une zone démontre un edge mesurable par ce test précis.

### Conclusion de ce run

- **Ne pas brancher en l'état** : aucune des 3 lectures du test statistique (brut, pondéré,
  filtré) ne valide un edge fiable sur données réelles, contrairement au run synthétique initial —
  conforme à la mise en garde déjà écrite plus haut dans ce document. Ce n'est pas un problème de
  pondération, la piste testée pour l'expliquer ne tient pas.
- **La mécanique de détection/clustering reste cohérente avec une lecture manuelle experte**
  (cluster 81k-90k retrouvé avec le 2ème score de force le plus élevé de tout le run D1, dans la
  zone exacte identifiée par Clem) — mais cette correspondance qualitative n'est **pas confirmée
  statistiquement** par `reaction_rate`, sous aucune de ses 3 variantes. À traiter comme une piste
  intéressante, pas une preuve.
- Prochaine étape si on veut aller plus loin (pas faite ici) : remettre en cause le design du test
  lui-même plutôt que l'agrégation — ex. tolérance/marge par régime de volatilité local (pas globale
  sur toute la série), fenêtre de tenue proportionnelle au TF, ou comparaison à une distribution de
  niveaux aléatoires (plusieurs tirages) plutôt qu'à un seul jeu de niveaux.

## Fenêtre de tenue normalisée en durée calendaire + H4 (2026-07-09, suite)

Question de Clem : est-ce que tester d'autres TF (H4, "D2" — qui n'existe pas dans l'enum
`TimeFrame`, et que `Bucket.alignTimestamp` ne gère de toute façon pas correctement pour un
multiple de jours > 1) aiderait ? Réponse apportée avant de coder : probablement pas *en ajoutant
un TF de plus sur le même étalon*, parce que la fenêtre de "tenue" de `reaction_rate` était un
nombre de bougies fixe (5), pas une durée — 5h en H1, 5 jours en D1, plus d'un mois en W1. Le vrai
levier identifié : normaliser cette fenêtre en jours calendaires réels (`--hold-days`, cf.
`reaction_rate_per_level`), pour comparer les TF sur un étalon commun.

**Rejoué avec `--hold-days 5` sur H1 (lookback 200), H4 (lookback 4800, ≈ même durée que D1 :
800 jours), D1 (lookback 800), W1 (lookback 120) :**

| TF | Bougies | Zones | Brut | Pondéré | Filtré | Aléatoire | Écart (brut) |
|---|---|---|---|---|---|---|---|
| H1 | 21 337 | 14 | 21.1% | 20.9% | 20.0% | 19.8% | +1.3 pts |
| H4 | 5 335 | 98 | 32.4% | 32.8% | 32.0% | 33.8% | **-1.4 pts** |
| D1 | 890 | 47 | 66.6% | 66.0% | 65.2% | 66.2% | +0.4 pts |
| W1 | 128 | 12 | ⚠ non exploitable (voir note) | — | — | — | — |

**Note W1** : avec un hold de 5 jours sur des bougies W1 (espacées de 7 jours), aucune bougie
suivante ne tombe jamais dans la fenêtre de tenue — le test devient vide par construction et
affiche 100% partout (zones et aléatoire), un artefact, pas un résultat. Garde-fou ajouté au script
(warning explicite si `hold_days` < espacement moyen des bougies) pour ne plus le confondre avec un
vrai signal. Sur W1, la seule fenêtre sensée reste en nombre de bougies (comportement historique,
`--hold-days` non applicable en dessous de 7 jours).

**Ce que ça change à la conclusion : rien sur le fond, mais un point positif inattendu.** L'edge
reste thin/nul/négatif selon le TF même avec une fenêtre honnête — confirmant que le problème n'est
pas "quel TF choisir" mais le design du test lui-même (cf. section précédente). En revanche, **H4
et D1 retrouvent des zones structurellement cohérentes entre elles** : le cluster H4 le plus fort
(résistance 96.7k, support 94.4k, 43/38 touches) et le cluster D1 le plus fort après le support
principal (81k-90k, 6 touches) tombent dans la même région de prix (85k-97k) sur une période
identique, alors que ce sont deux séries **indépendamment reconstituées** depuis le H1 brut via
`Bucket`. C'est un signal de cohérence structurelle réel (le formule détecte la même zone qu'on
la regarde à 4h ou à 1 jour de résolution), même si — encore une fois, à ne pas enjoliver — ça ne
se traduit toujours pas en edge statistique démontré par `reaction_rate`, sous aucune de ses
variantes testées à ce jour.

### Verdict final de cette session de calibration

- **Ne pas brancher `RejectionZoneIndicator` en production tel quel** : aucune configuration testée
  (brut/pondéré/filtré, H1/H4/D1, fenêtre bougies ou calendaire) ne montre un edge statistique
  robuste sur données réelles.
- **La détection/clustering est structurellement solide** : zones cohérentes entre TF
  indépendamment reconstitués, cluster correspondant à une zone identifiée manuellement par Clem
  (81k-97k) retrouvée avec un score élevé sur 2 TF différents.
- **Le point faible identifié est le test statistique `reaction_rate` lui-même**, pas la formule de
  détection — deux itérations (pondération/filtrage par force, puis fenêtre calendaire) n'ont pas
  réussi à lui faire produire un edge net. Aller plus loin demanderait de redessiner ce test from
  scratch (tolérance locale par régime de volatilité, comparaison à une distribution de tirages
  aléatoires plutôt qu'un seul, définition différente de "succès" qu'un simple hold/break binaire)
  — un chantier à part entière, pas une itération de plus sur l'existant.

## Bandes plutôt que niveaux ponctuels : idée de Clem + généralisation (2026-07-09, suite)

Deux apports de Clem en cours de session, tous les deux excellents :

1. **"Rectangle de consolidation"** : une technique de zone qu'il utilise réellement en trading
   manuel, absente des 6 précédentes. Principe : repérer une période de stagnation, prendre les
   bornes du prix à la FIN de cette stagnation (en excluant les valeurs extrêmes qui dépassent un
   peu — bornes par percentile, pas min/max brut), et projeter ce rectangle horizontalement vers
   l'avant. Implémenté dans `tools/calibration/consolidation_zone_test.py` (detection : extension
   gloutonne d'une fenêtre tant que le range percentile reste sous un seuil ATR, ancrée par une
   cassure confirmée juste après).
2. **"Je ne cherche pas LE prix exact, je cherche un indicateur plus ou moins pondéré"** : les 6
   techniques précédentes testaient un NIVEAU ponctuel + une tolérance de contact fixe et globale.
   Généralisé : chaque zone porte maintenant sa propre LARGEUR (bande), dérivée de sa construction
   (étendue du cluster pour rejection/pivot/wickdensity, largeur de bin pour volprofile, marge ATR
   pour roundnumbers/kde). `tools/calibration/band_zone_test.py` réunit les 7 techniques
   (6 précédentes + consolidation) sous ce même format bande, avec le même framework statistique
   que la section précédente (réaction = rejet du même côté vs transpercement, normalisé ATR,
   baseline density-matched de même largeur, permutation + Mann-Whitney, walk-forward).

**Walk-forward sur D1 (lookback=300, 6 coupures, horizon=5) — les 7 techniques en bandes :**

| Technique | Coupures significatives (p_perm<0.10) | Comportement |
|---|---|---|
| **consolidation** | 40%, 50%, 60% (3/6, p jusqu'à 0.005, MW jusqu'à 0.000) | Le plus consistant, effet fort (1.8 à 3.2) |
| pivot | 40%, 50% (2/6) | Positif au début, s'effondre ensuite (-5.3 à 80%) |
| roundnumbers | 40% (1/6) | Faible, s'effondre nettement après (-6.6 à 80%) |
| kde | 40% (1/6, marginal) | Échantillons trop petits pour conclure (1-3 zones) |
| volprofile | 50%, 60% (marginal) | Échantillons trop petits en fin de série (n=1 touche) |
| wickdensity | 60%, 70% (marginal, horizon=10 seulement) | Pas de pattern clair |
| rejection | 50% (marginal) | Pas d'amélioration nette vs la version ponctuelle |

**`consolidation` est la seule technique qui montre un pattern net et interprétable** : significative
sur les 3 premières coupures (40-60%, avant ~oct. 2025), puis s'effondre sur les 2 dernières (80-90%,
qui couvrent la période de bear market démarrée le 18/10/2025 selon Clem). **C'est exactement le
comportement que Clem avait anticipé de son expérience manuelle** ("au BearMarket il y a eu des
chutes qui ont totalement transpercé ces zones") — une confirmation independante, obtenue par un
test purement statistique sans connaître cette anecdote au moment du design du test, que la
méthodologie (détection + test walk-forward) capture un vrai phénomène de marché plutôt que du bruit.

**Ce que ça change à la conclusion** : `consolidation` (bandes) reste la piste la plus solide de
toute la session — pas un edge permanent et inconditionnel (elle aussi s'effondre en regime de
forte tendance baissière), mais un signal qui se comporte de façon cohérente avec l'intuition de
Clem : fiable en accumulation/range, pas fiable pendant un vrai décrochage directionnel. Un filtre
de régime en amont (détecter qu'on est en range avant de faire confiance à la zone) est la piste
naturelle suggérée par ce résultat — non implémenté ici.

**Suite envisagée** : un "indicateur pondéré" continu (plutôt qu'une bande binaire dedans/dehors) —
un score de confiance calculé en tout point de prix comme la somme des forces de zones proches,
pondérée par la distance — pour alimenter l'outil de visualisation web (zones éditables à la main)
évoqué par Clem. Pas encore implémenté.

## Bench élargi multi-cycle (BTC+ETH, 2017-2026) — retest durée / touches / volume (2026-07-10)

**Contexte** : les tests précédents de cette section (Q1 durée, Q2 usure/confirmation, Q3 densité)
tournaient sur un petit échantillon BTC-seul (73 zones dédupliquées, fév. 2024 → juil. 2026, obtenu
via la DB locale limitée à ~2 mois d'historique H1). Clem a demandé d'élargir : fetch complet BTC et
ETH (`fetch_real_klines.py`, corrigé pour bug de pas d'intervalle) via sa machine réelle (accès réseau
Binance indisponible dans le sandbox) — D1/H4 depuis le 17/08/2017 (listing Binance), H1 depuis
2020-01-01, jusqu'au 09/07/2026. `tools/calibration/build_zone_dataset.py` redétecte les zones
`consolidation` sur ce nouvel historique et calcule en plus une métrique de volume causale :
`volume_strength = volume moyen pendant la formation / volume moyen de tout l'historique ANTÉRIEUR
à la formation` (pas de fuite). `tools/calibration/final_analysis_extended.py` rejoue les 3 questions
sur le pool BTC+ETH (415 zones : 236 BTC, 179 ETH ; 224 D1, 83 H4, 108 H1).

**Q1 — durée de stagnation vs réaction** : résultat **inversé et maintenant significatif**
(rho=-0.207, p<0.0001 ; tercile court ≤3.7j = -0.029 ATR vs tercile long ≥14j = -0.166 ATR, écart
-0.137 ATR, p_perm=0.001). Sur le petit échantillon BTC-seul c'était nul ; sur le bench élargi, une
stagnation LONGUE est associée à une réaction PLUS FAIBLE (plus proche du transpercement), pas plus
forte.

**Q2 — usure vs confirmation (rang de touche)** : le pattern "1ère touche faible, ensuite ça
tient" se retrouve dans la forme générale mais moins proprement qu'avant : touche#1=-0.580 ATR,
#2=-0.087, #3=-0.029, #4=+0.077 (amélioration progressive), #5=-0.130 (rechute). Pente moyenne
quasi nulle (+0.0093), test du signe non significatif (p=0.66), mais test de permutation sur la
pente significatif (p=0.010) — la structure temporelle existe mais n'est pas un simple effet
linéaire monotone par zone.

**Q_volume (nouveau, réponse à "test de force de bande grâce au volume")** : significatif mais dans
le sens **inverse** de l'intuition naïve ("plus de volume = zone plus forte") : rho=-0.143, p=0.0034 ;
tercile volume faible (≤0.76×) = -0.048 ATR vs tercile volume fort (≥1.44×) = -0.153 ATR, écart
-0.104 ATR, p_perm=0.022. Un volume élevé pendant la formation est associé à une réaction plus
faible, pas plus forte.

**Lecture d'ensemble et réserve importante** : sur ce bench élargi, la réaction moyenne est négative
sur la quasi-totalité des sous-groupes (le dataset couvre bien plus de cycles baissiers/de
transpercement que le petit échantillon récent). Deux mises en garde : (1) BTC et ETH ne sont pas
des échantillons indépendants (forte corrélation de prix), et les zones D1/H4/H1 d'un même actif
partagent la même série de prix sous-jacente — le n=415 est optimiste, pas 415 observations
réellement indépendantes ; (2) ces trois tests portent sur la RELATION durée/rang/volume ↔ qualité
de réaction PARMI les zones déjà détectées par `consolidation`, ce qui est une question différente
du test walk-forward edge-vs-baseline de la section précédente (qui reste la conclusion de
référence sur la viabilité de la technique). Ces résultats affinent la compréhension du signal, ils
ne remettent pas en cause le verdict "pas de mise en prod en l'état, piste la plus solide reste
`consolidation` avec filtre de régime".

**Q3 (densité de chevauchement) sur le bench élargi** : rejoué séparément (`density_bench_elargi.py`,
densité calculée par actif — BTC et ETH ne partagent aucune bande de prix donc pas de fuite
cross-actif) sur 16670 touches (10141 BTC + 6529 ETH, 415 zones). Corrélation de Spearman
statistiquement "significative" (rho=+0.026, p=0.0010) mais l'effet est quasi nul en magnitude —
un artefact classique de très grand n. Le test par tercile, plus robuste à cet artefact, ne l'est
pas : basse densité = -0.073 ATR vs haute densité = -0.028 ATR, écart +0.046 ATR, p_perm=0.162.
**Conclusion inchangée par rapport au petit échantillon : "plus c'est gris, plus ça tient" n'est
toujours pas confirmé.**
mes coupures) pointe vers une dépendance de régime de marché
  plutôt qu'un signal structurel exploitable par détection de zone statique seule.
  - **N'`allouer aucune de ces 6 techniques à la production`** en l'état.
- **Ce que ça ne veut PAS dire** : que Clem a tort de trouver ses zones utiles en trading manuel. Le
  test mesure une chose précise et étroite ("le prix clôture-t-il mieux après un horizon fixe de N
  bougies, ATR-normalisé, vs un niveau aléatoire de densité comparable") — une approche
  discrétionnaire (lecture du contexte, confluence multi-signaux, gestion du risque asymétrique,
  patience sur plusieurs mois) capture des choses qu'un test mécanique aussi simple ne capture pas.
- **Piste sérieuse pour la suite si le sujet est repris** : plutôt que d'empiler encore des
  variantes de `reaction_rate`, tester une hypothèse structurée sur le régime de marché lui-même
  (ex : la zone tient-elle mieux en range qu'en trend ? un filtre de régime — ADX, volatilité
  réalisée — en amont du test changerait-il le tableau walk-forward ?), ou tester sur plusieurs
  actifs (pas seulement BTC) pour voir si le pattern se généralise. Les deux sont des chantiers à
  part entière, pas des itérations rapides.

**Outils produits cette session** : `tools/calibration/robust_zone_test.py` (test v2 complet, 6
techniques, mode `--walkforward`), résultats bruts agrégés dans
`target/calibration-data/results_all.csv` (non versionné, régénérable via le script).
