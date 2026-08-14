# Prompt d'implémentation — Décision, Palier 3, Étape 8 (calendrier macro dans le cycle décisionnel)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 8** (optionnelle) de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md`. Références :
`docs/suivi/point-avancement-2026-08-10.md` §3 et §6.2 pt 6 (addendum), et
`service/tree/opinion/modulator/ConfidenceModulation.java` (javadoc, qui cite déjà "calendrier macro"
comme modulateur candidat futur). **Prérequis** : Étape 7 (orchestrateur) mergée — cette étape agit sur
les Opinions que l'orchestrateur consomme, elle n'a de sens qu'une fois le cycle automatique en place.

**Décisions prises avec Clem le 2026-08-14, avant rédaction de ce prompt** :

1. **Résolution du conflit de périmètre** : le point d'avancement du 2026-08-10 (§6.2 pt 6) dit que
   l'intégration du calendrier macro "dépend de #5" (le composant d'exécution réelle d'ordres), or
   l'exécution réelle est explicitement **hors périmètre** du Palier 3 (en-tête de la roadmap). Clarifié
   par Clem : le périmètre du Palier 3 est "tout ce qui alimente la décision" (l'orchestrateur et tout ce
   qui s'y connecte) — **seule l'exécution d'un ordre proprement dite est hors périmètre**. Cette étape
   est donc bien dans le périmètre du Palier 3 : elle module une confidence en amont de la Decision,
   jamais l'exécution (qui n'existe pas encore dans le code).
2. **Placement architectural retenu : un nouveau `ConfidenceModulator`**, pas un garde-fou dans
   `DecisionOrchestrator`. Réutilise l'infrastructure déjà en place
   (`service/tree/opinion/modulator/`, étude "unification-confidence-modulator") plutôt que d'inventer un
   mécanisme séparé au niveau du cycle — cohérent avec le javadoc de `ConfidenceModulation`, qui cite déjà
   "calendrier macro" comme extension naturelle de ce patron. `DecisionOrchestrator` (étape 7) n'est **pas
   modifié** par ce lot : le nouveau modulateur agit en amont, à l'intérieur du calcul de l'Opinion, avant
   même que l'`OpinionSignal` ne soit retourné par `TreeAnalysisFacade.getOpinion(...)`.
3. **Branché dans `GlobalMarketOpinion` et `MacroMarketOpinion`** (les deux, pas un seul) : les deux
   portent déjà chacune un modulateur de la même famille (`SentimentShiftModulator`/`StalenessModulator`)
   et sont, comme le nouveau modulateur, des signaux d'ambiance "sans symbole" (cf. fix de l'étape 7).
   **`AbstractMarketOpinion`/`DefaultMarketOpinion` (scope `LOCAL`) ne sont pas touchées** dans ce lot —
   question distincte, non tranchée ici, à reposer séparément si un jour la conviction "un risque macro
   imminent doit aussi atténuer les Opinions LOCAL" est validée.
4. **Aucune valeur de calibration n'existe** pour la fenêtre/le seuil d'impact/le facteur d'atténuation —
   mêmes réserves que tous les `DEFAULT_*` déjà présents dans ces deux classes (points de départ
   raisonnables, pas mesurés empiriquement) :
   - Fenêtre : **2h** de part et d'autre de l'événement (avant et après) — délai jugé suffisant pour
     couvrir la volatilité pré/post-annonce sans assécher le signal en continu.
   - Impact minimal déclenchant : **`HIGH`** uniquement — un FOMC/NFP/CPI, pas un événement `MEDIUM`/
     `LOW` (les plus fréquents, qui rendraient le modulateur actif en permanence s'il les incluait).
   - Facteur d'atténuation : **0.5** (division par deux de la confidence pendant la fenêtre, jamais
     annulation complète — même contrat que tout `ConfidenceModulator`, cf. `ModulationResult`).

