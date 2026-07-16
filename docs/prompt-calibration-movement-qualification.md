# Prompt de calibration — MovementQualificationStrategy

Prompt autonome, à exécuter dans une session avec accès réseau réel (pas le bac à sable Cowork —
même limitation que celle documentée dans `docs/calibration-rejection-zone.md` : trafic sortant
restreint, `api.binance.com`/`coinalyze.net` hors allowlist). Utiliser le pattern déjà établi
`tools/calibration/*.py` + `ssh-gateway` (build/test réel), ou une machine locale avec Python 3 et
accès réseau normal.

**Contexte** : `MovementQualificationStrategy` (OI + Funding Rate + OBV, cf.
`service/tree/strategy/impl/MovementQualificationStrategy.java`) est codée, testée (tests unitaires
purs sur `computeSignal`), et résolue architecturalement comme `StrategyType.CONFIDENCE_MODULATOR`
depuis le 2026-07-15 (cf. mémoire `tradeio5_confidence_modulator_2026-07-15`). Mais contrairement à
`RejectionZoneIndicator`, elle n'a **jamais été confrontée à des données réelles** — ses seuils
(-10%/+10% sur l'OI, 0.05%/1% sur le funding, 2% de mouvement de prix, lookback 10 bougies, période
OBV) sont des valeurs de bon sens, jamais calibrées ni backtestées. `RejectionZoneIndicator` a
justement montré, sur ce même projet, qu'un raisonnement intuitif peut s'avérer faux une fois
confronté à des données réelles (durée/volume de zone → réaction plus forte, alors que
l'observation empirique a montré l'inverse) — et dans son cas, la calibration réelle a fini par
conclure de **ne pas brancher** l'indicateur en prod. Cette strategy mérite le même traitement
avant qu'on lui donne un vrai point d'entrée en production (scheduler).

## Lire avant de commencer

1. `service/tree/strategy/impl/MovementQualificationStrategy.java` — la formule exacte à
   reproduire fidèlement en Python (méthode `computeSignal`, 3 cas mutuellement exclusifs).
2. `docs/calibration-rejection-zone.md` — protocole de référence (calibration visuelle, test
   statistique vs baseline aléatoire, sensibilité aux paramètres) et son verdict final
   (`tradeio5_rejection_zone_calibration_verdict` : aucun edge robuste en walk-forward, ne pas
   brancher). Même méthodologie à appliquer ici, adaptée à une strategy multi-indicateurs plutôt
   qu'à un indicateur de niveaux de prix.
3. `tools/calibration/rejection_zone_calibration.py` et `tools/calibration/fetch_real_klines.py` —
   patron de script à réutiliser (réimplémentation Python fidèle de la formule Java, CLI avec
   `argparse`, sortie texte structurée par section).
