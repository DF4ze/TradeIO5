# Prompt — Modulation, étape 2 : moteur (bande, puissance, biais, journal)

À lire d'abord : [`../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md`](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md) §2, §4, §5 ; [`prompt-roadmap-modulation-bande-puissance.md`](prompt-roadmap-modulation-bande-puissance.md) ; [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md) (dimensionnement par lien) ; `docs/CODING_RULES.md`. Style de Clem : `isEmpty()`/`getFirst()`, constantes mutualisées, pas de variable redondante, `assertTrue`/`assertFalse` directs, `DomainClock`, JDK 21.

Pré-requis (étape 1, faite) : `RainbowModulationControlService#current()` (paramètres admin), `UserTradingSettingsService#effectiveSettings(user)` (bande, puissance, biais clampés), `ModulationDefaults`.

**Périmètre** : les montants des liens sont modulés dans `LiveSizing`. Le signal (`ReferenceSizer`, `LiveDemand`, `RainbowTrendLiveService`) et le plan d'ordres restent inchangés. Pas de `REBALANCE` (étape 3), pas d'écran (étape 4), `effetRisque` (§3) vaut 1.

## 1. Calculateur pur `ExposureModulator` (package `service/modulation`)
Sans accès base ni horloge, testé unitairement. Entrées : exposition (%), bande `[min, max]`, `m` (% de la largeur), `G`, sens (achat/vente). Sorties : facteur de bande, `l` haut/bas, plafond dur.
- `marge = m/100 × (max − min)`. Marge haute active **seulement si `max < 100`**, marge basse **seulement si `min > 0`** : la bande neutre `[0, 100]` ne module jamais rien.
- Haute : `s = clamp((exp − (max − marge)) / marge, 0, 1)`, `l = s²(3 − 2s)` ; basse : `s = clamp(((min + marge) − exp) / marge, 0, 1)`.
- Achat : `(1 − l_haute) × (1 + (G − 1)·l_basse)` ; vente : `(1 + (G − 1)·l_haute) × (1 − l_basse)`.
- Plafond dur en USD : achat `≤ max·V − P` (seulement si `max < 100`), vente `≤ P − min·V` (seulement si `min > 0`), plancher 0.

## 2. État d'exposition par wallet `ExposureLedger`
Calqué sur `CashLedger` (une instance par passe et par wallet, créée à la première lecture `OK`) : `P` = Σ quantité × prix des actifs du périmètre, `V` = cash USDC + `P` (constant pendant la passe), `exposure()` = `P / V × 100`.
- Après chaque action acceptée : achat ⇒ `P += montant`, vente ⇒ `P −= quantité × clôture` (le lien suivant voit l'exposition mise à jour, comme le cash réservé).
- **Rejeu du même jour** : l'action du snapshot d'origine met à jour le ledger (comme `forceReserve` pour le cash).
- **Valorisation** : clôture D1 du jour des séries déjà chargées par la passe (`AssetSeries`) ; pour un actif détenu sans série dans la passe, dernière clôture D1 en base (cache DB), **aucun appel exchange supplémentaire**. Prix introuvable pour un actif détenu, ou `V = 0` ⇒ exposition **inconnue** : pas de modulation de bande pour ce wallet, puissance et biais restent appliqués, statut tracé au journal. Jamais un 0 silencieux.

## 3. Branchement dans `LiveSizing`
Passer à `LiveSizing.of(...)` un objet `LiveModulation` (réglages effectifs du user, paramètres admin, `ExposureLedger`, clôtures) ; `runLink` lit les réglages une fois par passe et par user. `tradablePosition()` reste inchangée.
- Achat : `signal × P·(1 + β)` → `× facteur de bande` → plafond dur → `CashLedger#tryReserve` (inchangé). Vente : `quantité voulue × P·(1 − β)` → `× facteur de bande` → `min(…, tradable, plafond dur)`.
- Le produit `P_côté × facteur de bande` est plafonné à `modulationProductMax` (pas de plancher autre que 0).
- `actionAmountUsdc` / `actionQuantity` du bloc portent le montant **final** (c'est ce que lit le plan d'ordres). Action ramenée sous `EPSILON` alors que le signal en voulait une ⇒ `NONE` + `LiveBlockReason.BAND_LIMIT` (nouvelle valeur ; `ENUM` MySQL natif, documenter l'`ALTER` comme à l'étape 1).
- `BELOW_MIN` et carry : inchangés (gérés au plan).

## 4. Journal de modulation
Colonnes nullables dans `RainbowLiveLiveBlock` (typées, nullables car les blocs existants n'en ont pas) : montant ou quantité du signal avant modulation, `P`, `β`, facteur de côté, bande `min`/`max`, exposition avant et après, facteur de bande, facteur produit après plafond, plafond dur appliqué (booléen), statut d'exposition (`OK`/`UNKNOWN`), paramètres admin utilisés (`m`, `G`, `modulationProductMax`). Exposer ces champs dans le DTO de lecture du run de lien (`RainbowLiveBindingDtos`) pour l'écran de l'étape 4. **Ne pas** les faire entrer dans `OrderPlanner.inputsHash` (il ne lit que statut, action, montant, quantité, carry, cash) : le rejeu 23:55 doit rester `unchanged`, à couvrir par un test.

## 5. Tests
- Calculateur : exemple de la spec (bande 30–90, `m` 25 %, exposition 80 % ⇒ achat ×0,74, vente ×1,26), centre sans modulation, 89,99 % pour `max` 90 ⇒ achat ≈ 0, symétrie basse, bande `[0, 100]` neutre, `min = 0` seul, `max = 100` seul, plafond dur (un ordre ne franchit jamais une borne), `G` variable.
- `LiveSizing` : puissance et biais (`β = ±0,5`), plafond du produit, `BAND_LIMIT`, lecture `UNAVAILABLE` inchangée, vente toujours plafonnée à la position tradable.
- `ExposureLedger` : mise à jour entre deux liens du même wallet, rejeu du même jour, prix inconnu ⇒ modulation de bande inactive et journal `UNKNOWN`.
- Passe complète : un user sans réglages (valeurs neutres) obtient exactement les montants d'avant l'étape 2 (non-régression).

## 6. Décisions techniques prises (à contester au point)
Exposition par wallet ; `P` ne tient pas compte de `bagPercent` (position réelle entière) ; frais ignorés dans `V` ; marges et plafond calculés sur l'exposition **avant** l'ordre du lien courant, le plafond dur garantissant qu'il ne franchit pas la borne ; prix manquant ⇒ pas de modulation de bande plutôt que blocage.

## 7. Documentation
`architecture/08-rainbow-live.md` (section de dimensionnement : chaîne réelle, `ExposureLedger`, journal, `BAND_LIMIT`), `api/rest-endpoints.md` (champs du DTO), `glossary.md`, `operations/flyway.md` (colonnes, `ALTER` de l'`ENUM`), `V1__init.sql` (`mvn test -Dtest=FlywaySchemaTest -Dflyway.generate=true` puis `-Dflyway.check=true`). Une fois l'étape réellement terminée : mettre à jour la roadmap puis **point avec Clem** avant l'étape 3.
