---
statut: courant
vérifié_le: 2026-11-07
modifié_le: 2026-11-10
empreinte: 072d17c9b5
---
# Rainbow live — Modulation (admin + utilisateur)

Suite de [`08-rainbow-live.md`](08-rainbow-live.md) (Rainbow DCA ATR live — Affectations, signal quotidien, liens (bindings), API REST).

## Modulation : paramètres admin et réglages utilisateur

**Principe : le Rainbow reste seul à acheter et vendre ; la modulation ne fait que changer les quantités qu'il demande** (aucun ordre de rééquilibrage). Signal (`LiveDemand`) et plan d'ordres inchangés : seul `LiveSizing` module, par lien. Roadmap : ../prompts/prompt-roadmap-modulation-bande-puissance.md *(supprimé)*.

- **Constantes** : `service/modulation/ModulationDefaults` (défaut + bornes absolues incluses de chaque paramètre admin, valeurs neutres utilisateur 0 / 100 / 1 / 0) ; la base ne relève jamais une borne absolue.
- **Paramètres admin globaux** : singleton `RainbowModulationControl` (`rainbow_modulation_control`, id 1, `updatedAt`, `updatedBy`) : `regulationMarginPct` (25, 5–50), `sellBoostMax` (2, 1–4), `biasMax` (0,5, 0–1), `powerMin`/`powerMax` (0,25/2, 0–3), `minBandWidthPts` (10, 5–50), `modulationProductMax` (2,5, 1–5), `autonomyDays` (90, 7–365). `RainbowModulationControlService.current()` relit la base à chaque appel (sans cache), ligne absente ⇒ défauts ; `update(Params, by)` valide chaque borne et impose `powerMin ≤ 1 ≤ powerMax` (`IllegalArgumentException`, rien écrit). `RainbowModulationControlInitializer` (`@Order(71)`) crée la ligne si absente, ne modifie jamais une ligne existante.
- **Journal** : `AdminModulationService` écrit une ligne `AdminAction.MODULATION` (`target = rainbow-modulation`, payload `{before, after}`; `REFUSED` si invalide).
- **Réglages utilisateur** : `UserTradingSettings.exposureMin|exposureMax|power|bias` (`Double` **nullables** : null ⇒ neutre 0 / 100 / 1 / 0). `UserTradingSettingsService` : `getModulationSettings` (stocké ou neutre), `setModulationSettings` (validé contre `current()` : `0 ≤ min < max ≤ 100`, largeur ≥ `minBandWidthPts`, puissance dans `[powerMin, powerMax]`, `|biais| ≤ biasMax` ; ne touche jamais `riskCursor`, qui vaut `DEFAULT_RISK_CURSOR` à la création de ligne), `effectiveSettings` (valeurs stockées **clampées** aux bornes admin courantes ; bande trop étroite élargie autour de son centre, décalée pour rester dans [0, 100] ; consommé à l'étape 2).
- **API** : voir [`../api/rest-endpoints.md`](../api/rest-endpoints.md) (`/api/admin/rainbow-modulation`, `/api/user/modulation-settings`).

### Moteur de modulation (`LiveSizing`, par lien)
- **Marge de régulation réglable par l'utilisateur** : `UserTradingSettings.regulationMarginPct` (% de la largeur de bande, [5, 50], nul = défaut admin `regulationMarginPct` ; `effectiveSettings` la clampe) ; réglée dans Réglages → Modulation ; `LiveModulation` l'utilise à la place du défaut admin.
- **Calcul pur** `service/modulation/ExposureModulator` : marges de régulation `m % × (max − min)` à l'intérieur de chaque borne, `s` linéaire clampé puis `l = s²(3 − 2s)` ; achat × `(1 − l_haute)(1 + (G − 1)·l_basse)`, vente × `(1 + (G − 1)·l_haute)(1 − l_basse)` (`G = sellBoostMax`). Marge haute seulement si `max < 100`, basse seulement si `min > 0` : la bande neutre [0, 100] ne module rien. Plafond dur en USD : achat ≤ `max·V − P`, vente ≤ `P − min·V` (plancher 0).
- **Chaîne** (`LiveModulation`) : quantité du signal × `puissance·(1 ± biais)` × facteur de bande ; le produit par côté est plafonné à `modulationProductMax` ; puis plafond dur, puis les plafonds existants (cash du `CashLedger`, position tradable). Montant final = ce que lit le plan d'ordres. `effetRisque` (macro) = 1, pas encore branché.
- **Exposition** (`ExposureLedger`/`ExposureBook`, un par wallet et par passe) : `P` = Σ quantité × clôture D1 de la passe (demandes des Affectations, aucun appel exchange en plus), `V = P + cash USDC` constant ; achat accepté ⇒ `P +=`, vente ⇒ `P −=` pour le lien suivant ; rejeu du même jour : l'action d'origine met le ledger à jour. Prix manquant pour un actif détenu ou `V = 0` ⇒ exposition **inconnue** : pas de modulation de bande (puissance et biais restent appliqués), `exposureStatus = UNKNOWN` au journal.
- **`BAND_LIMIT`** (`LiveBlockReason`) : le signal voulait une action mais la modulation l'a ramenée à 0 ⇒ `NONE` + `BAND_LIMIT`.
- **Journal** : `RainbowLiveLiveBlock.modulation` (JSON, colonne `live_modulation`) : montants du signal, puissance, biais, facteurs de côté, bande, exposition avant/après, `l` haute/basse, facteurs de bande et produit, plafonds atteints, paramètres admin utilisés, montant final. Exposé en `modulation` dans le snapshot de `GET /api/rainbow-live/live-wallet`. Hors de `OrderPlanner.inputsHash` (qui ne lit que statut, action, montant, quantité, carry, cash) : le rejeu du même jour reste `unchanged`.
- Réglages neutres (aucune ligne ou colonnes nulles) ⇒ montants identiques à ceux d'avant la modulation (test de non-régression).

### Montant de base du lien

`RainbowLiveBinding.baseAmount` (`Double` nullable, choisi par l'utilisateur ; nul ⇒ montant du preset Rainbow joué). `LiveDemand` porte le montant de base avec lequel le signal a été dimensionné ; `withLinkBaseAmount` met l'**achat** à l'échelle `baseAmount lien / baseAmount du preset` (la vente, fraction de la position, n'en dépend pas) avant `LiveSizing`. Validation : > 0 et fini (`RainbowLiveBindingService`, `resetBaseAmount` remet à nul). Tests : `LiveDemandTest`, `RainbowLiveBindingControllerTest#linkBaseAmount`.

### Onboarding (`RainbowLiveOnboardingService`, `/api/rainbow-live/onboarding`)

- **Liaison** (`POST /link`) : par actif de `RainbowLiveDefaultPresets.ASSETS`, lien existant ⇒ inchangé ; sinon `BindingCheck` ; `OK` / `TRADABLE_VIA_INTERMEDIARY` ⇒ lien **inactif** sur l'Affectation de l'actif (`bagPercent`/`priority` par défaut) ; tout autre statut (non tradable sans fiat, clé rejetée, soldes illisibles…) ⇒ non lié, statut et raison retournés (rejouable).
- **Devis par exchange** (`GET /path-quotes?asset=&amount=`) : `BindingPathQuoteService#quoteWallet` sur chaque wallet actif du user, sans binding (page `/app/onboarding`, étape « Chemin d'achat » : l'utilisateur choisit l'exchange d'après le Fee Test). Un wallet en échec est retourné avec `error`, sans bloquer les autres. L'utilisateur choisit un seul couple (exchange, route) ; si la route n'est pas la moins chère, elle est mémorisée sur le lien (`RainbowLiveBinding.preferredRoute`, colonne `preferred_route`) et imposée au devis, au plan d'ordres et à l'exécution.
- **Proposition** (`GET /proposals?days=`) : `baseAmount = cash du wallet / nb de liens du wallet / (jours × multiplicateur moyen)` ; `jours` = durée choisie par l'utilisateur (champ « Étaler le cash sur », jours ou mois de 30 j), sinon `autonomyDays` admin, borné par les limites absolues de ce paramètre ; multiplicateur moyen = moyenne de `multX2`, `multX1`, `multX0_5` du preset Rainbow DOWN de l'Affectation, arrondi par défaut au centime. Cash lu par `RealPortfolioSource` (illisible ou nul ⇒ pas de proposition). Le montant est coté via `BindingPathQuoteService` : `BELOW_MIN`, niveau Fee Test et avertissements remontés ; devis indisponible ⇒ proposition gardée avec avertissement. Bande proposée : neutre (`ModulationDefaults`), aucun profil utilisateur n'existe.
- **Écran** : page `/app/onboarding` (cf. [`11-front-app.md`](11-front-app.md)). Le déclenchement est manuel : la sauvegarde d'une clé TRADE ne lie rien toute seule.
- Tests : `RainbowLiveOnboardingServiceTest`.

### Écrans de modulation

- **Admin, onglet « Modulation »** (`fragments/admin/modulation.html`, `assets/js/admin/modulation.js`) : un champ numérique par paramètre global (défaut et bornes absolues affichés), « Enregistrer » et « Remettre les défauts » via `GET/PUT /api/admin/rainbow-modulation` ; refus serveur affiché dans le bandeau d'alerte. Chaque champ affiche son impact concret (effet, si on augmente, si on diminue, exemple chiffré, défaut et bornes) et un bandeau rappelle que ces réglages s'appliquent immédiatement à tous les utilisateurs, ordres réels compris (`autonomyDays` n'agit que sur la proposition de montant de base).
- **Utilisateur** : écran Réglages stratégiques (`/app/settings/strategy`, `page-settings-strategy.js`) : jauge 0–100 % (bande, marges de régulation illustrées à 25 % de la largeur, repère d'exposition), curseurs mini/maxi, puissance, biais (bornes issues de `limits`), « Neutre » ; via `GET/PUT /api/user/modulation-settings`. L'exposition affichée est lue dans le dernier journal de modulation des liens (`exposureAfter`, sinon `exposureBefore`), inconnue sinon ; message si la bande est plus étroite que le minimum (elle sera élargie).
- **« Pourquoi ce montant ? »** : détail repliable de la fiche actif (`/app/assets/{symbole}`), construit depuis `snapshot.modulation` (signal, puissance/biais, bande, plafonds, montant final, exposition après).

## Profils de départ et historique de la modulation

- `StartingProfile` : `PRUDENT` 20–50, `BALANCED` 30–70, `DYNAMIC` 40–90, `FULL` 0–100, `CUSTOM` (non sélectionnable : état atteint par modification manuelle).
- `UserModulationService` (seul point d'écriture de la modulation côté utilisateur) : `update(...)` valide via `UserTradingSettingsService`, et si les valeurs changent réellement bascule le profil sur `CUSTOM` et journalise (`MANUAL`) ; `applyProfile(profile)` ne remplace que la bande (puissance, biais, marge conservés), refuse `null`/`CUSTOM`, journalise (`PROFILE`) si la bande change ; `history(user)` = 50 derniers changements.
- Historique : table `user_modulation_history` (user, `occurred_at`, `source` PROFILE|MANUAL, profil avant/après, valeurs avant/après `before_*`/`after_*` : exposition min/max, puissance, biais, marge ; `ON DELETE CASCADE`). Lecture : `GET /api/user/modulation-settings/history`.
