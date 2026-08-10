# Prompt de calibration — EtfFlowConfidenceStrategy (BTC et ETH séparément)

Prompt autonome, à exécuter dans une session avec accès réseau réel **et** accès à la base MySQL
locale (`tradeio5`, `localhost:3306`) — pas le bac à sable Cowork (mêmes limitations que
`docs/calibration/calibration-rejection-zone.md`/`docs/calibration/prompt-calibration-movement-qualification.md` : trafic
sortant restreint, `api.binance.com` hors allowlist, et ici en plus pas d'accès à MySQL). Utiliser
le pattern déjà établi `tools/calibration/*.py`, ou une machine locale avec Python 3 et accès réseau
+ DB normal.

**Contexte** : `EtfFlowConfidenceStrategy` (service/tree/strategy/impl/EtfFlowConfidenceStrategy.java)
est codée, testée (`EtfFlowConfidenceStrategyTest`, tests unitaires purs sur `computeSignal`), et
branchée comme `StrategyType.CONFIDENCE_MODULATOR` en ad hoc uniquement (pas encore par défaut dans
`DefaultMarketOpinion`) depuis le 2026-07-16. Ses 4 seuils (`flowSignificanceThresholdUsd`=50M USD,
`magnitudeScaleFactor`=3.0, `priceMoveThreshold`=2%, `priceLookbackCandles`=1 bougie D1) sont des
valeurs de bon sens jamais confrontées à des données réelles — même situation que
`MovementQualificationStrategy` avant sa calibration, et que `RejectionZoneIndicator` avant la
sienne (qui avait fini par ne pas être branché en prod, un raisonnement intuitif s'étant révélé faux
une fois testé). Contrairement à ces deux précédents, **les données sont déjà en base** : le backfill
du 2026-07-17 (`docs/etudes/etude-cache-etf-flow-historisation.md`, addendum Farside) a rempli
`etf_flow_snapshot` avec 629 jours pour BTC (2024-01-11 → 2026-07-16) et 497 jours pour ETH
(2024-07-23 → 2026-07-16) — pas besoin de retaper SoSoValue/Farside pour cette calibration, juste
d'exporter la table.

**Demande explicite de Clem** : calibrer BTC et ETH **séparément** — ne jamais fusionner les deux
séries dans un même test statistique/une même grille de sensibilité, produire deux verdicts
indépendants (même seuils par défaut aujourd'hui pour les deux actifs, mais rien ne garantit qu'ils
doivent rester identiques une fois calibrés).

## Lire avant de commencer

1. `service/tree/strategy/impl/EtfFlowConfidenceStrategy.java` — la formule exacte à reproduire
   fidèlement en Python (méthode `computeSignal`, 2 cas : divergent / neutre-ou-cohérent fusionnés,
   cf. javadoc de classe pour la justification de la fusion).
2. `service/tree/strategy/impl/EtfFlowConfidenceStrategyTest.java` — cas de test à recopier en
   Python pour valider `compute_signal(...)` avant de l'utiliser sur les vraies données (même
   garde-fou que dans le prompt MovementQualificationStrategy : s'assurer que le script teste la
   formule de production, pas une approximation).
3. `docs/calibration/calibration-rejection-zone.md` et `docs/calibration/prompt-calibration-movement-qualification.md` —
   protocole de référence (test statistique vs groupe de contrôle aléatoire, grille de sensibilité,
   verdict explicite). Même méthodologie à appliquer ici.
4. `docs/etudes/etude-cache-etf-flow-historisation.md` — comprendre `etf_flow_snapshot` (colonnes
   `asset`/`date`/`total_net_inflow`/`fetched_at`) et la couverture réelle par asset (dates de
   lancement différentes BTC/ETH, cf. addendum Farside).
5. `model/entity/market/EtfFlowSnapshotEntity.java`, `repository/market/EtfFlowSnapshotRepository.java`
   — structure exacte de la table à exporter.

## Rappel de la formule à calibrer (ne pas la modifier avant d'avoir des résultats)

Sur un jour donné (`total` = flux net ETF du jour en USD brut, `priceChangePct` = variation de
prix sur `priceLookbackCandles` bougies D1) :

1. Si `|priceChangePct| < priceMoveThreshold` OU `|total| < flowSignificanceThresholdUsd` →
   `score = 0.0` ("pas de mouvement de prix marqué ou flux ETF non significatif").
