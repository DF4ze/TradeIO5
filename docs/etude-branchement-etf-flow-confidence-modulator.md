# Étude — Brancher `ETF_FLOW` comme `CONFIDENCE_MODULATOR` (2026-07-16)

> **Implémenté le 2026-07-16.** Les 5 décisions §9 ont été tranchées avec Clem puis codées telles
> quelles (aucun écart) : formule §4 simplifiée (cohérent/neutre fusionnés) retenue, seuils $50M/x3
> gardés comme point de départ, `D1`/1 bougie de lookback (pas H1/10 comme les 2 autres modulateurs),
> **pas** branchée par défaut dans `DefaultMarketOpinion` (ad hoc uniquement via `evaluate_strategy`/
> `get_opinion`), liste blanche `{BTCUSDT, ETHUSDT}` plutôt qu'un préfixe. Nouveau fichier
> `EtfFlowConfidenceStrategy` + fabriques `StrategyParametersFactory.buildEtfFlowConfidenceStrategyParam`/
> `MarketOpinionParametersFactory.buildLocalOpinionParamWithEtfFlow`/`IndicatorParametersFactory.buildEtfFlowParams`.
> 359 tests (348 + 11 nouveaux), vérifié en réel via `evaluate_strategy` après redémarrage de l'app :
> résolution `EtfFlowConfidenceStrategy` non ambiguë, asset correctement dérivé du symbole (BTC total
> 107 804 554 vs ETH total 53 829 997, mêmes valeurs que la vérification en réel de la migration
> SoSoValue), `SOLUSDT` correctement rejeté ("hors périmètre ETF_FLOW"), score neutre le jour du test
> (mouvement de prix D1 sous le seuil de 2%, comportement attendu, pas un bug).
>
> **Suite le même jour** : `EtfFlowIndicator` appelait SoSoValue à chaque évaluation alors que la
> donnée ne change qu'une fois par jour — cache DB + historisation ajoutés,
> cf. `docs/etude-cache-etf-flow-historisation.md`.

Suite attendue de deux chantiers déjà fermés : le sourcing (`docs/etude-sourcing-etf-flow-alternative-farside.md`,
`ETF_FLOW` sur SoSoValue depuis le 2026-07-16) et le mécanisme générique de modulation
(`docs/etude-unification-confidence-modulator.md`/`-implementation.md`, `StrategyType.CONFIDENCE_MODULATOR`
+ `ConfidenceModulator`/`ConfidenceModulation`/`StrategyConfidenceModulator`, 320 tests). Le plan
retenu depuis `docs/etude-nouvelles-opinions-indicateurs-non-branches.md` §5 était : "brancher
`ETF_FLOW` comme `StrategyType.CONFIDENCE_MODULATOR` (patron `MovementQualificationStrategy`/
`OrderFlowStrategy`), restreint aux symboles BTC*/ETH*, jamais comme signal directionnel". Cette
étude conçoit ce branchement en détail — **c'est une étude, pas une implémentation** : aucun code
n'est modifié ici.

## 0. Verdict en une ligne

