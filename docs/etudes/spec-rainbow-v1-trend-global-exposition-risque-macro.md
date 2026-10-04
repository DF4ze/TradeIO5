# Spec V1 — DCA Rainbow : Trend global, Curseur d'exposition, Embrayage risque macro

> **Mise à jour 2026-10-04** : le §1 (4 combos de bornes **%**) est **caduc** — le Rainbow est passé aux bornes ATR (`service/dca/atr`) et le paramétrage par trend est **Bull/Bear (2 jeux par actif, pas de Sideways)**. La question ouverte est le branchement d'une Trend (simple, ou double global/local) vers ces jeux : [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §2. Le §2 (curseur d'exposition) et le §3 (risque macro) restent des intentions non implémentées.

Date : 2026-09-25. Fait suite à [[tradeio5_rainbow_etude_2026-09-25_regime_exposition_risque]] et aux
échanges du même jour avec Clem. Les décisions ci-dessous sont actées par Clem ; les points marqués
« À trancher à l'implémentation » sont des choix techniques laissés ouverts, pas des décisions
produit. Cette spec ne code rien — elle cadre le prochain(s) prompt(s) d'implémentation.

Portée : toujours BTC seul (comme V0), toujours en D1. Aucun des 3 points n'est backtestable sur un
historique de score macro pour l'instant (cf. §3) — à traiter comme limite connue, pas comme blocage
de la spec.

---

## 1. Trend global + Trend local → 4 paramétrages Rainbow (CADUC, voir bandeau)

**Décision** : pas de M1/W1. Les deux indicateurs de trend tournent sur **D1**, mais avec un
paramétrage d'hystérésis différent — le "global" doit être nettement moins réactif que le "local"
actuel. Les deux trends passent à **2 états seulement : UP / DOWN** (suppression du RANGE pour cet
usage). Matrice 2×2 = **4 combos** (global UP/local UP, global DOWN/local UP, global UP/local DOWN,
global DOWN/local DOWN).

**Décision (revient sur la reco initiale de l'étude du 25/09)** : les 4 combos ont chacun leur
**jeu complet de bornes % Rainbow** (`percdown2`/`percdown1`/`percup1`/`percup2`/`percup3`), pas
seulement des multiplicateurs différents sur une géométrie de zone commune. Un bench de calibration
dédié doit déterminer, pour chacun des 4 états, le meilleur jeu de bornes (et multiplicateurs
associés). Conséquence assumée : surface de calibration ×4 (20 bornes au lieu de 5) et possibilité
de saut de zone à la transition d'un combo à l'autre (le prix peut changer de zone sans avoir bougé,
simplement parce que le régime a basculé) — accepté par Clem.

**Architecture proposée** :
- Réutiliser `TrendAnalyzer`/`RegressiveTrendScoreCalculator`/`RegressionHysteresisCalculator` tel
  quel comme moteur, mais instancié deux fois avec des jeux de constantes différents :
  - **Trend local** (existant, inchangé) : fenêtres 7/14/30, hystérésis entrée ±1/6, sortie 0.
  - **Trend global** (nouveau) : fenêtres plus longues (ex. 30/60/90 — valeurs exactes à calibrer,
    pas de valeur "évidente" comme pour le local) et/ou seuils d'entrée/sortie plus larges, pour
    filtrer le bruit que le local capte légitimement.
- **Ne pas toucher à `TrendRegime` existant** (consommé ailleurs, ex. `TrendConfirmationStrategy`) :
  introduire une classification à 2 états dédiée à cet usage (ex. `RainbowTrendDirection.UP/DOWN`),
  dérivée du `score` continu de `TrendState`.
- `RainbowDcaBacktestRequest` porte alors 4 jeux de paramètres complets (bornes + multiplicateurs)
  (`Map<RainbowTrendCombo, RainbowParams>` ou 4 champs nommés UP_UP/UP_DOWN/DOWN_UP/DOWN_DOWN),
  sélection du jeu actif à chaque jour de la boucle de `RainbowDcaBacktestService.backtest()` à
  partir des deux `TrendState` du jour (précalculés en amont sur toute la timeline, comme le fait
  déjà le bench Trend unifié).
- **Règle de passage à 2 états — les deux variantes seront implémentées et comparées au bench** (pas
  de choix a priori) :
  - (a) signe du score continu : `score > 0 => UP`, `score < 0 => DOWN`.
  - (b) hystérésis à 2 états avec dead-band propre (ex. `score > +seuil => UP`, `score < -seuil =>
    DOWN`, entre les deux => état précédent conservé), pour éviter le flip-flop autour de zéro.
  Le bench de calibration doit produire les deux jeux de résultats (4 combos × règle a, 4 combos ×
  règle b) pour permettre à Clem de trancher sur données réelles.
- **Transition pendant un armement (tranché)** : le jeu de paramètres actif est réévalué chaque
  jour, y compris pendant un armement ARMÉ/DÉCLENCHÉ en cours — pas d'état "jeu figé au moment de
  l'armement" à mémoriser (calcul stateless sur les 2 `TrendState` du jour). Conséquence assumée :
  les bornes/seuils de déclenchement peuvent différer entre le jour d'armement et le jour de
  déclenchement si le combo change entretemps.

**À trancher à l'implémentation** :
- Fenêtres et seuils exacts de l'hystérésis "global" (nécessite le bench de calibration dédié,
  comme celui qui a produit les seuils du local) — ce même bench doit aussi produire les 4 jeux de
  bornes % Rainbow et les résultats comparés règle (a) vs (b).
