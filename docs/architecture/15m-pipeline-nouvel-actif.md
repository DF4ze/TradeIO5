---
statut: courant
sujet: Pipeline d'accueil d'un nouvel actif (D1b) — proposition automatique (preset BTC + taux par profil), historique provider / Binance bench, drapeaux, persistance, alerte Docteur, validation admin
vérifié_le: 2026-11-24
modifié_le: 2026-11-23
empreinte: 51e44458f4
---
# Pipeline nouvel actif (D1b) — proposition, alerte, validation admin

Un lien créé sur un actif **sans taux calibré** (hors BTC / ETH, `ModulationDefaults.CALIBRATED_RATE_ASSETS`) et sans proposition déclenche le calcul d'une **proposition** : preset de départ + taux de base par profil + drapeaux. **Rien n'est appliqué sans action admin** : le lien tourne déjà sur le repli BTC « non calibré » ([`08d-rainbow-modulation.md`](08d-rainbow-modulation.md)). Alerte et validation : section « Alerte et validation admin ». Décisions : [`../decisions/full-autonome-v1.md`](../decisions/full-autonome-v1.md), [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Nouveaux actifs »).

## Déclenchement
`RainbowLiveBindingService.create` publie `NewAssetLinkedEvent` ; `NewAssetProposalService.onLinked` (`@TransactionalEventListener(AFTER_COMMIT)`) met le calcul en file et rend la main. Actifs autorisés aujourd'hui : BTC, ETH, PAXG (`RainbowLiveDefaultPresets.ASSETS`) : seul PAXG déclenche ; tout actif ajouté plus tard suivra sans autre branchement. Activation : `tradeio.new-asset-proposal.enabled=true` (**défaut : désactivé**).
- Exécution : fil dédié `new-asset-proposal` (daemon, priorité minimale, un calcul à la fois) + fil de calcul avec délai maximal (30 min) ; jamais sur le chemin du cron live. Aucune exception ne remonte : un échec devient une proposition dégradée (`CALAGE_INTERROMPU`).
- Une proposition existante pour l'actif (quel que soit son statut) empêche tout nouveau calcul.

## Historique (`NewAssetHistoryResolver`)
1. Provider du lien (`DailyDataLoader`, cache DB) s'il couvre ≥ 48 mois (`providerSufficientMonths`).
2. Sinon complément Binance D1 en USDT (`BinanceBenchHistoryFetcher`, API publique, pages de 1 000, pause 1 s), retenu s'il est plus long, stocké dans le stockage bench ([`15l-transfert-presets.md`](15l-transfert-presets.md), `BenchCandleStore.save`, étiquette `BINANCE/USDT`, dossier `tradeio.new-asset-proposal.bench-dir`). Jamais lu par le live ni l'exécution.
3. Binance injoignable : on garde le provider (éventuellement rien) ; sous 120 bougies : `HISTORIQUE_INSUFFISANT`, ni taux ni exception.