Le mécanisme générique (`ConfidenceModulator`) est déjà en place et n'a besoin d'aucune modification
— brancher `ETF_FLOW` revient à écrire **une seule nouvelle classe** (`EtfFlowConfidenceStrategy`,
même patron que `MovementQualificationStrategy`/`OrderFlowStrategy`) + deux petites méthodes de
fabrique (mêmes patrons que l'existant). Deux corrections au plan initial, découvertes en relisant le
code réel plutôt que la description de haut niveau du plan (§2) : la restriction BTC*/ETH* ne peut
pas passer par `Strategy.accepts(...)` (qui n'a pas accès au symbole) et le paramètre `asset` ne doit
surtout pas être pré-rempli statiquement dans les `IndicatorParameters` (même piège que le bug
`get_indicator` corrigé plus tôt aujourd'hui).

## 1. Rappel du mécanisme disponible (pas à recoder)

- `StrategyType.CONFIDENCE_MODULATOR` : une `Strategy` qui le déclare (`getType()`) n'est plus
  agrégée par `StrategyAggregator` avec les Strategies `ENTRY` — `AbstractMarketOpinion.decide` la
  sépare automatiquement (`isConfidenceModulator`).
- Chaque `StrategyKey` de type `CONFIDENCE_MODULATOR` est enrobée par `StrategyConfidenceModulator`
  (`service/tree/opinion/modulator/`), qui appelle `strategy.evaluate(context.marketContext(),
  parameters)` puis `MarketOpinionHelper.computeConfidenceModulationFactor(score)` :
  - `score >= 0` → facteur `1.0` (aucune atténuation, jamais de bonus).
  - `score < 0` → facteur `1 / (1 + |score|)`, borné à `[0.5, 1.0]`.
  - `StrategySignal` invalide (`isValid() == false`) → `ModulationResult(applied=false, factor=1.0,
    ...)`, **ignoré silencieusement** (juste un warn loggé par `AbstractMarketOpinion`), jamais de
    faute propagée.
- `ConfidenceModulation.evaluateAll`/`combinedFactor` fait le produit des facteurs `applied` de tous
  les modulateurs fournis à une `MarketOpinion` — `EtfFlowConfidenceStrategy` s'additionnera aux
  modulateurs déjà branchés (`MovementQualificationStrategy`, `OrderFlowStrategy`) sans qu'aucun des
  deux n'ait besoin d'être touché.
- Aucun nouveau fichier requis dans `service/tree/opinion/modulator/` : c'est le même
  `StrategyConfidenceModulator` générique qui enrobera `EtfFlowConfidenceStrategy`, exactement comme
  il enrobe déjà les deux Strategies existantes.

## 2. Deux corrections au plan initial

### 2.1 La restriction "BTC*/ETH* uniquement" ne peut pas passer par `accepts(...)`

Le plan disait "restreint aux symboles BTC*/ETH* via `Strategy.accepts(...)`". En relisant
`Strategy.accepts(StrategyParameters parameters)` : cette méthode ne reçoit que les
`StrategyParameters` (composition d'indicateurs), **jamais le symbole évalué** — le symbole n'existe
que dans `MarketContext` (`context.symbol()`), disponible seulement à `evaluate(MarketContext,
StrategyParameters)`. `StrategyRegistry.resolveBestMatch` n'appelle `accepts(...)` que pour
désambiguïser entre plusieurs Strategies du même `StrategyType` partageant la même composition
d'indicateurs — non pertinent ici (`ETF_FLOW` n'est consommé par aucune autre Strategy).

**Correction** : `accepts(...)` garde son rôle usuel (vérifier "exactement 1 `ETF_FLOW`", même
patron que les deux Strategies existantes) ; la restriction BTC*/ETH* se fait **dans `evaluate(...)`**,
en lisant `context.symbol()`. Pour tout symbole hors BTC*/ETH*, `evaluate` retourne
`StrategySignal.notValid(...)` → `StrategyConfidenceModulator` traduit ça en
`ModulationResult(applied=false, factor=1.0)` → **aucune atténuation, ignoré proprement** — exactement
le comportement souhaité ("jamais qui la crée" pour un symbole hors périmètre), obtenu par le chemin
normal du mécanisme plutôt que par un branchement spécial.

### 2.2 Le paramètre `asset` ne doit jamais être pré-rempli statiquement

Piège déjà rencontré aujourd'hui avec `get_indicator` (cf. `docs/etude-sourcing-etf-flow-alternative-farside.md`,
addendum) : `EtfFlowIndicator` lit `asset` ("BTC"/"ETH") comme un paramètre *string* de ses propres
`IndicatorParameters`. Les fabriques existantes (`StrategyParametersFactory.buildMovementQualificationStrategyParam`,
`buildOrderFlowStrategyParam`) construisent des `IndicatorParameters` **une fois**, réutilisables pour
n'importe quel symbole (aucune des deux n'a de notion de symbole — c'est `MarketContext.symbol()`,
résolu seulement à `evaluate()`, qui varie). Si `asset` était fixé à la construction (ex: toujours
"BTC" par défaut), `EtfFlowConfidenceStrategy` interrogerait systématiquement les ETF Bitcoin même
pour une évaluation sur `ETHUSDT` — silencieusement faux, comme l'était `get_indicator` avant
correction.

**Correction** : `EtfFlowConfidenceStrategy.evaluate(...)` reconstruit une copie de
l'`IndicatorParameters` fournie (mêmes `numerics`/`booleans`/`credential`, `strings` réécrit avec
`EtfFlowIndicator.P_ASSET` déduit de `context.symbol()`) **avant** d'appeler
`indicatorEngine.execute(...)`, plutôt que de faire confiance au `asset` éventuellement présent dans
les `IndicatorParameters` fournies par l'appelant. Résolution symbole → asset : `symbol.startsWith("BTC")
→ EtfFlowAsset.BTC`, `symbol.startsWith("ETH") → EtfFlowAsset.ETH`, sinon aucun des deux → notValid
(cf. §2.1).

## 3. Design de `EtfFlowConfidenceStrategy`

Patron de référence : `OrderFlowStrategy` (3 cas explicites — cohérent / divergent / neutre — plus
proche de ce qu'on veut ici que les 3 cas de `MovementQualificationStrategy`, qui sont spécifiques à
OI/funding).

```java
@Component
public class EtfFlowConfidenceStrategy extends AbstractStrategy {

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_FLOW_SIGNIFICANCE_THRESHOLD_USD = "flowSignificanceThresholdUsd";
    public static final String P_PRICE_MOVE_THRESHOLD = "priceMoveThreshold";
    public static final String P_PRICE_LOOKBACK_CANDLES = "priceLookbackCandles";

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {
        // 1. exactement 1 IndicatorKey de type ETF_FLOW (comme accepts()) — sinon notValid()
        // 2. asset = résolu depuis context.symbol() (§2.2) — sinon notValid() "symbole hors BTC*/ETH*"
        // 3. IndicatorParameters reconstruites avec le bon "asset", credential/numerics/booleans
        //    copiés tels quels depuis l'entrée fournie
        // 4. indicatorEngine.execute(...) -> total (USD brut, cf. §4 unité)
        // 5. priceChangePct sur la fenêtre priceLookbackCandles (même calcul que
        //    MovementQualificationStrategy/OrderFlowStrategy, dupliqué — cf. §7 note factorisation)
        // 6. computeSignal(total, priceChangePct, seuils) -> score [-1,1] (cf. §4)
    }