**Ce que ce lot n'est PAS** : pas un changement de `DecisionOrchestrator`/`DecisionEngine` ; pas une
extension au scope `LOCAL` ; pas une calibration empirique des valeurs du point 4 (valeurs de départ
explicitement révisables) ; pas un branchement dans `ExternalMarketOpinion` (scope `EXTERNAL`, hors
périmètre de l'étape 7 et non concerné ici).

Avant de commencer, lire dans l'ordre :
1. `docs/suivi/point-avancement-2026-08-10.md` §3 (calendrier macro déjà connecté au MCP,
   `MacroEventCalendarService`/`isWithinRiskWindow` déjà écrits et testés) et §6.2 pt 6.
2. `service/tree/macro/MacroEventCalendarService.java` — `isWithinRiskWindow(Instant now, Duration
   window, MacroEventImpact minImpact)` : méthode déjà écrite, déjà testée, **réutilisée telle quelle**
   par ce lot, pas réécrite. Javadoc de classe : "volontairement non branché dans DecisionEngine/Scenario
   à ce stade" — phrase à retirer/mettre à jour dans ce lot puisque ce n'est plus le cas.
3. `service/tree/opinion/modulator/ConfidenceModulator.java` (interface),
   `ConfidenceModulation.java` (boucle commune `evaluateAll`/`combinedFactor`),
   `ModulationResult.java` (record, contrat `applied`/`factor ∈ ]0,1]`/`reason`).
4. `service/tree/opinion/modulator/StalenessModulator.java` et `SentimentShiftModulator.java` — patron
   exact à reproduire (adaptateur simple, valeurs déjà résolues au moment de la construction — sauf que
   pour ce nouveau modulateur, l'appel à `isWithinRiskWindow(...)` se fait dans `evaluate(...)`, pas à la
   construction, cf. étape 1 ci-dessous pour la justification).
5. `service/tree/opinion/impl/GlobalMarketOpinion.java` — constructeur (~ligne 92), bloc de construction
   des modulateurs et `ConfidenceModulation.evaluateAll(List.of(sentimentShiftModulator), ...)`
   (~lignes 190-195), points d'édition de ce lot.
6. `service/tree/opinion/impl/MacroMarketOpinion.java` — constructeur (~ligne 104), bloc équivalent
   (~lignes 170-176), points d'édition de ce lot.
7. `model/enumerate/tree/macro/MacroEventImpact.java` — `LOW`/`MEDIUM`/`HIGH`/`HOLIDAY`.
8. `service/market/DomainClock.java` — déjà injecté/accessible via `context.clock()` dans les deux
   classes concernées, source de `now` pour `isWithinRiskWindow(...)` (jamais `Instant.now()` en dur,
   même règle que le reste du projet).

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher `DecisionOrchestrator.java`
(étape 7, décision 2 ci-dessus), `DecisionEngine.java`, `ExternalMarketOpinion.java`,
`AbstractMarketOpinion.java`/`DefaultMarketOpinion.java` (scope `LOCAL`, décision 3 ci-dessus), ni la
logique interne de `MacroEventCalendarService` (réutilisée telle quelle).

---

## Étape 1 — `MacroRiskWindowModulator`

**Contexte** : contrairement à `StalenessModulator`/`SentimentShiftModulator` (qui enrobent une fonction
pure sur des valeurs déjà résolues avant construction), ce modulateur a besoin d'appeler un service
(`MacroEventCalendarService.isWithinRiskWindow`, qui fait un appel réseau vers Finnhub/ForexFactory) au
moment de `evaluate(...)`. Ce n'est pas une entorse au patron : `GlobalMarketOpinion`/`MacroMarketOpinion`
construisent leurs modulateurs juste avant de les évaluer immédiatement (`evaluateAll` les évalue une
seule fois, dans la foulée) — l'appel réseau a lieu au même moment que s'il avait été fait "à la main"
dans `decide()` avant construction, seule la responsabilité change de place.

**À faire**, `service/tree/opinion/modulator/MacroRiskWindowModulator.java` :
```java
package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventCalendarService;

import java.time.Duration;
import java.time.Instant;

/**
 * Adaptateur {@link ConfidenceModulator} pour {@link MacroEventCalendarService#isWithinRiskWindow}
 * (Palier 3, étape 8) : atténue la confidence d'une Opinion d'ambiance (GLOBAL/MACRO) pendant une
 * fenêtre autour d'un événement macro à fort impact (FOMC/NFP/CPI...), sans jamais l'annuler
 * (contrat {@link ModulationResult}, même règle que tout autre modulateur). Contrairement à
 * {@link StalenessModulator}/{@link SentimentShiftModulator}, l'appel réseau sous-jacent
 * (Finnhub/ForexFactory) a lieu dans {@link #evaluate}, pas à la construction — cf. javadoc du
 * prompt d'implémentation de cette étape.
 */
public class MacroRiskWindowModulator implements ConfidenceModulator {

    private final MacroEventCalendarService calendarService;
    private final Instant now;
    private final Duration window;
    private final MacroEventImpact minImpact;
    private final double dampeningFactor;

    public MacroRiskWindowModulator(
            MacroEventCalendarService calendarService, Instant now, Duration window,
            MacroEventImpact minImpact, double dampeningFactor) {
        this.calendarService = calendarService;
        this.now = now;
        this.window = window;
        this.minImpact = minImpact;
        this.dampeningFactor = dampeningFactor;
    }

    @Override
    public ModulationResult evaluate(OpinionContext context, MarketOpinionParameters parameters) {
        boolean atRisk = calendarService.isWithinRiskWindow(now, window, minImpact);
        if (!atRisk) {
            return new ModulationResult(true, 1.0, "hors fenêtre à risque macro");
        }
        String reason = String.format(
                "fenêtre à risque macro active (impact >= %s, +/- %s)", minImpact, window);
        return new ModulationResult(true, dampeningFactor, reason);
    }
}
```

**Tests attendus** (`MacroRiskWindowModulatorTest`) :
- `MacroEventCalendarService` mocké, `isWithinRiskWindow(...)` retourne `false` → `applied=true`,
  `factor=1.0`.
- `isWithinRiskWindow(...)` retourne `true` → `applied=true`, `factor=dampeningFactor` (pas `1.0`, pas
  `0`).
- Capture d'arguments : vérifier que `now`/`window`/`minImpact` passés au constructeur sont bien ceux
  transmis tels quels à `calendarService.isWithinRiskWindow(...)`.

---

## Étape 2 — Branchement dans `GlobalMarketOpinion`

**À faire** :

1. Constructeur (~ligne 92) : ajouter `MacroEventCalendarService calendarService` en dernier paramètre,
   nouveau champ `private final MacroEventCalendarService calendarService;`.
2. Nouvelles constantes (même bloc que `P_STABLECOIN_WEIGHT`, ~lignes 65-83) :
   ```java
   public static final String P_MACRO_RISK_WINDOW_HOURS = "macroRiskWindowHours";
   public static final String P_MACRO_RISK_MIN_IMPACT = "macroRiskMinImpact";
   public static final String P_MACRO_RISK_DAMPENING_FACTOR = "macroRiskDampeningFactor";

   // Fenêtre de part et d'autre d'un événement macro à fort impact pendant laquelle la confidence
   // est atténuée (Palier 3, étape 8) — valeur de départ proposée, pas mesurée empiriquement.
   private static final double DEFAULT_MACRO_RISK_WINDOW_HOURS = 2.0;
   private static final MacroEventImpact DEFAULT_MACRO_RISK_MIN_IMPACT = MacroEventImpact.HIGH;
   private static final double DEFAULT_MACRO_RISK_DAMPENING_FACTOR = 0.5;
   ```
3. Dans `decide(...)` (~lignes 190-195), ajouter le modulateur à la liste existante :
   ```java
   MacroRiskWindowModulator macroRiskWindowModulator = new MacroRiskWindowModulator(
           calendarService, context.clock().now(),
           Duration.ofHours(get(parameters, P_MACRO_RISK_WINDOW_HOURS, DEFAULT_MACRO_RISK_WINDOW_HOURS)),
           parameters != null
                   ? MacroEventImpact.valueOf(parameters.get(P_MACRO_RISK_MIN_IMPACT, DEFAULT_MACRO_RISK_MIN_IMPACT.name()))
                   : DEFAULT_MACRO_RISK_MIN_IMPACT,
           get(parameters, P_MACRO_RISK_DAMPENING_FACTOR, DEFAULT_MACRO_RISK_DAMPENING_FACTOR));

   List<ModulationResult> modulationResults = ConfidenceModulation.evaluateAll(
           List.of(sentimentShiftModulator, macroRiskWindowModulator), context, parameters);
   ```
   Vérifier le nom exact de la méthode d'accès générique utilisée ailleurs dans cette classe pour les
   paramètres numériques (`parameters.get(key, default)` vu à la lecture, confirmer avant d'écrire —
   `GlobalMarketOpinion` n'a pas de méthode statique `get(parameters, key, default)` contrairement à
   `MacroMarketOpinion`, à adapter en conséquence, ne pas copier-coller aveuglément entre les deux
   classes).

**Tests attendus** (`GlobalMarketOpinionTest`, existant) : ajouter un cas où `MacroEventCalendarService`
(mocké) signale une fenêtre à risque active → la confidence finale de l'`OpinionEvent` capturé est bien
réduite par le facteur attendu (0.5 par défaut) par rapport au même scénario sans fenêtre à risque, sans
changement du signal directionnel (`BULLISH`/`BEARISH`/`NEUTRAL` inchangé). Vérifier qu'aucun test
existant ne dépendait d'un `GlobalMarketOpinion` construit avec l'ancienne signature à 2 arguments
(rechercher avant de conclure).

---

## Étape 3 — Branchement dans `MacroMarketOpinion`

**À faire**, même logique qu'à l'étape 2 :

1. Constructeur (~ligne 104) : ajouter `MacroEventCalendarService calendarService`.
2. Mêmes 3 constantes `P_MACRO_RISK_*`/`DEFAULT_MACRO_RISK_*` que l'étape 2 (dupliquées entre les deux
   classes, pas mutualisées — même choix que `P_STALE_QUOTE_HOURS` qui existe séparément dans les deux
   classes sans mutualisation aujourd'hui, cohérent avec l'existant plutôt qu'une extraction non demandée).
3. Dans `decide(...)` (~lignes 170-176) :
   ```java
   MacroRiskWindowModulator macroRiskWindowModulator = new MacroRiskWindowModulator(
           calendarService, now,
           Duration.ofHours(get(parameters, P_MACRO_RISK_WINDOW_HOURS, DEFAULT_MACRO_RISK_WINDOW_HOURS)),
           parameters != null
                   ? MacroEventImpact.valueOf(parameters.get(P_MACRO_RISK_MIN_IMPACT, DEFAULT_MACRO_RISK_MIN_IMPACT.name()))
                   : DEFAULT_MACRO_RISK_MIN_IMPACT,
           get(parameters, P_MACRO_RISK_DAMPENING_FACTOR, DEFAULT_MACRO_RISK_DAMPENING_FACTOR));

   List<ModulationResult> modulationResults = ConfidenceModulation.evaluateAll(
           List.of(stalenessModulator, macroRiskWindowModulator), context, parameters);
   ```
   `now` ici est la variable locale déjà déclarée juste au-dessus (`Instant now = context.clock().now();`,
   ~ligne 169) — réutiliser, ne pas recalculer.

**Tests attendus** (`MacroMarketOpinionTest`, existant) : même patron que l'étape 2 — cas fenêtre à
risque active vs inactive, confidence atténuée en conséquence, signal directionnel inchangé.

---

## Étape 4 — Nettoyage documentaire

**À faire** :
1. `MacroEventCalendarService.java` (javadoc de classe, ~ligne 26-28) : retirer/mettre à jour la mention
   "volontairement non branché dans DecisionEngine/Scenario à ce stade" — ce n'est plus vrai après ce
   lot (branché indirectement via `GlobalMarketOpinion`/`MacroMarketOpinion`, qui alimentent bien
   `DefaultScenarioEngine`/`DecisionEngine` via l'orchestrateur de l'étape 7).
2. `docs/suivi/point-avancement-2026-08-10.md` §6.2 point 6 : ajouter une note indiquant que ce point a
   été traité par l'étape 8 du Palier 3, avec la clarification de périmètre actée en tête de ce prompt
   (le "dépend de #5" du document d'origine concernait l'exécution réelle, pas la modulation de
   confidence en amont — distinction non faite explicitement à l'époque).

**Pas de test associé** (changement documentaire uniquement).

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline obtenue après l'étape 7),
détail de tout échec, et signaler explicitement :
- le nom exact de la méthode d'accès aux paramètres utilisée dans `GlobalMarketOpinion` (étape 2, point
  d'attention déjà signalé — ne pas supposer qu'elle est identique à `MacroMarketOpinion`) ;
- si le changement de signature du constructeur de `GlobalMarketOpinion`/`MacroMarketOpinion` (nouveau
  paramètre `MacroEventCalendarService`) a cassé un test existant instanciant ces classes directement
  (rechercher tous les sites d'instanciation avant de conclure qu'il n'y en a pas, même règle que pour
  chaque changement de constructeur dans ce palier) ;
- tout autre écart pris par rapport à ce prompt.

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 8 → ✅) — dernière étape du
Palier 3. Le palier est alors terminé côté "tout ce qui alimente la décision" ; le composant d'exécution
réelle et le calcul de sizing (étude §12 pt 1, point d'avancement §6.2 pts 2-5) restent des chantiers
séparés, non entamés par ce palier.
