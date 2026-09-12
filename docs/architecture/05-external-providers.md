# Providers de données externes

Vérifié le : 2026-09-12 (croisement `docs/suivi/etat-des-lieux-indicateurs-strategies-opinions.md` archivé + `configuration/EtfFlowCachingConfig` + `service/scheduler/EtfFlowHistorizationJob` + `service/tree/macro/MacroEventCalendarService`).

| Donnée | Provider actuel | Provider historique/écarté | Notes |
|---|---|---|---|
| Open Interest / Funding / Liquidations | Coinalyze | — | — |
| DXY (indice dollar) | Twelve Data | — | Seul indicateur encore sur Twelve Data (voir ligne suivante) |
| SP500 / NASDAQ | Yahoo Finance | Twelve Data | Migration effectuée — ne pas documenter SP500/NASDAQ comme venant de Twelve Data |
| Fear & Greed | CoinStats | — | Endpoint codé en dur (`/insights/fear-and-greed`) dans `CoinstatsFearAndGreedClient` — TODO connu, non bloquant |
| Plafond stablecoin | DefiLlama | — | — |
| ETF flow (BTC/ETH) | SoSoValue (`SosoValueEtfFlowClient`, via `CachingEtfFlowClient`, bean `@Primary`) | Farside (scraping HTML `jsoup`, `FarsideEtfFlowClient`) | Farside n'est plus la source du flux courant mais reste injecté **uniquement** dans `EtfFlowBackfillService` pour le backfill historique — 3ᵉ candidat `EtfFlowProvider` dans le contexte Spring, sans casser le `@Primary` posé sur SoSoValue |
| Calendrier macro | Finnhub + ForexFactory | — | Dédoublonné entre les deux sources (`MacroEventCalendarService`) |

## Historisation ETF flow

`EtfFlowHistorizationJob` (cron par défaut `0 0 7 * * *`, **actif** — contrairement à la plupart des autres jobs du projet) rafraîchit BTC + ETH une fois par jour via `CachingEtfFlowClient#refresh` (bypass volontaire du gate quotidien normalement appliqué à `fetch`), pour garantir une ligne `etf_flow_snapshot` par asset et par jour même si personne n'évalue `ETF_FLOW`/`CONFIDENCE_MODULATOR` ce jour-là. Isolation par asset : un échec sur BTC n'empêche pas la tentative ETH. Créneau (07h00 heure JVM) documenté dans le code comme **non vérifié empiriquement** (contrairement au calage veille média, basé sur des horaires observés).

**Bug corrigé (2026-08-10)** : mélange d'échelles entre Farside (millions USD) et SoSoValue (USD brut) dans `etf_flow_snapshot.total_net_inflow`. Corrigé par conversion systématique en USD brut à l'écriture (`EtfFlowBackfillService#toRawUsd`). Les lignes déjà en base avant le correctif restent à l'ancienne échelle tant que `POST /api/admin/etf-flow/backfill` n'est pas rejoué (upsert idempotent par `(asset, date)`).

## Calendrier macro : lu à la demande, pas branché dans le pipeline de décision

`MacroEventCalendarService` + `MacroCalendarMcpTools` (tools MCP `get_macro_calendar`, `check_macro_risk_window`) permettent de lire le calendrier macro et de savoir si une fenêtre à risque (FOMC/NFP/CPI...) est active. **Ce calendrier est également consommé automatiquement** via `MacroRiskWindowModulator`, branché dans `GlobalMarketOpinion`/`MacroMarketOpinion` — modulation de confidence (pas de suspension/exécution) en amont d'une `Decision`. Vérifier `MacroRiskWindowModulator` directement si un comportement précis de seuil est nécessaire (facteur de réduction observé dans le plan de test manuel : ~0.5, non calibré).

## Credentials manquantes = dégradation gracieuse, pas erreur

Convention répétée dans plusieurs jobs (`EtfFlowHistorizationJob`, `MediaWatchIngestionJob`) : si `IndicatorCredentialResolver`/`MediaCredentialResolver` ne résout aucune credential pour le provider requis, le job logue un warning et s'arrête proprement (pas d'exception, pas de crash). À garder en tête en diagnostic : "aucune donnée produite" peut simplement signifier "aucune clé API configurée pour ce provider", pas un bug.