4. `service/tree/indicator/external/OpenInterestIndicator.java`,
   `service/tree/indicator/external/coinalyze/CoinalyzeClient.java`,
   `service/tree/indicator/external/coinalyze/CoinalyzeSymbolResolver.java` — comprendre comment OI
   et le symbole Coinalyze sont résolus côté Java (pour reproduire à l'identique côté script).
5. `service/tree/indicator/impl/ObvIndicator.java` — formule OBV exacte (cumul borné à une fenêtre
   de `period+1` bougies, pas un OBV "depuis l'origine du marché").

## Rappel de la formule à calibrer (ne pas la modifier avant d'avoir des résultats)

Sur une évaluation donnée (`oiCurrent`, `oiPrevious`, `funding`, `obv`, `priceChangePct` sur une
fenêtre de lookback) :

1. **Cascade de liquidations** : `oiDelta <= -10%` ET mouvement de prix marqué (`|priceChangePct| >=
   2%`) → score négatif (mouvement jugé peu durable).
2. **Sur-effet-de-levier en construction** : `fundingSignal >= 0.6` (funding normalisé) ET
   `oiDelta >= +10%` ET `priceChangePct > 0` → score négatif (risque de retournement à venir).
3. **Conviction spot** : `oiDelta >= 0` ET `|fundingSignal| <= 0.3` ET `obv > 0` → score positif.
4. Sinon : score neutre (0.0), "aucun pattern net détecté".

Détail des seuils par défaut et de `normalizeFundingSignal`/`computePriceChangePct` : voir le
fichier source directement, ne pas les redevoir de mémoire dans le script Python — recopier les
valeurs exactes.

## Objectif de la calibration

Répondre à 3 questions, dans l'ordre :

1. **Les 3 cas typés (cascade / buildup / conviction) ont-ils un pouvoir prédictif réel**, ou
   classent-ils des points qui se comportent ensuite comme n'importe quel point aléatoire de la
   série ?
2. **Les seuils par défaut sont-ils stables**, ou un score/edge qui s'effondre dès qu'on bouge un
   seuil de quelques points signale une formule qui "marche" seulement par hasard sur ce jeu de
   données précis ?
3. **Le signal est-il assez fréquent pour être utile** ? (si "aucun pattern détecté" représente
   99% des évaluations, le modulateur ne sert jamais en pratique, même s'il est statistiquement
   valide sur les rares cas où il se déclenche).

## Étape 1 — Récupérer les données réelles

Trois séries à construire, sur les **mêmes timestamps H1**, pour BTCUSDT et ETHUSDT, sur la plage
la plus longue possible (viser au moins 12-18 mois, à défaut de reproduire les ~2.5 ans du run réel
`RejectionZoneIndicator` — **vérifier en pratique la profondeur d'historique réellement retournée
par Coinalyze avant de fixer la fenêtre**, ne pas supposer qu'elle couvre la même période que
Binance ; c'est un point d'attention explicite, pas une formalité).

**a. OHLCV (prix + volume)** — réutiliser `tools/calibration/fetch_real_klines.py` tel quel
(`--interval 1h`), aucune modification nécessaire. Sert à la fois pour `priceChangePct` et pour
calculer OBV localement (voir point c).

**b. Open Interest + Funding Rate historiques** — nouveau script,
`tools/calibration/fetch_coinalyze_history.py`. Points d'attention à ne pas deviner :
- **Résoudre le symbole Coinalyze dynamiquement**, comme le fait `CoinalyzeSymbolResolver` côté
  Java (`GET /exchanges` puis `GET /future-markets`, filtrer sur l'exchange "Binance" et
  `symbolOnExchange == BTCUSDT`/`ETHUSDT`) — le suffixe exact (ex. `BTCUSDT_PERP.A`) ne doit pas
  être codé en dur/deviné.
- **Open Interest** : `GET /open-interest-history?symbols=<code>&interval=1hour&from=<epoch>&to=<epoch>&api_key=<clé>`
  (mêmes query params que `CoinalyzeClient.fetchOpenInterestHistory`, mais appelée directement en
  Python plutôt qu'en réutilisant le client Java).
- **Funding Rate** : `CoinalyzeClient.java` n'expose aujourd'hui qu'un point courant
  (`fetchFundingRate`, `/funding-rate`), pas d'historique — mais l'API Coinalyze réelle expose bien
  `/funding-rate-history` (mêmes query params que l'OI). Appeler cet endpoint directement (il n'a
  simplement pas encore de wrapper Java, ce n'est pas bloquant pour un script de calibration
  autonome). Si l'endpoint n'existe pas/renvoie une erreur claire, le documenter immédiatement dans
  le doc de résultats plutôt que de forcer une solution de contournement.
- **Rate limit** : 40 appels/minute par clé (cf. javadoc `CoinalyzeClient`, non géré côté client
  Java aujourd'hui). Paginer/espacer les appels explicitement dans le script (`time.sleep`), sur le
  modèle de `fetch_real_klines.py` (`--sleep`, poli envers l'API).
- Réutiliser la clé déjà en place dans `application-dev.properties`
  (`tradeio.coinalyze.apiKey`) — ne pas en générer une nouvelle.

**c. OBV** — **ne pas fetcher séparément**, se calcule localement à partir des mêmes données OHLCV
(close + volume) récupérées en (a), en reproduisant exactement `ObvIndicator.compute` : cumul sur
une fenêtre glissante de `period + 1` bougies (paramètre `period` à fixer, ex. 20, cohérent avec un
usage H1 — expliciter le choix dans le doc de résultats), pas un OBV cumulé depuis l'origine de la
série. Attention au piège documenté dans le code source lui-même : la valeur absolue d'OBV n'est
pas comparable d'une fenêtre à l'autre, seule la stratégie utilise `Math.signum(obv)` — reproduire
ce même usage (signe uniquement), ne pas sur-interpréter la magnitude.

## Étape 2 — Réimplémentation Python fidèle

Un script `tools/calibration/movement_qualification_calibration.py`, même patron que
`rejection_zone_calibration.py` :
- Fonction pure `compute_signal(...)` qui reproduit **exactement** `MovementQualificationStrategy.computeSignal`
  (mêmes 3 cas, mêmes formules de `normalizeFundingSignal`/magnitude/clamp01) — à valider en
  premier avec quelques cas de test unitaires Python calqués sur
  `MovementQualificationStrategyTest.java` (mêmes entrées → même score/reason attendus), pour
  garantir que le script teste bien la formule de production et pas une approximation.
- Boucle sur chaque timestamp H1 où les 3 séries (OHLCV, OI, funding) sont disponibles et où assez
  d'historique existe pour calculer `priceChangePct` (lookback 10) et OBV (period choisi) :
  calculer `oiDelta`/`fundingSignal`/`obv sign`/`priceChangePct`, classer le point (cascade /
  buildup / conviction / neutre).

## Étape 3 — Test statistique (analogue au "taux de réaction" de REJECTION_ZONE)

Pour chaque cas typé, mesurer un résultat "forward" sur plusieurs horizons (ex. 6h, 12h, 24h après
le point classé) :

- **Cascade** : la strategy prédit un mouvement "peu durable" → mesurer le taux de cas où le prix,
  dans les N bougies suivantes, **s'arrête ou se retourne** plutôt que de continuer dans le sens du
  mouvement initial.
- **Buildup (sur-effet-de-levier)** : la strategy prédit un "risque de retournement violent à venir"
  → mesurer le taux de retournement à la baisse dans les N bougies suivantes.
- **Conviction spot** : la strategy prédit un mouvement "de qualité" → mesurer le taux de
  continuation dans le même sens dans les N bougies suivantes.

**Groupe de contrôle indispensable** (comme les "niveaux aléatoires" de `RejectionZoneIndicator`) :
tirer un échantillon de points aléatoires de la même série (même distribution temporelle, nombre
comparable) et mesurer le même taux de continuation/retournement dessus. Un cas typé n'a de valeur
que si son taux diverge significativement du groupe de contrôle — sinon la strategy classe des
points qui se comportent comme n'importe quel point de la série, exactement le risque identifié
dans `RejectionZoneIndicator` avant sa calibration réelle.

Documenter aussi la **fréquence** de chaque cas (proportion cascade/buildup/conviction/neutre sur
l'ensemble des points) — répond à la question 3 de l'objectif (signal assez fréquent ou pas).

## Étape 4 — Sensibilité aux paramètres

Grille sur les seuils les plus structurants (analogue aux 81 combinaisons de
`RejectionZoneIndicator`) :
- `oiDeltaCascadeThreshold` ∈ {-0.05, -0.10, -0.15}
- `oiDeltaBuildupThreshold` ∈ {0.05, 0.10, 0.15}
- `fundingBuildupSignalThreshold` ∈ {0.4, 0.6, 0.8}
- `priceMoveThreshold` ∈ {0.01, 0.02, 0.03}

(3^4 = 81 combinaisons, même ordre de grandeur.) Pour chacune, refaire l'étape 3 et noter la
stabilité du taux de continuation/retournement obtenu — un effondrement vers ~50% (comportement
aléatoire) ou une forte instabilité selon le seuil signale une formule fragile.

## Étape 5 — Périmètre

BTCUSDT et ETHUSDT (les 2 seuls actifs pour lesquels Coinalyze/le reste du pipeline sont déjà
validés), timeframe H1 (défaut de la strategy en prod, cf.
`StrategyParametersFactory.MovementQualificationParam.defaults`).

## Definition of done

1. `tools/calibration/fetch_coinalyze_history.py` et
   `tools/calibration/movement_qualification_calibration.py`, réutilisables (même patron que les
   scripts REJECTION_ZONE, pas des scripts jetables).
2. `docs/calibration-movement-qualification.md`, même structure que
   `docs/calibration-rejection-zone.md` : données utilisées (avec la profondeur d'historique
   réellement obtenue), résultat du test statistique par cas typé (+ groupe de contrôle), résultat
   de la grille de sensibilité, fréquence de chaque cas, **verdict explicite** (calibré tel quel /
   seuils à ajuster + nouvelles valeurs proposées / pas d'edge robuste, ne pas brancher — sur le
   modèle du verdict déjà rendu pour `RejectionZoneIndicator`).
3. Mémoire projet mise à jour avec le verdict (même format que
   `tradeio5_rejection_zone_calibration_verdict`).
4. **Ne pas** toucher `MovementQualificationStrategy.java` (formule/seuils de production) ni ajouter
   de scheduler avant d'avoir ce verdict — c'est explicitement la décision que ce prompt doit
   permettre de prendre, pas une étape à anticiper.

## Limites déjà identifiées à garder en tête pendant l'analyse

- OBV ne porte que son signe (pas de pente/momentum) — si le test statistique montre que le cas
  "conviction spot" est celui qui performe le moins bien des 3, c'est le suspect le plus probable
  (cf. discussion préalable) : envisager, dans le doc de résultats, un test complémentaire avec un
  taux de variation d'OBV sur quelques bougies à la place du signe brut, pour voir si ça change la
  conclusion — sans le faire remonter dans le code de prod avant validation.
- Le fait que `MovementQualificationStrategy` soit maintenant un `CONFIDENCE_MODULATOR` (plancher
  d'atténuation à 0.5, jamais de zéro brutal) réduit le risque d'un mauvais signal si jamais elle
  est branchée avec des seuils imparfaits — mais ne dispense pas de la calibration : un modulateur
  qui atténue la confidence dans le mauvais sens reste nuisible, juste de façon moins brutale qu'un
  signal directionnel `DIRECTIONAL` qui aurait été faux.