    @Override
    public Set<StrategyType> getType() {
        return Set.of(StrategyType.CONFIDENCE_MODULATOR);
    }

    @Override
    public boolean accepts(StrategyParameters parameters) {
        // exactement 1 IndicatorType.ETF_FLOW — même patron que les 2 Strategies existantes
    }
}
```

## 4. Formule proposée (point de départ, pas calibrée empiriquement)

Même réserve explicite que `MovementQualificationStrategy`/`OrderFlowStrategy` à leur création
("point de départ, à ajuster empiriquement") — aucune des deux n'a été calibrée sur données réelles
avant branchement, et c'est resté acceptable pour ce type de signal (modulateur, jamais directionnel).
`ETF_FLOW` n'échappe pas à cette règle.

**Entrées** : `total` (flux net ETF du jour, USD brut — cf. §5 pour le rappel d'unité),
`priceChangePct` (même calcul `computePriceChangePct` que les 2 Strategies existantes, sur
`priceLookbackCandles`).

**Idée directrice** : cohérence entre le flux institutionnel et le mouvement de prix récent. Un flux
qui confirme le mouvement (prix monte + argent institutionnel entre, ou prix baisse + argent sort) ne
doit jamais atténuer — c'est le cas normal/attendu. Un flux qui **contredit** le mouvement (prix monte
alors que l'argent institutionnel sort, ou l'inverse) est le signal de fragilité recherché : le
mouvement de prix n'est pas soutenu par la demande institutionnelle, potentiellement porté par un
autre facteur (retail, dérivés) plus fragile.

```
flowDirection = sign(total)
priceDirection = sign(priceChangePct)
markedPriceMove = |priceChangePct| >= priceMoveThreshold
significantFlow = |total| >= flowSignificanceThresholdUsd

si !markedPriceMove || !significantFlow:
    score = 0.0, reason = "pas de mouvement de prix marqué ou flux ETF non significatif"

sinon si flowDirection == priceDirection:
    score = 0.0 (jamais de bonus, cf. computeConfidenceModulationFactor : score >= 0 => facteur 1.0
                 de toute façon — magnitude positive calculée uniquement pour la traçabilité/reason)
    reason = "flux ETF cohérent avec le mouvement de prix, aucune atténuation"

sinon (flowDirection != priceDirection, divergence) :
    magnitude = clamp01(|total| / (flowSignificanceThresholdUsd * magnitudeScaleFactor))
    score = -magnitude
    reason = "mouvement de prix non soutenu par le flux ETF institutionnel (divergence) : confiance atténuée"