- Valeur du dead-band pour la variante (b).

---

## 2. Curseur d'exposition (achat + vente, piloté par l'utilisateur)

**Décision** : "Exposition" = % de la valeur totale en cryptos vs % en stablecoin. Un futur curseur
utilisateur (page web à venir, hors scope de cette spec) fixe une **exposition cible** (0-100%). Le
mécanisme Rainbow doit interpréter cette cible comme une **intention directionnelle explicite**, pas
juste une correction : à 0%, Rainbow ne doit plus que vendre (peu importe la zone) ; à 100%, ne plus
qu'acheter. Entre les deux, l'intensité d'achat/vente doit être réglable en continu.

**Architecture proposée** :
- Nouveau service dédié de sizing (`ExposureSizingService` ou équivalent), séparé de
  `RainbowZone`/`RainbowDcaBacktestService` qui restent inchangés dans leur rôle (classification de
  zone / orchestration du backtest). Ce service prend en entrée : `zoneMultiplier` (sortie actuelle
  de `resolveIntermediateMultiplier`), `expositionActuelle`, `expositionCible` — et retourne les
  montants/fractions finaux d'achat et de vente.
- Pour V1 en **backtest**, pas besoin d'attendre le vrai `WalletSnapshot` (toujours vide en prod,
  cf. étude §1.3) : le backtest connaît déjà en interne sa position et peut simuler un wallet
  fictif (solde stablecoin de départ + valeur position courante), donc calculer une exposition
  simulée sans dépendance externe. Nouveau(x) champ(s) sur `RainbowDcaBacktestRequest` :
  `initialWalletValue` (ou équivalent) + `targetExposurePercent`.
- Formule de départ proposée (à valider/calibrer, pas figée) :
  `intensitéAchat = zoneMultiplier × clamp(expositionCible − expositionActuelle, 0, 1) × k_achat`
  `intensitéVente = sellFraction_base × clamp(expositionActuelle − expositionCible, 0, 1) × k_vente`
  avec gestion explicite des cas dégénérés cible=0% (achat forcé à 0 quelle que soit la zone) et
  cible=100% (vente forcée à 0).
- En production/live (au-delà du backtest), ce même service consommerait le vrai `WalletSnapshot`
  une fois `openPositions`/`investedValue` peuplés (chantier séparé, non prérequis pour tester le
  concept en V1 backtest).

**À trancher à l'implémentation** :
- Forme exacte de la fonction d'intensité (linéaire proposée ci-dessus vs autre courbe) — sujet à
  calibration comme les autres paramètres Rainbow.
- Le curseur influence-t-il aussi `cooldownDays` post-vente, ou seulement les quantités ? (Ouvert.)
- Priorité en cas de conflit entre signal de zone Rainbow et signal d'exposition (ex. zone
  EXTREME_BAS mais déjà à 100% d'exposition cible → on bloque l'achat, on le réduit, ou on ignore la
  cible ponctuellement ?).

---

## 3. Risque macro comme "embrayage" via `riskCursor`

