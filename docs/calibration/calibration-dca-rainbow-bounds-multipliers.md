# Calibration — DCA Rainbow : bornes % valides et méthodes de sortie ARMÉ

> **Statut 2026-10-04** : bench des bornes en **%** (`RainbowDcaBacktestService`), **supplanté** par les bornes ATR ([`calibration-rainbow-atr-v3-regime.md`](calibration-rainbow-atr-v3-regime.md)). Conservé tant que le code correspondant n'est pas jugé inutile.

Référencé depuis `RainbowDcaBacktestRequest` (javadoc classe) et `docs/etudes/etude-dca-tool-mcp.md`
§11/§12, qui documentent la conception et l'implémentation du mécanisme lui-même — **ce document-ci
documente uniquement l'usage du bench et ses résultats**, dans l'esprit de
`calibration-rejection-zone.md` (protocole + verdict, pas un journal de conception).

## Règles ARMÉ/cooldown (mise à jour 2026-09-29)

Le comportement des deux machines à état ARMÉ (achat/vente) a changé le 2026-09-29 sur demande
explicite de Clem — description faisant foi dans le javadoc de `RainbowDcaBacktestService`, résumé
ici pour qui consulte ce document en premier :

- `FIXED_DELAY` reste un compte de jours fixe depuis l'armement (formule de
  `buyTriggered`/`sellTriggered` inchangée).
- Tant qu'une vente est ARMÉE, **tout achat est bloqué** (armement, déclenchement ET achat de zone
  intermédiaire) — évite les yoyo et les ventes multiples.
- Le cooldown (`cooldownDays`, déclenché par une vente exécutée) bloque désormais **à la fois**
  l'armement d'une nouvelle vente, le déclenchement d'une vente déjà armée et tout achat — plus
  seulement le montant acheté comme avant cette date.