## Calcul (`NewAssetProposalCalculator`, pur)
- **Preset** : preset BTC (Bull / Bear de l'Affectation Système BTC) joué **tel que le live l'applique** (modulation ATH comprise, comme le calage C3), avec la Trend par défaut du live (`TransferStudy.inputs(…, pureTiming=false)`). Rien n'est réglé.
- **Taux par profil** : méthode C3 **allégée** (`ExposureCalibrationStudy.lite`) : pire drawdown sur fenêtres glissantes de 24 mois (pas 30 j) + historique complet, plafond / k (k = `baseRateMarginK` admin, bande neutre), taux = multiplicateur × base du ×1 / capital. Sans calage ancré ni test de marge (k fourni, jamais mesuré ici). Niveaux identiques à ceux de C4 à k égal (test).
- **Coût** : estimé avant lancement (niveaux × (fenêtres + 1) × durée d'un rejeu) ; > 10 min (série) ⇒ calage non lancé, drapeau `CALAGE_COUT_ELEVE`. BTC rejoué : 1 312 rejeux, 8,8 s (pool de 2), estimation 0,55 min.

## Drapeaux (jamais bloquants)
`PROVISOIRE` (< 24 mois), `MARCHE_TROP_COURT` (aucune fenêtre de 24 mois : pire cas sur l'historique complet seul), `HISTORIQUE_INSUFFISANT`, `NON_TRADABLE` (`BindingCheck` ; avertissement visible), `CALAGE_COUT_ELEVE`, `CALAGE_INTERROMPU`. Détail : mois d'historique, blocs de 24 mois rejouables, nombre de switchs Bull / Bear.

## Persistance
Table `new_asset_proposal` (`V7__new_asset_proposal.sql`, idempotente) : statut `PROPOSE` / `VALIDE` / `REFUSE` (le calcul n'écrit que `PROPOSE`), source du preset, étiquette source et empreinte des bougies, détail et drapeaux, k, taux (JSON), coût, avertissement, demandeur, horodatages de décision.

## Alerte et validation admin (`NewAssetProposalAdminService`)
- **Alerte** : à l'enregistrement d'une proposition `PROPOSE`, alarme Docteur WARN `NEW_ASSET_PROPOSAL:<actif>` (« Actif à valider », famille DATA, impact QUALITY) levée **explicitement** (`AlarmService.raise`, hors passe du Docteur : aucun contrôle ne la réconcilie). Une seule fois par proposition (alarme déjà ouverte ⇒ aucun effet, pas de doublon ni de désacquittement), acquittable comme toute alarme ; le message porte le lien `/admin#modulation` (ouvre l'onglet Modulation). Clôturée par Valider et Refuser. Une proposition ne peut pas être recalculée (une par actif), donc pas de nouvelle alarme.
- **Écran** : onglet « Modulation » de `/admin`, section « Actifs à valider » (`modulation.js`) : par proposition, preset de départ, taux par profil (plafond, multiplicateur, taux, statut), drapeaux, mois d'historique, blocs, switchs, source et empreinte des bougies, marge k, avertissement « non tradable » ; une ligne annonce **avant validation** ce qui arrivera au preset ; boutons Valider / Refuser (confirmation).
- **Valider** (transaction unique) : écrit la **ligne de taux dédiée** de l'actif (`asset_base_rate`, `V8__asset_base_rate.sql`, entité `AssetBaseRate` : 4 taux, proposition source, validateur, date). Résolution (`AssetBaseRateService`) : ligne de l'actif d'abord, puis table admin (BTC / ETH propres), sinon repli BTC « non calibré » ; BTC / ETH ne lisent jamais la ligne dédiée. Taux contrôlés comme ceux de l'admin (bornes absolues, croissants Prudent → Full) ; proposition sans taux (drapeaux `HISTORIQUE_INSUFFISANT`, `CALAGE_COUT_ELEVE`, `CALAGE_INTERROMPU`) ⇒ validation refusée (400), seul Refuser reste possible. **Preset** : l'actif ayant déjà un preset ou une Affectation dédié (tous utilisateurs) ⇒ `DEDIE_CONSERVE`, rien touché (cas PAXG réglé à la main) ; sinon `APPLIQUE_BTC` : les presets de base BTC (Trend, Rainbow Bull, Rainbow Bear) sont copiés en presets SYSTEM de l'actif (`ExpertBasePresetService.copyBasePresetsFromBtc`). Aucune Affectation n'est créée ici (`ensureAffectations` reste borné à `RainbowLiveDefaultPresets.ASSETS`).
- **Refuser** : rien n'est écrit, le lien reste sur le repli BTC « non calibré ».
- **Une décision, une fois** : proposition déjà `VALIDE` / `REFUSE` ⇒ 400.
- **Droits et journal** : `/api/admin/new-asset-proposals` réservé ROLE_ADMIN ([`../api/rest-rainbow-live.md`](../api/rest-rainbow-live.md)). Chaque décision écrit `AdminAction.MODULATION`, cible `new-asset-proposal:<id>`, payload `{action, asset, rates, presetPlan, presetsCreated, flags, historySource, candleChecksum}` (refus de validation : `REFUSED`) ; auteur et horodatage portés par la ligne de journal, `decided_by` / `decided_at` par la proposition.
- Le drapeau `tradeio.new-asset-proposal.enabled` reste le seul déclencheur du calcul (défaut désactivé) ; l'écran et l'API lisent les propositions existantes même désactivé.

## Tests
`NewAssetProposalAdminServiceTest` : alarme créée une seule fois, avec lien, acquittable, non rouverte ; Valider écrit la ligne et la résolution la prend avant le repli BTC (BTC / ETH intacts) ; presets BTC copiés si aucun preset dédié ; preset PAXG dédié jamais écrasé ; taux invalides ou absents refusés ; Refuser conserve le repli et clôt l'alarme ; décision unique ; journal. `AdminNewAssetProposalSecurityTest` : non-admin refusé. `AssetBaseRateMigrationTest` : V8 idempotente.

`NewAssetProposalTest` : calage allégé = C4 ; BTC rejoué comme nouvel actif (taux proches de la table de C3) ; historique court = `PROVISOIRE` ; bougies insuffisantes et Binance indisponible = dégradé sans exception ; garde-fou de coût ; étiquette source stockée et propagée ; proposition `PROPOSE` sans écriture ailleurs ; déclenchement asynchrone filtré (BTC / ETH / déjà proposé).
