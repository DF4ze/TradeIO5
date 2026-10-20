# Prompt — Modulation, étape 1 : socle (paramètres admin + réglages utilisateur)

À lire d'abord : [`../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md`](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md) §2, §4, §7 ; [`prompt-roadmap-modulation-bande-puissance.md`](prompt-roadmap-modulation-bande-puissance.md) ; `docs/CODING_RULES.md` ; [`../operations/flyway.md`](../operations/flyway.md). Style de Clem : `isEmpty()`/`getFirst()`, constantes métier mutualisées, pas de variable locale redondante, `assertTrue`/`assertFalse` directs, `DomainClock` (jamais `Instant.now()`), JDK 21.

**Périmètre : socle uniquement.** Ne PAS toucher à `LiveSizing`, au signal ni au plan d'ordres : aucun effet sur les montants à cette étape.

## 1. Constantes mutualisées
Classe `ModulationDefaults` (défauts + **bornes absolues**, en `BigDecimal`, sur le modèle d'`ExecutionDefaults`) :

| Paramètre | Défaut | Bornes absolues |
|---|---|---|
| `regulationMarginPct` | 25 | 5–50 |
| `sellBoostMax` | 2 | 1–4 |
| `biasMax` | 0,5 | 0–1 |
| `powerMin` / `powerMax` | 0,25 / 2 | 0–3 |
| `minBandWidthPts` | 10 | 5–50 |
| `modulationProductMax` | 2,5 | 1–5 |
| `rebalanceDailyCapPct` | 2 | 0–10 (0 = désactivé) |
| `autonomyDays` | 90 | 7–365 |

## 2. Paramètres admin globaux
- Entité singleton `RainbowModulationControl` (table `rainbow_modulation_control`, id 1, colonnes ci-dessus + `updatedAt`, `updatedBy`), modelée sur `ExecutionControl` ; repository ; `RainbowModulationControlService` : `current()` relit à chaque appel **sans cache**, ligne absente ⇒ défauts en constantes ; `update(...)` valide chaque valeur dans ses bornes absolues, impose `powerMin ≤ 1 ≤ powerMax`, `IllegalArgumentException` sinon.
- Initializer `@Order(71)` : crée la ligne si absente, **ne modifie jamais** une ligne existante (règle des défauts, `flyway.md`).
- `AdminModulationController` : `GET`/`PUT /api/admin/rainbow-modulation`, `ROLE_ADMIN` (même sécurité que `ExecutionAdminController`). Le `GET` renvoie aussi, par paramètre, le défaut et les bornes absolues (pour l'écran). Le `PUT` écrit une ligne dans `AdminActionLog` (nouvelle valeur d'enum `AdminAction.MODULATION`, `target = "rainbow-modulation"`, payload JSON compact avant/après, jamais de secret).
- Piège : `admin_action_log.action` est un `ENUM` MySQL natif, que `ddl-auto=update` n'étend pas — documenter l'`ALTER` pour base existante dans `flyway.md`, comme pour les valeurs précédentes.

## 3. Réglages utilisateur
- `UserTradingSettings` : ajouter `exposureMin`, `exposureMax` (en %), `power`, `bias`. **Colonnes nullables (`Double`)** : sur base existante `ddl-auto=update` initialiserait des primitives à 0 et casserait les lignes en place ; `null` ⇒ valeurs neutres `0`, `100`, `1`, `0`.
- `UserTradingSettingsService` : `getModulationSettings(user)` (valeurs stockées ou neutres), `setModulationSettings(user, ...)` validé contre `RainbowModulationControlService.current()` : `0 ≤ min < max ≤ 100`, `max − min ≥ minBandWidthPts`, `power ∈ [powerMin, powerMax]`, `|bias| ≤ biasMax`. Création de ligne : `riskCursor` à `DEFAULT_RISK_CURSOR` ; `riskCursor` jamais modifié par ces méthodes. Prévoir `effectiveSettings(user)` qui **clampe** les valeurs stockées aux bornes admin courantes (consommé à l'étape 2, l'admin pouvant resserrer les bornes après coup).
- `UserModulationSettingsController` : `GET`/`PUT /api/user/modulation-settings` (corps JSON), `isAuthenticated`, scopé à l'utilisateur connecté via `IAuthenticationFacade`. Le `GET` renvoie les valeurs stockées et les limites admin en vigueur.

## 4. Tests
Défauts dans leurs bornes ; validation de chaque paramètre (limites incluses/exclues, `powerMin ≤ 1 ≤ powerMax`) ; ligne absente ⇒ défauts ; initializer idempotent et sans écrasement ; sécurité (endpoint admin refusé à un non-admin) ; ligne `MODULATION` écrite au journal ; réglages utilisateur : valeurs neutres sans ligne ou colonnes nulles, erreurs de validation, `effectiveSettings` clampé, `riskCursor` intact. Calquer les tests d'`ExecutionControl*` et de `UserTradingSettings*`.

## 5. Documentation et schéma
- `architecture/08-rainbow-live.md` : section « Modulation : paramètres admin et réglages utilisateur », en précisant que **rien n'est encore appliqué aux montants** ; `api/rest-endpoints.md` (2 endpoints) ; `glossary.md` (bande, puissance, biais) ; `operations/flyway.md` (nouvelle table, colonnes de `user_trading_settings`, `ALTER` de l'`ENUM`).
- Régénérer `V1__init.sql` : `mvn test -Dtest=FlywaySchemaTest -Dflyway.generate=true`, puis `-Dflyway.check=true`.
- Une fois l'étape réellement terminée : mettre à jour `prompt-roadmap-modulation-bande-puissance.md` (statut, classes réelles) puis **point avec Clem** avant l'étape 2.
