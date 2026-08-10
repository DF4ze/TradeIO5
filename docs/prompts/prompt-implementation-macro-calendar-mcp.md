# Prompt d'implémentation — Connecter le calendrier macro au MCP

Contexte pour la session qui exécute ce prompt : `MacroEventCalendarService`
(`src/main/java/fr/ses10doigts/tradeIO5/service/tree/macro/MacroEventCalendarService.java`) agrège
Finnhub + ForexFactory (dédoublonné, testé, `docs/suivi/etat-des-lieux-indicateurs-strategies-opinions.md`
§3) mais n'est exposé par **aucun** tool MCP — vérifié le 2026-08-10 en grep sur
`TreeAnalysisMcpTools`/`TreeAnalysisFacade`/`DcaMcpTools`, aucune référence. Objectif de ce lot :
rendre ce service appelable en MCP, sans toucher au reste (ne pas le brancher dans
`DecisionEngine`/`Scenario` — cette décision reste explicitement reportée, cf. javadoc de la classe
et `docs/suivi/point-avancement-2026-08-10.md` §3/§6.2 point 6).

## Pourquoi un nouveau tool dédié, pas `get_indicator`

`MacroEventCalendarService` ne fait pas partie du triptyque Indicator/Strategy/Opinion (pas
d'`IndicatorType`, pas de dépendance à un `MarketDataset`) — c'est un service de lecture autonome,
même famille que `DcaCalculatorService`. Suivre le même patron que
`service/dca/DcaMcpTools.java` (déjà enregistré séparément dans `McpServerConfig`) plutôt que de
forcer ce service dans le contrat `get_indicator`/`IndicatorParameters`, qui suppose un symbole et
un `MarketDataset` — aucun des deux n'a de sens ici (le calendrier macro n'est pas scopé par
symbole).

## Étape 1 — `MacroCalendarMcpTools`

