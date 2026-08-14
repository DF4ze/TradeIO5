# Prompt d'implémentation — Décision, Palier 3, Étape 7 (orchestrateur)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 7** de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md`. Référence :
`docs/etudes/etude-branchement-persistance-decision-engine.md` §E point 6. **Prérequis** : Étapes 1 et 2
mergées (moteur singleton partagé, owner en paramètre d'appel — dépendance dure, l'orchestrateur appelle
directement `ScenarioEngine.onMarketOpinion(...)`), Étapes 4 et 5 mergées (persistance photo/rejeu,
`User.lastLogin`), Étape 6 mergée (`User.archivedAt`, `evictOwner`) — les cinq confirmées en code avant
rédaction de ce prompt, pas supposées.

**Décisions prises avec Clem le 2026-08-14, avant rédaction de ce prompt** :

1. **Périmètre des actifs** : 3 actifs fixes du périmètre DCA — `BTC`, `ETH`, `PAXG`. Pas de déduction
   depuis les soldes de wallet (exclurait à tort un utilisateur qui veut démarrer un DCA sur un actif
   qu'il ne possède pas encore, cf. tour d'horizon du 2026-08-12). Liste taguée `// TODO parametrize`
   dans le code — décision de périmètre pour ce lot, pas une valeur figée définitivement.
2. **Verrou anti-doublon par owner** : mutex (un seul refresh actif à la fois pour un owner donné) +
   délai minimal **1h** avant un nouveau refresh (empêche cron + relance manuelle + connexion de se
   marcher dessus). Durée taguée `// TODO parametrize`.