```

`magnitudeScaleFactor` (ex: 3.0, seuil "pleine atténuation" = 3x le seuil de significativité) est le
même genre de paramètre "à ajuster empiriquement" que `oiDeltaCascadeThreshold`/`oiDeltaBuildupThreshold`
côté `MovementQualificationStrategy`.

**Seuil de significativité `flowSignificanceThresholdUsd`** — point le plus ouvert de cette étude,
au même titre que le seuil de liquidations l'était pour `OrderFlowStrategy` (§4.2 de son étude
d'origine). Contrairement aux liquidations (qui ont un volume de référence directement comparable,
`volume × close` sur le même exchange/fenêtre), le flux ETF est un nombre TradFi agrégé
(tous émetteurs US confondus), sans dénominateur naturel côté marché crypto spot à normaliser dessus
— un ratio au volume Binance BTCUSDT n'aurait pas de sens (échelles et fenêtres temporelles
incomparables : ETF_FLOW est un flux quotidien post-clôture US, le volume spot est continu 24/7).
Proposition : seuil absolu en USD (ex: 50M$, à valider/ajuster avec Clem), documenté comme point de
départ, pas comme valeur mesurée.

## 5. Rappel — unité USD brut, pas millions

Déjà documenté dans `SosoValueEtfFlowClient` (javadoc) suite à la migration Farside → SoSoValue :
`total` est en USD brut (`-55066297.0`), pas en millions ("US$m") comme l'aurait suggéré l'historique
Farside. `flowSignificanceThresholdUsd` doit être exprimé dans la même unité (`50_000_000.0`, pas
`50.0`) — piège de conversion facile si on recopie un seuil "à l'œil" depuis un dashboard qui affiche
des millions.

## 6. Intégration — fabriques, même patron que l'existant

Deux méthodes à ajouter, mêmes signatures que celles déjà en place pour
`MovementQualificationStrategy`/`OrderFlowStrategy` :

- `StrategyParametersFactory.buildEtfFlowConfidenceStrategyParam(EtfFlowConfidenceParam param,
  ApiCredentialDTO sosoValueCredential)` — construit l'unique `IndicatorKey`/`IndicatorParameters`
  `ETF_FLOW` (asset laissé absent/non pertinent ici, cf. §2.2 — résolu dynamiquement à `evaluate()`,
  pas à la construction) + les seuils de la Strategy (`numericParams`).
  `sosoValueCredential` résolue par l'appelant, ex. `IndicatorCredentialResolver.resolve(IndicatorType.ETF_FLOW)`
  (déjà branché sur `WebProviderCode.SOSOVALUE` depuis la migration du 2026-07-16 — **aucun nouveau
  travail de credential nécessaire**).
- `MarketOpinionParametersFactory.buildLocalOpinionParamWithEtfFlow(Strategy strategy,
  StrategyParametersFactory.EtfFlowConfidenceParam param, ApiCredentialDTO sosoValueCredential)` —
  même patron que `buildLocalOpinionParamWithOrderFlow`, un seul `StrategyKey`, à concaténer par
  l'appelant avec Trend/MovementQualification/OrderFlow dans une seule `DefaultMarketOpinion` si
  besoin (`MarketOpinionParameters.strategies()`, déjà conçu pour ça).

Rien à toucher dans `AbstractMarketOpinion`, `ConfidenceModulation`, `StrategyConfidenceModulator`,
`DefaultMarketOpinion` : le mécanisme générique absorbe la nouvelle Strategy sans modification, exactement
la promesse de l'étude "unification-confidence-modulator" §4 ("ces branchements deviennent des ajouts
triviaux une fois l'interface en place").

## 7. Fichiers impactés

**Nouveaux** :
- `service/tree/strategy/impl/EtfFlowConfidenceStrategy.java`
- `EtfFlowConfidenceStrategyTest.java` (patron `OrderFlowStrategyTest`/`MovementQualificationStrategyTest` :
  `computeSignal` testé en pur, `accepts()` testé, `evaluate()` testé pour le cas hors BTC*/ETH*)

**Modifiés** :
- `StrategyParametersFactory.java` — nouvelle méthode + `EtfFlowConfidenceParam` (record/classe de
  seuils, même patron que `MovementQualificationParam`/`OrderFlowParam`).
- `MarketOpinionParametersFactory.java` — nouvelle méthode `buildLocalOpinionParamWithEtfFlow`.

**Non touché** (confirmé par §1/§6) : `AbstractMarketOpinion`, `ConfidenceModulation`,
`StrategyConfidenceModulator`, `ModulationResult`, `ConfidenceModulator`, `IndicatorCredentialResolver`
(déjà résolu), `EtfFlowIndicator`/`SosoValueEtfFlowClient` (aucun changement de contrat requis — `asset`
reste un paramètre optionnel de l'indicateur, seule la Strategy appelante change la façon de le fournir).

**Note factorisation (hors scope, à signaler)** : `computePriceChangePct` est maintenant dupliqué
trois fois à l'identique (`MovementQualificationStrategy`, `OrderFlowStrategy`, et ce futur
`EtfFlowConfidenceStrategy`). Candidat naturel à extraire en méthode statique partagée (`AbstractStrategy`
ou une classe utilitaire `StrategyPriceHelper`) — pas fait dans cette étude pour rester focalisée sur
le branchement `ETF_FLOW`, mais à envisager si un 4e modulateur du même genre apparaît (ATR,
calendrier macro évoqués comme candidats futurs dans l'étude "unification-confidence-modulator" §4).

## 8. Garantie de non-régression attendue

Même critère que les branchements précédents : aucune `MarketOpinion`/Strategy existante ne change de
comportement observable tant que `EtfFlowConfidenceStrategy` n'est pas explicitement ajoutée à une
liste de `StrategyKey` par un appelant (MCP `evaluate_strategy`/`get_opinion`, ou une future fabrique
appelée en production). Tests existants (348 à ce jour) inchangés ; nouveaux tests uniquement pour la
nouvelle classe.

## 9. Décisions à valider avec Clem avant codage

1. Formule §4 (cohérent/divergent/neutre) — confirmer l'idée directrice ("divergence flux/prix =
   fragilité") avant d'écrire le code, plutôt que de découvrir un désaccord après coup.
2. `flowSignificanceThresholdUsd` : valeur de départ (proposition 50M$) et `magnitudeScaleFactor`
   (proposition 3.0) — aucun des deux n'est mesuré, à traiter comme `OrderFlowStrategy` en 2026-07-15
   (accepté comme point de départ raisonnable, pas bloquant).
3. `priceMoveThreshold`/`priceLookbackCandles` : réutiliser les mêmes valeurs par défaut que
   `MovementQualificationStrategy`/`OrderFlowStrategy` (2%, 10 bougies) par cohérence, ou des valeurs
   propres à `ETF_FLOW` (cadence quotidienne, potentiellement un lookback plus long a du sens
   puisque `ETF_FLOW` lui-même n'est mis à jour qu'une fois par jour) ?
4. Faut-il brancher `EtfFlowConfidenceStrategy` dans `DefaultMarketOpinion` (aux côtés de
   `MovementQualificationStrategy`/`OrderFlowStrategy`) dès ce lot, ou le laisser d'abord accessible en
   ad hoc via `evaluate_strategy`/`get_opinion` (comme `OrderFlowStrategy` l'a été un temps) avant de
   l'intégrer par défaut ?
5. Restriction BTC*/ETH* (§2.1) : confirmer la règle `symbol.startsWith("BTC"/"ETH")` — couvre
   `BTCUSDT`/`ETHUSDT` (cas visés), mais aussi des paires moins évidentes comme `ETHBTC` (mappée sur
   ETH par cette règle) : à valider que c'est le comportement voulu ou s'il faut une liste blanche de
   symboles exacts plutôt qu'un préfixe.

## 10. Hors scope de cette étude

- Écrire le code (cette étude est un design, pas une implémentation — à faire dans un lot séparé une
  fois les points §9 validés).
- Détail par émetteur (`byIssuer`) — toujours vide côté `SosoValueEtfFlowClient` (décision déjà prise,
  §2.1 de l'étude sourcing), donc non exploitable ici même si on le voulait.
- Factorisation de `computePriceChangePct` (§7, note) — signalée, pas traitée.
- Toute extension côté `DecisionEngine` (gate/sizing) — déjà explicitement hors scope de
  "unification-confidence-modulator" §4, s'applique de la même façon ici.