2. Sinon, si `signum(total) == signum(priceChangePct)` → `score = 0.0` ("flux ETF cohérent avec le
   mouvement de prix, aucune atténuation").
3. Sinon (divergence) → `score = -clamp01(|total| / (flowSignificanceThresholdUsd *
   magnitudeScaleFactor))` ("mouvement de prix non soutenu par le flux ETF institutionnel").

Recopier les valeurs par défaut exactes depuis le fichier source (ne pas les redevoir de mémoire) :
`DEFAULT_FLOW_SIGNIFICANCE_THRESHOLD_USD`, `DEFAULT_MAGNITUDE_SCALE_FACTOR`,
`DEFAULT_PRICE_MOVE_THRESHOLD`, `DEFAULT_PRICE_LOOKBACK_CANDLES`.

## Objectif de la calibration

Répondre à 3 questions, **pour chaque asset séparément** :

1. **Le cas "divergent" a-t-il un pouvoir prédictif réel** ? Un jour où le flux ETF contredit le
   mouvement de prix est-il suivi d'un mouvement moins durable (arrêt/retournement) qu'un jour
   quelconque de la série ?
2. **Les seuils par défaut sont-ils stables** ? Un edge qui s'effondre dès qu'on bouge un seuil de
   quelques points signale une formule qui "marche" par hasard sur ce jeu de données précis.
3. **Le signal est-il assez fréquent pour être utile** ? ETF_FLOW ne se met à jour qu'une fois par
   jour (contrairement à OI/funding en H1) — le nombre de jours "divergents" pourrait être trop
   faible pour un verdict statistiquement solide, surtout côté ETH (497 jours au total, moins que
   BTC). Documenter ce risque explicitement plutôt que de le découvrir en fin de protocole.

## Étape 1 — Récupérer les données réelles

**a. Flux ETF quotidien (déjà en base, pas besoin de réseau externe)** — nouveau script
`tools/calibration/export_etf_flow_history.py` : connexion MySQL locale (`mysql-connector-python`
ou `pymysql`, credentials dans `application-dev.properties`, `spring.datasource.username`/
`password`, jamais committées en clair), requête
`SELECT date, total_net_inflow FROM etf_flow_snapshot WHERE asset = 'BTC' ORDER BY date` (et
`'ETH'`), export CSV (`date,total_net_inflow`) — un fichier par asset. Vérifier en pratique le
nombre de lignes exportées correspond à ce qui est documenté (629 BTC / 497 ETH, cf. contexte
ci-dessus) avant de continuer ; si différent, le backfill a peut-être tourné à nouveau entretemps,
documenter le nouveau compte plutôt que de supposer une erreur.

**b. OHLCV quotidien (prix)** — réutiliser `tools/calibration/fetch_real_klines.py` tel quel, avec
`--interval 1d` (déjà supporté, cf. `INTERVAL_MS`), sur la même plage que les données ETF
disponibles pour chaque asset (BTC : depuis 2024-01-11 ; ETH : depuis 2024-07-23, pas la peine de
remonter avant — pas de donnée ETF à comparer). Sert à calculer `priceChangePct`.

**c. Alignement des séries** — l'ETF flow n'est publié qu'aux jours de bourse US (fériés/week-ends
US absents ou à 0, cf. javadoc `FarsideEtfFlowClient#parseHistory` sur l'exclusion des jours non
publiés), alors que les klines crypto existent 7j/7. Aligner les deux séries sur les dates
effectivement présentes côté ETF flow (pas l'inverse) — un jour sans flux ETF publié n'est pas un
jour à exclure du prix, mais un jour sans flux à comparer, donc à exclure du calcul du score ce
jour-là.

## Étape 2 — Réimplémentation Python fidèle

Un script `tools/calibration/etf_flow_calibration.py`, même patron que
`movement_qualification_calibration.py` :
- Fonction pure `compute_signal(total, price_change_pct, flow_significance_threshold_usd,
  magnitude_scale_factor, price_move_threshold)` reproduisant **exactement** la formule ci-dessus —
  valider d'abord avec les cas de `EtfFlowConfidenceStrategyTest.java` recopiés en tests Python.
- Boucle sur chaque jour aligné (étape 1c), calcule `priceChangePct` sur `priceLookbackCandles`
  bougies D1, classe le jour (divergent / neutre-ou-cohérent).
- `--asset {BTC,ETH}` en paramètre CLI plutôt que deux scripts séparés — mais **toujours exécuté et
  rapporté séparément**, jamais une exécution combinée qui mélangerait les deux séries.

## Étape 3 — Test statistique (par asset, jamais combiné)

Pour les jours classés "divergent", mesurer un résultat "forward" sur plusieurs horizons (ex. 3j,
7j, 14j après le jour classé — horizons en **jours**, pas en heures comme pour
MovementQualificationStrategy, cohérent avec la granularité D1 d'ETF_FLOW) : taux de cas où le prix,
dans les N jours suivants, s'arrête ou se retourne plutôt que de continuer dans le sens du mouvement
initial (la strategy prédit un mouvement "non soutenu institutionnellement", donc moins durable).

**Groupe de contrôle indispensable** : tirer un échantillon de jours aléatoires de la même série
(même asset, nombre comparable) et mesurer le même taux de retournement/continuation dessus. Un edge
n'a de valeur que si son taux diverge significativement du groupe de contrôle.

Documenter aussi la **fréquence** du cas divergent par asset (proportion sur l'ensemble des jours
alignés) — répond à la question 3 de l'objectif. Si l'échantillon divergent est trop petit pour un
test statistique solide (ordre de grandeur à juger en pratique, mais signaler explicitement si <20-30
occurrences), le dire clairement dans le verdict plutôt que de tirer une conclusion sur un échantillon
insuffisant.

## Étape 4 — Sensibilité aux paramètres (par asset)

Grille sur les 3 seuils les plus structurants :
- `flowSignificanceThresholdUsd` ∈ {25M, 50M, 100M}
- `magnitudeScaleFactor` ∈ {2.0, 3.0, 4.0}
- `priceMoveThreshold` ∈ {0.01, 0.02, 0.03}

(3^3 = 27 combinaisons.) Pour chacune, refaire l'étape 3 et noter la stabilité du taux de
retournement obtenu, par asset séparément — un effondrement vers ~50% (comportement aléatoire) ou
une forte instabilité selon le seuil signale une formule fragile.

## Étape 5 — Périmètre

BTCUSDT et ETHUSDT (les 2 seuls assets `EtfFlowAsset`), timeframe D1 (seul défaut de la strategy en
prod). Résultats et verdict présentés côte à côte mais **jamais fusionnés** dans une seule
statistique — c'est la demande explicite de Clem.

## Definition of done

1. `tools/calibration/export_etf_flow_history.py` et `tools/calibration/etf_flow_calibration.py`,
   réutilisables (même patron que les scripts REJECTION_ZONE/MOVEMENT_QUALIFICATION, pas des
   scripts jetables).
2. `docs/calibration/calibration-etf-flow.md`, même structure que `docs/calibration/calibration-movement-qualification.md` :
   données utilisées (avec le nombre de jours réellement exportés par asset), résultat du test
   statistique par asset (+ groupe de contrôle), résultat de la grille de sensibilité par asset,
   fréquence du cas divergent par asset, **deux verdicts explicites et indépendants** (BTC / ETH :
   calibré tel quel / seuils à ajuster + nouvelles valeurs proposées / pas d'edge robuste, ne pas
   brancher par défaut).
3. Mémoire projet mise à jour avec les deux verdicts (même format que
   `tradeio5_rejection_zone_calibration_verdict`/`tradeio5_movement_qualification_calibration_verdict`).
4. **Ne pas** toucher `EtfFlowConfidenceStrategy.java` (formule/seuils de production) ni la brancher
   par défaut dans `DefaultMarketOpinion` avant d'avoir ces deux verdicts — explicitement la
   décision que ce prompt doit permettre de prendre, pas une étape à anticiper.

## Limites déjà identifiées à garder en tête pendant l'analyse

- Échantillon plus petit que les calibrations précédentes : ETF_FLOW est quotidien (D1) et n'existe
  que depuis le lancement de chaque ETF (BTC : 18 mois ; ETH : 12 mois), très loin des ~2.5 ans
  H1/quotidien des calibrations REJECTION_ZONE/MOVEMENT_QUALIFICATION. Le verdict "pas assez de
  signal pour conclure" est une issue légitime ici, pas un échec du protocole.
- ETH a structurellement moins de recul que BTC (lancement ~6 mois plus tard) : un verdict différent
  entre les deux assets peut simplement refléter une différence de puissance statistique, pas une
  vraie différence de comportement de marché — le préciser explicitement si les deux verdicts
  divergent.
- `EtfFlowConfidenceStrategy` est un `CONFIDENCE_MODULATOR` (jamais de signal directionnel, atténue
  seulement) — réduit le risque d'un mauvais signal si branchée avec des seuils imparfaits, mais ne
  dispense pas de la calibration (même remarque que pour MovementQualificationStrategy).