3. **Déclencheur pour ce lot : uniquement cron désactivé par défaut + endpoint admin**, même patron que
   les étapes 4/6 (`UserArchivalJob`/`UserArchivalAdminController`). Le bouton utilisateur ("à la
   demande") et l'auto-sync à la connexion (patron Coinstats, mentionnés dans l'étude §E pt 6) restent
   **explicitement postposés** à une étape ultérieure — l'orchestrateur expose une méthode de service
   appelable directement, ces deux déclencheurs pourront s'y brancher plus tard sans changer sa forme.
4. **Périmètre des scopes d'Opinion calculés par cycle** :
   - `LOCAL`, une fois **par actif** (3 appels — analyse technique propre à chaque actif) ;
   - `GLOBAL` et `MACRO`, une fois **par cycle** (2 appels, pas répétés par actif) — ces deux scopes
     ignorent le symbole par construction (`GlobalMarketOpinion`/`MacroMarketOpinion`, cf. point 6
     ci-dessous) ;
   - **`EXTERNAL` explicitement exclu de ce lot** : vérifié en code, `ExternalMarketOpinion` lit bien
     `context.marketContext().symbol()` et fait un appel LLM par actif (comme LOCAL, pas comme
     GLOBAL/MACRO) — l'inclure aurait fait passer le cycle à 3 appels LLM automatiques par run, jugé
     trop coûteux/prématuré pour ce lot. Reste accessible en ad hoc via `get_opinion` (MCP).
   - Total : **5 appels d'Opinion par cycle** (3 LOCAL + 1 GLOBAL + 1 MACRO), propagés ensuite à chaque
     owner actif.
5. **Réutilisation de `TreeAnalysisFacade.getOpinion(symbol, scope, params)`** plutôt que de réimplémenter
   la capture synchrone d'`OpinionEvent` dans l'orchestrateur : cette méthode fait déjà exactement le
   patron `subscribe`/`unsubscribe` documenté dans le javadoc de
   `DefaultScenarioEngine.onOpinionEvent(...)` (méthode `decideAndCapture`, vérifiée en code) et retourne
   directement l'`OpinionSignal`. L'orchestrateur appelle donc `scenarioEngine.onMarketOpinion(signal,
   context)` directement, owner par owner — **`onOpinionEvent(...)` n'est pas utilisé par ce lot**, il
   reste la méthode de référence documentée pour un futur appelant alternatif, pas celle retenue ici.
6. **Bug découvert et corrigé dans ce lot** : `TreeAnalysisFacade.getOpinionCommon` construit **toujours**
   un `MarketContext` portant le symbole passé en argument (via `buildMarketContextForAsset`), y compris
   pour les scopes `GLOBAL`/`MACRO`. Or `GlobalMarketOpinion`/`MacroMarketOpinion` lisent
   `context.marketContext().symbol()` et l'incluaient dans l'`OpinionEvent` publié ("vérification
   défensive au cas où un symbole s'y glisserait") — ce qui contredit leur conception documentée ("pas de
   symbole par construction") et casserait silencieusement le filtre de scénario global
   (`ScenarioKey.symbol().isEmpty()`, utilisé par `getGlobalScenarios`) dès qu'un appelant (cet
   orchestrateur) leur passe un symbole non nul. **Corrigé à la source** (option retenue par Clem plutôt
   qu'un contournement côté orchestrateur) : ces deux classes forcent désormais `Optional.empty()`
   inconditionnellement, la "vérification défensive" est retirée. `TreeAnalysisFacade` elle-même n'est
   **pas modifiée** (le symbole doit continuer à circuler normalement pour LOCAL/EXTERNAL, qui en ont
   réellement besoin).
7. **Strategies par défaut du calcul LOCAL automatique** : aucun jeu "par défaut" n'existait en code prod
   avant ce lot (seuls des appels MCP au cas par cas ou des fixtures de test). Retenu — combinaison de 4
   `StrategyKey` (concaténation, patron déjà documenté dans `MarketOpinionParametersFactory`) :
   - `TrendConfirmation` : `TimeFrame.H1`, EMA rapide 10 / EMA lente 20, ADX(14), RSI(14), seuils
     `adxLowThreshold=15.0`, `adxHighThreshold=25.0`, `rsiOverboughtThreshold=80.0`,
     `rsiOversoldThreshold=20.0` — repris tels quels de `TrendConfirmationStrategy.DEFAULT_*` (mêmes
     valeurs que `TrendConfirmationStrategyTest`, pas inventées pour ce lot).
   - `MovementQualification` : `StrategyParametersFactory.MovementQualificationParam.defaults(H1, 14.0)`
     (fabrique déjà alignée sur `MovementQualificationStrategy.DEFAULT_*`, `obvPeriod=14.0` repris de
     `MarketOpinionParametersFactoryMovementQualificationTest`), credential Coinalyze résolue via
     `IndicatorCredentialResolver.resolve(IndicatorType.OPEN_INTEREST)`.
   - `OrderFlow` : `StrategyParametersFactory.OrderFlowParam.defaults(H1)` (idem, alignée sur
     `OrderFlowStrategy.DEFAULT_*`), credential Coinalyze résolue via
     `IndicatorCredentialResolver.resolve(IndicatorType.LIQUIDATIONS)`.
   - `EtfFlow` : `StrategyParametersFactory.EtfFlowConfidenceParam.defaults()` (`TimeFrame.D1` fixe,
     imposé par la Strategy elle-même), credential SoSoValue résolue via
     `IndicatorCredentialResolver.resolve(IndicatorType.ETF_FLOW)`. **Inclus par défaut dans ce lot** —
     bascule assumée par Clem le 2026-08-14 sur la base du recadrage de calibration du 2026-08-10
     ("utilisable telle quelle, rôle = validateur J-1"), qui contredisait le commentaire encore présent
     dans `MarketOpinionParametersFactory.buildLocalOpinionParamWithEtfFlow` ("pas branchée par défaut
     pour l'instant") — **ce commentaire doit être mis à jour dans ce lot** pour ne plus induire en
     erreur un futur lecteur.
   Toutes les credentials sont résolues via l'utilisateur technique "System" (`IndicatorCredentialResolver`,
   déjà indépendant de tout contexte utilisateur appelant) — aucun problème pour un appel automatique/
   planifié, pas de credential par-owner nécessaire ici.
   Ces valeurs numériques sont un point de départ pratique, pas calibré empiriquement pour ce lot — même
   réserve que partout ailleurs dans le projet pour ces constantes (`StrategyParametersFactory`
   `DEFAULT_*`) : à revisiter si l'algorithmie décisionnelle le justifie.
8. **`GLOBAL`/`MACRO`** : `strategies` ignoré par construction (vérifié en code,
   `getRequiredCandles` retourne `Map.of()` pour les deux) — appelés avec
   `MarketOpinionParameters.builder().build()` (aucune strategy). Le symbole passé en argument à
   `getOpinion(...)` (requis non-blanc par le contrat de la façade, `requireSymbol`) est arbitraire —
   utiliser `BTC` (premier élément de la liste des 3 actifs fixes) : sans conséquence depuis le fix du
   point 6, le résultat ne portera de toute façon jamais ce symbole.
9. **"Utilisateur actif" pour l'itération** = `enabled=true` **et** `archivedAt == null` (nouvelle requête
   `UserRepository`). Décision dérivée directement de l'étape 6, pas une nouvelle question ouverte :
   itérer sur un owner déjà archivé recréerait immédiatement l'état que l'archivage vient d'évincer,
   contredisant sa raison d'être. Un owner exclu par ce filtre n'est pas un cas d'erreur : il redevient
   éligible automatiquement dès sa restauration à la reconnexion (étape 6, hook `signinForm`).
10. **`DecisionEngine` n'est jamais appelé directement par l'orchestrateur.** Vérifié en code : son
    constructeur reste abonné à `ScenarioEvent` sur le bus partagé
    (`eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent)`), et `onMarketOpinion(...)`
    appelle déjà en interne `collectActionIntents(...)`, qui publie lui-même les `ScenarioEvent`
    `ACTION_PROPOSED` consommés par `DecisionEngine`. La chaîne complète Opinion → Scenario → Decision se
    déclenche donc en cascade dès que l'orchestrateur appelle
    `scenarioEngine.onMarketOpinion(signal, context)` — rien à ajouter côté `DecisionEngine`.
11. **"User×Wallet×Asset" (étude §E pt 6) devient "User×Asset" pour ce lot.** Vérifié en code :
    `ScenarioOwner.UserOwner` est indexé par `userId` seul (Palier 2), pas par wallet — il n'existe
    aujourd'hui aucune notion de wallet dans la clé d'un scénario/d'une décision. La résolution du wallet
    concret reste un problème de sizing, explicitement hors périmètre de ce palier (cf. en-tête de la
    roadmap). L'orchestrateur boucle donc sur owner × actif, pas sur owner × wallet × actif.

**Ce que ce lot n'est PAS** : pas de composant d'exécution réelle (`ProviderApiService.buy/sell`) ; pas
de calcul de sizing ; pas de calendrier macro dans le cycle (étape 8, optionnelle, après ce lot) ; pas de
déclenchement "à la demande" (bouton utilisateur) ni "à la connexion" (auto-sync au login) — conçus pour
pouvoir s'y greffer plus tard, pas construits ici ; pas d'Opinion `EXTERNAL` dans le cycle automatique.

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-branchement-persistance-decision-engine.md` §E point 6.
2. `service/tree/scenario/DefaultScenarioEngine.java` — javadoc de `onOpinionEvent(...)` (patron
   documenté mais **non retenu** pour ce lot, cf. décision 5) et `onMarketOpinion(...)` (retenu,
   cascade interne vers `collectActionIntents`).
3. `service/tree/decision/DecisionEngine.java` — constructeur (abonnement bus toujours actif),
   `onScenarioEvent(...)`, `isUnanimousAcrossScopes(...)` : confirmer qu'aucun changement n'y est
   nécessaire (décision 10).
4. `service/tree/api/mcp/TreeAnalysisFacade.java` — `getOpinion(String, OpinionScope,
   MarketOpinionParameters)` (ligne ~260), `getOpinionCommon(...)`, `decideAndCapture(...)`,
   `buildMarketContextForAsset(...)`, `requireSymbol(...)`.
5. `service/tree/opinion/impl/GlobalMarketOpinion.java` et `MacroMarketOpinion.java` — bloc
   "vérification défensive" en fin de `decide(...)` (respectivement ~ligne 203-206 et ~ligne 183-185),
   point d'édition du fix décrit en décision 6.
6. `service/tree/helper/MarketOpinionParametersFactory.java` et
   `service/tree/helper/StrategyParametersFactory.java` — les 4 fabriques utilisées
   (`buildLocalOpinionParamWithTrendConfirmation`/`...MovementQualification`/`...OrderFlow`/`...EtfFlow`)
   et leurs `Param.defaults(...)` respectifs.
7. `service/tree/indicator/IndicatorCredentialResolver.java` — résolution via l'utilisateur technique
   "System", indépendante de l'owner appelant.
8. `model/dto/tree/scenario/ScenarioOwner.java` — `UserOwner(String userId)`, `ScenarioOwner.of(User)`.
9. `security/model/User.java` / `security/repository/UserRepository.java` — champs `enabled`,
   `archivedAt`, `lastLogin` ; patron de requête dérivée déjà utilisé
   (`findByLastLoginBeforeAndArchivedAtIsNull`, étape 6) à reproduire pour la requête de ce lot.
10. `service/tree/decision/UserArchivalService.java` + `service/scheduler/UserArchivalJob.java` +
    `controller/UserArchivalAdminController.java` — patron exact à reproduire pour le service/job/
    controller de l'orchestrateur (cron désactivé par défaut + endpoint admin).
11. `model/dto/tree/scenario/ScenarioContext.java` — record, `owner`/`symbol`/`clock`/`globalScenarios`.

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher `ExternalMarketOpinion.java`
(scope EXTERNAL explicitement hors périmètre, décision 4) ni `DecisionEngine.java` (aucun changement
nécessaire, décision 10) ni `TreeAnalysisFacade.java` (le fix du point 6 se fait dans
`GlobalMarketOpinion`/`MacroMarketOpinion`, pas dans la façade).

---

## Étape 1 — Fix : `GlobalMarketOpinion`/`MacroMarketOpinion` ne portent jamais de symbole

**Contexte** : cf. décision 6 en tête de ce prompt. Sans ce fix, un appel `getOpinion("BTC", GLOBAL,
...)` produirait un scénario tagué `symbol="BTC"` au lieu d'un scénario réellement global, cassant
`getGlobalScenarios` pour tous les owners.

**À faire**, `GlobalMarketOpinion.java` (~ligne 203-206) :
```java
// Opinion GLOBAL : jamais de symbole (contexte de marché large, cf. javadoc classe) — quel que soit
// le symbole reçu dans le contexte (ex: appelé par l'orchestrateur, Palier 3 étape 7), il n'est
// jamais reporté sur l'OpinionEvent publié. Fix du 2026-08-14 : l'ancienne "vérification défensive"
// propageait par erreur ce symbole, cassant le filtre de scénario global (ScenarioKey.symbol().isEmpty()).
Optional<String> opinionSymbol = Optional.empty();
```
Même changement, `MacroMarketOpinion.java` (~ligne 183-185). Dans les deux fichiers, la variable locale
`symbol` (issue de `context.marketContext().symbol()`) reste utilisée pour construire `indicatorContext`
(nécessaire à la lecture des indicateurs) — seul le report dans `opinionSymbol`/l'`OpinionEvent` final
est supprimé.

**Tests attendus** : dans les tests existants de ces deux classes (`GlobalMarketOpinionTest`/
`MacroMarketOpinionTest`, vérifier le nom exact avant de conclure), ajouter un cas où `OpinionContext`
porte un `MarketContext` avec un symbole non nul (ex: `"BTC"`) : l'`OpinionEvent` capturé doit avoir
`getSymbol().isEmpty()`. Vérifier qu'aucun test existant ne dépendait du comportement inverse (recherche
avant de conclure).

---

## Étape 2 — Requête "utilisateur actif" (`UserRepository`)

**À faire** :
```java
List<User> findByEnabledTrueAndArchivedAtIsNull();
```

**Tests attendus** (`UserRepositoryTest`, `@DataJpaTest`, même patron que l'étape 6) : quatre `User` —
actif normal (`enabled=true`, `archivedAt=null`), désactivé (`enabled=false`, `archivedAt=null`), archivé
(`enabled=true`, `archivedAt` renseigné), désactivé et archivé — vérifier que seul le premier est
retourné.

---

## Étape 3 — Verrou anti-doublon par owner (`OwnerRefreshGuard`)

**À faire**, `service/tree/decision/OwnerRefreshGuard.java` :
```java
@Component
public class OwnerRefreshGuard {

    // TODO parametrize — valeur de départ actée par Clem (2026-08-14), à externaliser en property
    // Spring quand ce palier sera éprouvé en usage réel.
    private static final Duration MIN_INTERVAL = Duration.ofHours(1);

    private final Map<ScenarioOwner, OwnerRefreshState> states = new ConcurrentHashMap<>();

    /**
     * Tente d'acquérir le verrou pour cet owner : refuse si un refresh est déjà en cours (mutex) ou
     * si le précédent s'est terminé il y a moins de {@link #MIN_INTERVAL} (throttle). Les deux
     * mécanismes sont volontairement combinés dans une seule méthode — cf. étude §E pt 6 ("un seul
     * refresh actif à la fois, nouveau refresh bloqué tant qu'un délai minimal ne s'est pas écoulé").
     */
    public boolean tryAcquire(ScenarioOwner owner, Instant now) {
        OwnerRefreshState state = states.computeIfAbsent(owner, k -> new OwnerRefreshState());
        synchronized (state) {
            if (state.running) {
                return false;
            }
            if (state.lastCompletedAt != null
                    && state.lastCompletedAt.plus(MIN_INTERVAL).isAfter(now)) {
                return false;
            }
            state.running = true;
            return true;
        }
    }

    /** À appeler dans un {@code finally}, systématiquement après un {@link #tryAcquire} réussi. */
    public void release(ScenarioOwner owner, Instant completedAt) {
        OwnerRefreshState state = states.get(owner);
        if (state == null) return;
        synchronized (state) {
            state.running = false;
            state.lastCompletedAt = completedAt;
        }
    }

    private static class OwnerRefreshState {
        boolean running = false;
        Instant lastCompletedAt = null;
    }
}
```
`synchronized (state)` (plutôt qu'un simple `ConcurrentHashMap` sans section critique) : nécessaire
parce que `tryAcquire` fait un test-puis-set sur deux champs (`running`/`lastCompletedAt`) — une race
entre deux threads appelant `tryAcquire` pour le même owner au même instant pourrait sinon laisser
passer les deux.

**Tests attendus** (`OwnerRefreshGuardTest`) :
- `tryAcquire` puis `tryAcquire` immédiat pour le même owner → `false` (mutex).
- `tryAcquire`, `release`, puis `tryAcquire` immédiat → `false` (throttle, < 1h écoulée).
- `tryAcquire`, `release`, puis `tryAcquire` avec `now` avancé de plus d'1h → `true`.
- Deux owners distincts n'interfèrent jamais l'un avec l'autre.
- Test de concurrence réelle (ex. `ExecutorService` avec plusieurs threads appelant `tryAcquire` pour le
  même owner simultanément) : un seul obtient `true`.

---

## Étape 4 — `DecisionOrchestrator`

**À faire**, `service/tree/decision/DecisionOrchestrator.java` :
```java
@Service
@RequiredArgsConstructor
public class DecisionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DecisionOrchestrator.class);

    // TODO parametrize — périmètre DCA actuel (roadmap Palier 3, étape 7), pas une liste figée
    // définitivement.
    private static final List<String> TRACKED_ASSETS = List.of("BTC", "ETH", "PAXG");

    private final TreeAnalysisFacade treeAnalysisFacade;
    private final ScenarioEngine scenarioEngine;
    private final UserRepository userRepository;
    private final OwnerRefreshGuard refreshGuard;
    private final IndicatorCredentialResolver credentialResolver;
    private final DomainClock clock;

    public OrchestrationResult runCycle() {
        Instant now = clock.now();

        MarketOpinionParameters localParams = buildDefaultLocalOpinionParams();
        MarketOpinionParameters emptyParams = MarketOpinionParameters.builder().build();

        List<OpinionSignal> signals = new ArrayList<>();
        for (String asset : TRACKED_ASSETS) {
            signals.add(treeAnalysisFacade.getOpinion(asset, OpinionScope.LOCAL, localParams));
        }
        // GLOBAL/MACRO : une fois par cycle, symbole arbitraire (ignoré, cf. fix étape 1 — le
        // résultat ne porte jamais ce symbole).
        signals.add(treeAnalysisFacade.getOpinion(TRACKED_ASSETS.get(0), OpinionScope.GLOBAL, emptyParams));
        signals.add(treeAnalysisFacade.getOpinion(TRACKED_ASSETS.get(0), OpinionScope.MACRO, emptyParams));

        List<User> activeUsers = userRepository.findByEnabledTrueAndArchivedAtIsNull();

        int processed = 0;
        int skippedLocked = 0;
        for (User user : activeUsers) {
            ScenarioOwner owner = ScenarioOwner.of(user);
            if (!refreshGuard.tryAcquire(owner, now)) {
                skippedLocked++;
                log.debug("DecisionOrchestrator: owner {} verrouillé, cycle ignoré pour ce run.", owner);
                continue;
            }
            try {
                for (OpinionSignal signal : signals) {
                    ScenarioContext context = new ScenarioContext(owner, signal.symbol(), clock, List.of());
                    scenarioEngine.onMarketOpinion(signal, context);
                }
                processed++;
            } finally {
                refreshGuard.release(owner, clock.now());
            }
        }

        log.info("DecisionOrchestrator: cycle terminé — {} signal(aux) calculé(s), {} owner(s) traité(s), "
                        + "{} owner(s) ignoré(s) (verrou).",
                signals.size(), processed, skippedLocked);

        return new OrchestrationResult(signals.size(), activeUsers.size(), processed, skippedLocked, now);
    }

    /**
     * Combine TrendConfirmation + MovementQualification + OrderFlow + EtfFlow (modulateurs de
     * confiance) — décision 7 du prompt d'implémentation de cette étape. Concaténation des
     * StrategyKey des 4 fabriques, patron déjà documenté dans MarketOpinionParametersFactory.
     */
    private MarketOpinionParameters buildDefaultLocalOpinionParams() {
        List<StrategyKey> keys = new ArrayList<>();

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(
                Strategy.TREND_CONFIRMATION, // vérifier le nom exact de la constante/enum Strategy avant d'écrire
                new StrategyParametersFactory.TrendConfirmationParam(
                        TimeFrame.H1, 10, 20, 14, 14,
                        15.0, 25.0, 80.0, 20.0)
        ).getStrategies());

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithMovementQualification(
                Strategy.MOVEMENT_QUALIFICATION,
                StrategyParametersFactory.MovementQualificationParam.defaults(TimeFrame.H1, 14.0),
                credentialResolver.resolve(IndicatorType.OPEN_INTEREST)
        ).getStrategies());

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithOrderFlow(
                Strategy.ORDER_FLOW,
                StrategyParametersFactory.OrderFlowParam.defaults(TimeFrame.H1),
                credentialResolver.resolve(IndicatorType.LIQUIDATIONS)
        ).getStrategies());

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithEtfFlow(
                Strategy.ETF_FLOW_CONFIDENCE,
                StrategyParametersFactory.EtfFlowConfidenceParam.defaults(),
                credentialResolver.resolve(IndicatorType.ETF_FLOW)
        ).getStrategies());

        return MarketOpinionParameters.builder().strategies(keys).build();
    }
}
```
**Point d'attention à vérifier avant d'écrire ce code tel quel** : les constantes `Strategy.XXX` utilisées
ci-dessus (`TREND_CONFIRMATION`/`MOVEMENT_QUALIFICATION`/`ORDER_FLOW`/`ETF_FLOW_CONFIDENCE`) sont des
noms indicatifs — retrouver les valeurs exactes de l'enum/registre `Strategy` utilisé par
`MarketOpinionParametersFactory` (voir les tests `MarketOpinionParametersFactory*Test` pour l'usage
réel) avant de compiler, ne pas les deviner.

`OrchestrationResult` : record `(int signalsComputed, int activeUsersFound, int usersProcessed, int
usersSkippedLocked, Instant runAt)`.

**Mettre à jour le commentaire de `MarketOpinionParametersFactory.buildLocalOpinionParamWithEtfFlow`**
(cf. décision 7) : retirer la mention "n'est pas appelée automatiquement par défaut", remplacer par une
note indiquant qu'elle est désormais utilisée par défaut par `DecisionOrchestrator` (Palier 3, étape 7)
depuis le recadrage de calibration du 2026-08-10.

**Tests attendus** (`DecisionOrchestratorTest`) :
- Mock `TreeAnalysisFacade` : 3 appels LOCAL (un par actif de `TRACKED_ASSETS`) + 1 GLOBAL + 1 MACRO,
  chacun retournant un `OpinionSignal` distinct construit à la main.
- Mock `UserRepository` : 2 users actifs, 1 archivé, 1 désactivé → vérifier que seuls les 2 actifs sont
  traités (`OrchestrationResult.usersProcessed() == 2`), et que `scenarioEngine.onMarketOpinion(...)` est
  appelé exactement 5 fois par owner traité (5 signaux × 2 owners = 10 appels au total), jamais pour les
  2 exclus.
- Un des 2 owners actifs a déjà un verrou actif (`OwnerRefreshGuard` réel, `tryAcquire` appelé une
  première fois juste avant) → `usersSkippedLocked() == 1`, `onMarketOpinion` jamais appelé pour cet
  owner sur ce cycle.
- `ScenarioContext.symbol()` transmis à `onMarketOpinion` correspond bien à `signal.symbol()` pour
  chaque signal (vide pour GLOBAL/MACRO, renseigné pour chaque LOCAL) — capture les arguments réels de
  l'appel, ne se contente pas de vérifier le nombre d'appels.
- Test d'intégration légère (pas de mock sur `ScenarioEngine`/`DecisionEngine`, contexte Spring réel ou
  proche) : un signal LOCAL BULLISH répété sur 2 cycles pour le même owner doit, via la cascade
  existante (`onMarketOpinion` → `collectActionIntents` → `ScenarioEvent` → `DecisionEngine.onScenarioEvent`),
  aboutir à une `Decision` visible via `decisionEngine.getAllActiveDecisions()` — **sans que
  l'orchestrateur n'appelle jamais `DecisionEngine` directement** (vérifie la décision 10).

---

## Étape 5 — Job planifié désactivé + endpoint admin

**À faire**, même patron exact qu'étapes 4/6 :

`service/scheduler/DecisionOrchestratorJob.java` :
```java
@Component
@RequiredArgsConstructor
public class DecisionOrchestratorJob {
    private final DecisionOrchestrator orchestrator;