Ceci change les résultats de tout run de bench antérieur au 2026-09-29 (moins d'achats/ventes
qu'avant dans certains scénarios) : un résultat produit avant cette date n'est plus reproductible
tel quel avec le code actuel.

## Monotonie des bornes (mise à jour 2026-09-30)

`RainbowDcaBacktestService#validate()` exige désormais, aussi bien en mode % qu'en mode ATR :

```
down2 >= down1 >= 0  <=  SMA  <=  0 <= up1 <= up2 <= up3
```

Égalité permise (pour supprimer une zone en la réduisant à largeur nulle — ex. `up3=up2` annule
NO_BUY), mais toute inversion lève une `DcaException`. Avant cette date, rien ne validait cet ordre
côté ATR : le coordinate ascent du bench pouvait retenir un `combinedCandidate` avec `up2>up3`, ce
qui produit des achats de zone parasites au-delà du seuil EXTREME_HAUT (cf. étude §28/§29 pour le
diagnostic complet). **`BULL_SEP24_FEV25` et `SIDEWAYS` (presets ci-dessous) sont dans ce cas et
doivent être régénérés** en relançant `RainbowDcaAtrTrendBenchExportTest` avec cette validation
active avant d'être réutilisés tels quels.

## Objectif

Trouver, pour BTC uniquement (le multi-actif n'est pas dans le périmètre — cf.
`docs/CODING_RULES.md`, "ne pas coder ce dont on n'a pas besoin"), quelles bornes % Rainbow
(`percDown2/percDown1/percUp1/percUp2/percUp3`) et quelle méthode de sortie des états ARMÉ
(`ReentryMode` : trailing stop / franchissement pur / délai fixe, cf. étude §12) tiennent sur
plusieurs régimes de marché, plutôt que sur une seule fenêtre bull comme le run initial du §11.

## Outil

`RainbowDcaBacktestManualRunnerTest.java` (`src/test/java/.../service/dca/`) — runner manuel
`@Disabled` par défaut, même convention que `YoutubeManualNetworkTest` (réseau + DB réels, pas de
tool MCP ni d'endpoint REST pour ce lot). Deux méthodes de test, indépendantes :

- `runBoundsCalibration_realBtcHistory` — grille sur les 5 bornes %, reste des paramètres aux
  défauts V0.
- `runReentryMethodsComparison_realBtcHistory` — grille sur `buyReentryMode`/`sellReentryMode`/
  `trailingStopBuyPercent`/`trailingStopSellPercent`/`cooldownDays`/`fixedDelayDays`/`sellFraction`, bornes % fixées aux
  défauts V0.

## Comment lancer le bench

1. Dans `RainbowDcaBacktestManualRunnerTest.java`, commenter l'annotation `@Disabled` sur la classe.
2. Lancer `test:tradeio-5` (build local), ou directement les 2 méthodes depuis l'IDE si seul un
   sous-ensemble est voulu — chaque méthode peut tourner indépendamment.
3. Ne pas oublier de recommenter `@Disabled` après coup : ce test reste hors de la suite par défaut
   (accès réseau réel, ~1700 backtests au total, quelques secondes à quelques minutes selon la
   machine — parallélisé sur autant de threads que de coeurs disponibles).
4. Récupérer les résultats sous `target/rainbow-dca-bench/` (non versionné, régénéré à chaque run) :
   - `<bench>-detail-<timestamp>.csv` : une ligne par (jeu de paramètres × fenêtre), toutes les
     métriques brutes (`totalInvested`, `pnlPercent`, `deltaVsFixed`, `maxDrawdownPercent`, ...).
   - `<bench>-aggregated-<timestamp>.csv` : une ligne par jeu de paramètres, delta de PnL% vs DCA
     fixe par fenêtre + `minDelta` (métrique de robustesse, cf. Méthodologie) + `avgDelta`.
   - Séparateur `;` (Excel FR). Le résumé console (top 15 par `minDelta`) donne un premier aperçu
     sans ouvrir les CSV.

## Méthodologie (résumé — détail complet dans l'étude §12)

- **3 fenêtres fixes** plutôt qu'une fenêtre glissante : `BULL_2023_2024`, `BEAR_2021_2022`
  (top → bottom du cycle), `SIDEWAYS_2018_2019`. La proposition de valeur de Rainbow (vendre en
  haut, racheter plus bas) ne peut se matérialiser que sur un cycle complet — le constat du run
  initial (§11 de l'étude, "aucune combinaison ne bat le DCA fixe") venait d'un test bull-only,
  pas d'un verdict sur la stratégie.
- **Classement par robustesse** : `minDelta` = le pire des 3 deltas de PnL% (Rainbow − DCA fixe)
  sur les 3 fenêtres. Un jeu de paramètres classé en tête a un delta acceptable dans **chacun** des
  3 régimes, pas seulement dans celui où il brille le plus — même logique que le walk-forward de
  `calibration-rejection-zone.md` (éviter de retenir un réglage qui n'a eu de la chance qu'une fois).
- **Max drawdown** (`position × close`, pic-à-creux, calculé dans le runner) : une stratégie qui
  désinvestit en haut de cycle peut avoir un `pnlPercent` inférieur au DCA fixe tout en portant
  moins de risque — à lire en complément du delta, pas à sa place.
- **Point d'attention méthodologique, non résolu par ce bench** : Rainbow investit systématiquement
  moins que le DCA fixe (cf. §11 — il laisse du cash de côté en zone haute). Comparer des `pnlPercent`
  sur des `totalInvested` différents n'est pas une comparaison à iso-capital ; un rendement pondéré
  dans le temps (XIRR) serait plus correct mais n'est pas implémenté (cf. §12, volontairement hors
  scope). Lire `deltaVsFixed`/`minDelta` en gardant ça en tête, pas comme un verdict définitif.

## Résultats

_Pas encore lancé sur données réelles depuis cette réécriture (2026-09-13) — l'environnement Cowork
qui a écrit ce bench n'a pas d'accès réseau/DB vers Binance/la base locale. À compléter par Clem
après le premier run réel : coller ici le top 5-10 de chaque CSV agrégé (bornes et méthodes de
sortie), avec la même honnêteté que `calibration-rejection-zone.md` — un edge qui ne survit que sur
une fenêtre n'est pas un edge._