Nouveau fichier `src/main/java/fr/ses10doigts/tradeIO5/service/tree/macro/MacroCalendarMcpTools.java`,
`@Component`, calqué sur `DcaMcpTools` (mêmes garde-fous : jamais laisser fuiter une exception hors
d'un `@Tool`, retour `String` JSON sérialisé à la main via `ObjectMapper`, jamais un `Map` renvoyé
directement — cf. javadoc `DcaMcpTools`, "avec spring-ai-starter-mcp-server-webmvc 1.0.9, un retour
Map&lt;String,Object&gt; est poussé dans structuredContent sans remplir correctement content").
Reprendre telles quelles les méthodes `toJson`/`toJsonOrError` de `DcaMcpTools` (dupliquées à
l'identique, même choix assumé que le reste du projet plutôt que d'extraire une classe utilitaire
partagée pour 2 lignes de méthode — cf. patron déjà en place entre `TreeAnalysisMcpTools` et
`DcaMcpTools`).

### Tool 1 (MVP de ce lot) : `get_macro_calendar`

```java
@Tool(
        name = "get_macro_calendar",
        description = "Liste les événements macro-économiques (FOMC, NFP, CPI, etc.) prévus dans une "
                + "fenêtre de dates, agrégés depuis Finnhub et ForexFactory (dédoublonnés). Impact "
                + "HIGH/MEDIUM/LOW/HOLIDAY par événement. Utile pour anticiper une fenêtre de risque "
                + "événementiel avant de prendre une décision de trading."
)
public String getMacroCalendar(
        @ToolParam(description = "Date de début (incluse), format ISO yyyy-MM-dd, interprétée en début de journée UTC") String fromDate,
        @ToolParam(description = "Date de fin (incluse), format ISO yyyy-MM-dd, interprétée en fin de journée UTC") String toDate,
        @ToolParam(description = "Filtre : n'inclure que les événements d'impact >= ce seuil (LOW/MEDIUM/HIGH). "
                + "Omis = tous les événements y compris HOLIDAY.", required = false) MacroEventImpact minImpact
) {
    return toJsonOrError("get_macro_calendar", () -> {
        Instant from = parseDate(fromDate, "fromDate").atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = parseDate(toDate, "toDate").plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);

        List<MacroEvent> events = macroEventCalendarService.getEvents(from, to);
        if (minImpact != null) {
            events = events.stream().filter(e -> impactRank(e.getImpact()) >= impactRank(minImpact)).toList();
        }
        return calendarResponse(events);
    });
}
```

Points d'attention :
1. `parseDate` : même patron que `DcaMcpTools#parseDate` (catch `DateTimeParseException`, message
   d'erreur explicite avec le nom du champ) — mais lever une exception générique ou une nouvelle
   `MacroCalendarException` plutôt que de réutiliser `DcaException` (sémantiquement incorrect ici).
   Décider si une exception dédiée vaut le coup pour 2 champs de date, ou si une
   `IllegalArgumentException` suffit (le tool capture tout via `toJsonOrError` de toute façon, la
   distinction n'a d'effet que sur `error.exception` dans la réponse JSON).
2. `toDate` interprété en **fin de journée UTC inclusive** (23:59:59.999), pas en début de journée —
   sinon un événement prévu le `toDate` à 14h UTC serait exclu silencieusement. Couvrir ce cas dans
   les tests (§ci-dessous).
3. `minImpact` : réutiliser `impactRank` de `MacroEventCalendarService` (actuellement `private
   static`, cf. ligne 156) — le passer en `static` (package-private ou public) pour être appelable
   depuis `MacroCalendarMcpTools`, plutôt que de dupliquer le mapping `HOLIDAY=0/LOW=1/MEDIUM=2/HIGH=3`.
4. Si ni Finnhub ni ForexFactory n'ont de credential résolue, `MacroEventCalendarService#getEvents`
   renvoie déjà une liste vide proprement (pas d'exception, cf. `fetchFrom`) — le tool doit donc
   renvoyer un JSON avec un tableau vide, pas une erreur. Vérifier ce comportement en test plutôt que
   de le supposer.

`calendarResponse` (méthode privée statique, même patron que `DcaMcpTools#dcaResponse`) sérialise
chaque `MacroEvent` : `title`, `country`, `dateTime` (`Instant#toString()`, cf. patron
`DcaOccurrence#getTimestamp().toString()`), `impact` (`.name()`), `source` (`.name()`), `forecast`,
`previous`, `actual` (les 3 restent des `String`, peuvent être `null` — ne pas les convertir).

### Tool 2 (optionnel dans ce lot, coût marginal très faible — décision à prendre en codant)

`MacroEventCalendarService#isWithinRiskWindow(Instant now, Duration window, MacroEventImpact
minImpact)` existe déjà et est trivial à exposer (`check_macro_risk_window`, retourne
`{"withinRiskWindow": true/false, "eventCount": N}` sur la fenêtre `[now-window, now+window]`).
Utile pour un futur usage "gating" (suspendre une décision automatique avant un FOMC) sans attendre
que le chantier scheduler (`docs/suivi/point-avancement-2026-08-10.md` §6.2 point 7) soit repris.
**Ne pas coder ce 2e tool sans trancher d'abord** : est-ce que `get_macro_calendar` seul suffit pour
ce lot (un consommateur peut recalculer ce booléen lui-même à partir de la liste d'événements), ou
le gain d'un tool dédié justifie la surface MCP supplémentaire ? Si la réponse est oui, même patron
que le tool 1 :

```java
@Tool(
        name = "check_macro_risk_window",
        description = "Indique si un événement macro d'impact >= minImpact tombe dans une fenêtre de "
                + "N heures autour de maintenant (now-window, now+window). Utile pour éviter de décider "
                + "juste avant/après une annonce à fort impact (FOMC, NFP, CPI)."
)
public String checkMacroRiskWindow(
        @ToolParam(description = "Demi-largeur de la fenêtre en heures autour de maintenant") double windowHours,
        @ToolParam(description = "Impact minimal déclenchant la fenêtre de risque : LOW, MEDIUM ou HIGH") MacroEventImpact minImpact
) {
    return toJsonOrError("check_macro_risk_window", () -> {
        Duration window = Duration.ofMinutes(Math.round(windowHours * 60));
        Instant now = clock.now(); // injecter DomainClock, ne jamais utiliser Instant.now() en dur (cf. patron du reste du projet)
        boolean within = macroEventCalendarService.isWithinRiskWindow(now, window, minImpact);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("withinRiskWindow", within);
        response.put("windowHours", windowHours);
        response.put("minImpact", minImpact.name());
        return response;
    });
}
```

Si ce 2e tool est codé, injecter `DomainClock` dans `MacroCalendarMcpTools` (même patron que le
reste du projet — jamais `Instant.now()` en dur, cf. `CachingEtfFlowClient`/`EtfFlowHistorizationJob`
pour des exemples récents de ce principe appliqué).

## Étape 2 — Enregistrement dans `McpServerConfig`

Ajouter le bean, même patron que `dcaToolCallbackProvider` :

```java
@Bean
public ToolCallbackProvider macroCalendarToolCallbackProvider(MacroCalendarMcpTools macroCalendarMcpTools) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(macroCalendarMcpTools)
            .build();
}
```

Mettre à jour le javadoc de classe de `McpServerConfig` (liste à 2 items aujourd'hui) pour ajouter
la 3e entrée.

## Étape 3 — Tests

Nouveau `MacroCalendarMcpToolsTest` (mock `MacroEventCalendarService`), même esprit que les tests
existants de `DcaMcpTools` s'ils existent (vérifier `src/test/.../service/dca/` pour le patron exact
de mock/assertions JSON) :

- `fromDate`/`toDate` valides, `minImpact` omis → tous les événements renvoyés par le mock
  (`getEvents`) apparaissent dans le JSON, dans l'ordre.
- `minImpact=MEDIUM` → un événement `LOW` retourné par le mock est filtré hors de la réponse, un
  événement `HIGH` reste.
- `toDate` inclusif : un événement daté du dernier jour de la plage à 23h UTC est bien inclus (pas
  tronqué par un `to` calculé en début de journée par erreur).
- `fromDate`/`toDate` invalides (format non `yyyy-MM-dd`) → réponse JSON avec `error:true`, pas
  d'exception qui remonte.
- Aucune credential résolue (mock `getEvents` renvoie `List.of()`, comportement réel de
  `MacroEventCalendarService` documenté dans sa javadoc `fetchFrom`) → JSON avec tableau
  d'événements vide, `error` absent (ce n'est pas un cas d'échec).
- Si le tool 2 est codé : `isWithinRiskWindow` mocké `true`/`false`, vérifier que le JSON reflète
  correctement chaque cas, et qu'une fenêtre en heures fractionnaires (`windowHours=1.5`) produit
  bien un `Duration` de 90 minutes passé au service (capturer l'argument, pas juste vérifier l'appel).

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite complète via `test:tradeio-5`
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`, cf. les lots précédents de ce projet — ne
pas lancer `mvn` en sandbox local, pas de réseau/Maven disponible). Rapporter le nombre de tests
exécutés et le détail de tout échec. Mettre à jour
`docs/suivi/etat-des-lieux-indicateurs-strategies-opinions.md` §3 ("Calendrier macro") pour retirer
la mention "aucun point d'entrée ne le couvre" une fois ce lot fermé, et ajouter une entrée dans
`docs/suivi/point-avancement-2026-08-10.md` ou un nouveau point de suivi daté.