    @Scheduled(cron = "${tradeio.decision.orchestrator-cron:-}")
    public void runCycle() {
        orchestrator.runCycle();
    }
}
```

`controller/DecisionOrchestratorAdminController.java` :
```java
@RestController
@RequestMapping("/api/admin/decision")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DecisionOrchestratorAdminController {
    private final DecisionOrchestrator orchestrator;

    @PostMapping("/orchestrate")
    public ResponseEntity<OrchestrationResult> triggerCycle() {
        return ResponseEntity.ok(orchestrator.runCycle());
    }
}
```
(même préfixe `/api/admin/decision` que `DecisionScenarioSnapshotAdminController`/
`UserArchivalAdminController`, chemin distinct `/orchestrate` — vérifier au démarrage du contexte que
ça ne pose pas de conflit, comme déjà fait à l'étape 6.)

**Tests attendus** : test de contrôleur pour `POST /api/admin/decision/orchestrate` (`ROLE_ADMIN`, 200,
corps `OrchestrationResult` cohérent avec un `DecisionOrchestrator` mocké) — même patron que le test
existant pour `/archive` si un test dédié existe pour `UserArchivalAdminController` (vérifier avant
d'improviser un nouveau patron).

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline obtenue après l'étape 6),
détail de tout échec, et signaler explicitement :
- les noms exacts retrouvés pour les constantes `Strategy.XXX` (étape 4) — le prompt donne des noms
  indicatifs, pas vérifiés lettre pour lettre dans le registre réel ;
- si le fix de l'étape 1 (`GlobalMarketOpinion`/`MacroMarketOpinion`) a fait échouer un test existant qui
  dépendait de l'ancien comportement (symbole propagé par erreur) — à corriger dans ce lot si c'est le
  cas, pas à contourner ;
- si le test d'intégration légère de l'étape 4 (cascade Opinion → Scenario → Decision sans appel direct à
  `DecisionEngine`) a nécessité un contexte Spring plus lourd que prévu, ou a révélé un couplage caché ;
- tout autre écart pris par rapport à ce prompt.

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 7 → ✅) et débloquer l'étape 8
(calendrier macro, optionnelle) — à ce moment-là seulement, trancher avec Clem si elle est incluse ou
reportée, comme prévu dans la roadmap.