**Décision, reformulation importante par rapport à l'étude du 25/09** : `riskCursor` (0-10, déjà
persisté sur `UserTradingSettings`, aujourd'hui orphelin) n'est **pas** un facteur de tolérance
personnelle statique. C'est un **gain/embrayage** appliqué à un **score de risque marché calculé**
(à partir des indicateurs macro existants : DXY, S&P/Nasdaq, ETF flow, calendrier macro, etc.).
`riskCursor = 0` → aucune incidence du risque macro sur le Rainbow (embrayage débrayé) ;
`riskCursor = 10` → incidence maximale.

**Architecture proposée (2 briques distinctes)** :
1. **Un nouveau calculateur de score de risque marché**, indépendant de `Decision.confidence`
   (toujours inexploitable pour un backtest historique — event-sourcé, pas d'historique requêtable
   par date, cf. étude §Point 3). Ce calculateur agrège les indicateurs/Opinions macro déjà
   existants (`GlobalMarketOpinion`, `MacroMarketOpinion`, éléments déjà utilisés par
   `MacroRiskWindowModulator`) en un score continu normalisé (échelle à définir, ex. -1..+1 ou
   0..1). C'est une **nouvelle** agrégation : ces briques sont aujourd'hui internes au moteur
   Opinion/Decision tactique, pas exposées telles quelles pour un usage DCA.
2. **`riskCursor` comme gain multiplicatif** sur l'effet de ce score : `effetRisque = score ×
   (riskCursor / 10)`, combiné aux autres facteurs (zone Rainbow, exposition) sur les quantités
   achat/vente — position dans la chaîne de multiplication et signe exact à définir avec Clem au
   moment de l'implémentation (ex. `montantFinal = baseAmount × zoneMultiplier × facteurExposition ×
   (1 − effetRisque)` pour un risque qui freine l'achat, ou une formule dédiée si le risque doit
   aussi accélérer la vente).
- `UserTradingSettingsService`/`riskCursor` (déjà persisté, endpoint REST existant) sert de gain
  utilisateur ; le score de risque marché est calculé côté serveur, indépendant de l'utilisateur.

**Limite connue, à trancher avec Clem avant de coder** :
- Un score de risque macro **historique rejouable jour par jour sur plusieurs années** n'existe pas
  aujourd'hui (mêmes indicateurs macro que ceux consommés par le moteur Opinion, mais jamais
  restitués comme série temporelle backtestable). Deux options : (a) construire ce score uniquement
  pour un usage **live** dans un premier temps (le backtest V1 reste sans risque macro, comme V0
  aujourd'hui), ou (b) investir d'abord dans l'historisation des séries macro nécessaires pour
  pouvoir le rejouer en backtest — effort nettement plus lourd, à chiffrer séparément si souhaité.

**À trancher à l'implémentation** :
- Quels indicateurs macro exacts entrent dans le score, et avec quelle pondération (nouveau sujet
  de calibration, pas de valeur "par défaut" identifiée dans le code existant).
- Formule exacte de combinaison de `effetRisque` avec les facteurs Trend (§1) et Exposition (§2) —
  ordre de priorité entre les 3 mécanismes si tous actifs simultanément.
- Portée : ce score de risque doit-il rester propre au DCA Rainbow, ou est-il conçu dès le départ
  pour être réutilisable par le chantier Decision→Order plus large (qui a le même besoin non résolu,
  cf. `PLACEHOLDER_QUANTITY` dans `DefaultMarketScenario`) ?

---

## Hors scope de cette spec (V1)
- Page web de pilotage du curseur d'exposition (mentionnée par Clem, à spécifier séparément).
- Wallet réel branché (`WalletSnapshot.openPositions`/`investedValue`) — reste un chantier séparé.
- Historisation des séries macro pour un backtest de risque rejouable (si l'option (b) du §3 est
  retenue).
- Multi-actif (ETH, etc.) — tout reste BTC seul comme V0.

## Prochaine étape suggérée
Rédiger un prompt d'implémentation par point (comme pour V0), dans l'ordre suggéré : §1 (Trend
global, le plus autonome et le moins dépendant des deux autres) → §2 (Exposition, testable en
backtest avec wallet fictif) → §3 (Risque macro, nécessite d'abord de trancher live-vs-backtest).
À confirmer avec Clem avant de lancer la rédaction des prompts.
