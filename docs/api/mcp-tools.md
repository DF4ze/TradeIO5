# Tools MCP exposés

Vérifié le : 2026-09-12 (`configuration/McpServerConfig`, `service/tree/api/mcp/TreeAnalysisMcpTools`, `service/dca/DcaMcpTools`, `service/tree/macro/MacroCalendarMcpTools`).

Serveur MCP transport HTTP/SSE (`spring-ai-starter-mcp-server-webmvc`, cohérent avec `spring-boot-starter-web` déjà présent). Trois groupes de tools, chacun enregistré via son propre `ToolCallbackProvider` dans `McpServerConfig` :

## TreeAnalysisMcpTools

| Tool | Description |
|---|---|
| `get_indicator` | Lit la valeur d'un indicateur pour un symbole |
| `evaluate_strategy` | Évalue une strategy |
| `get_opinion` | Calcule une `Opinion` pour un symbole+scope — même chemin que `GET /api/admin/decision/opinion` (`TreeAnalysisFacade` partagée) |

## DcaMcpTools

| Tool | Description |
|---|---|
| `calculate_dca` | Calcul DCA (`DcaCalculatorService`). Référence : `docs/etudes/etude-dca-tool-mcp.md` |

## MacroCalendarMcpTools

Patron commun aux deux tools : retour `String` JSON sérialisé à la main (jamais de `Map` directe), exceptions capturées via `toJsonOrError` (jamais d'exception qui remonte au client MCP), horloge injectée (`DomainClock`, jamais `Instant.now()` en dur).

| Tool | Description |
|---|---|
| `get_macro_calendar(fromDate, toDate, minImpact?)` | Liste les événements macro sur une fenêtre de dates (`toDate` inclusif jusqu'à 23:59:59.999 UTC), filtrable par impact minimal (`HOLIDAY`/`LOW`/`MEDIUM`/`HIGH`) |
| `check_macro_risk_window(windowHours, minImpact)` | Indique si une fenêtre à risque est active maintenant |

Sans credential résolue pour le calendrier, retourne une liste vide (pas une erreur). Erreurs de parsing de date → JSON `error:true`, jamais d'exception qui remonte (`MacroCalendarException` dédiée, distincte de `DcaException` malgré un patron de validation identique).

**Non branché automatiquement dans le pipeline de décision** au-delà de `MacroRiskWindowModulator` (modulation de confidence, voir [`architecture/05-external-providers.md`](../architecture/05-external-providers.md)) — ces deux tools sont avant tout un accès en lecture à la demande pour un agent externe.
