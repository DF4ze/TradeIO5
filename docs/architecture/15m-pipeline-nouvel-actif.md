---
statut: courant
sujet: Pipeline d'accueil d'un nouvel actif (D1b) — proposition automatique (preset BTC + taux par profil), historique provider / Binance bench, drapeaux, persistance, alerte Docteur, validation admin
vérifié_le: 2026-11-24
modifié_le: 2026-11-24
empreinte: 2776a28ff1
---
# Pipeline nouvel actif (D1b) — proposition, alerte, validation admin

Un lien créé sur un actif **sans ligne `asset_base_rate` valide** (`AssetBaseRateService.isCalibrated`) et sans proposition déclenche le calcul d'une **proposition** : preset de départ + taux de base par profil + drapeaux. **Rien n'est appliqué sans action admin** : le lien tourne déjà sur le repli BTC « non calibré » ([`08d-rainbow-modulation.md`](08d-rainbow-modulation.md)). Alerte et validation : section « Alerte et validation admin ». Décisions : [`../decisions/full-autonome-v1.md`](../decisions/full-autonome-v1.md), [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Nouveaux actifs »).

## Déclenchement
`RainbowLiveBindingService.create` publie `NewAssetLinkedEvent` ; `NewAssetProposalService.onLinked` (`@TransactionalEventListener(AFTER_COMMIT)`) met le calcul en file et rend la main. Tout actif éligible peut être lié : seuls ceux sans ligne de taux valide déclenchent (BTC, ETH, PAXG en ont une). Activation : `tradeio.new-asset-proposal.enabled` (**défaut : `true`**, dans le code — `matchIfMissing` — et dans `application.properties`). Les propriétés de test et le modèle de recette `application-recette.properties.template` le mettent à `false` : aucun calcul réel pendant les tests ni la recette.
- Exécution : fil dédié `new-asset-proposal` (daemon, priorité minimale, un calcul à la fois) + fil de calcul avec délai maximal (30 min) ; jamais sur le chemin du cron live. Aucune exception ne remonte : un échec devient une proposition dégradée (`CALAGE_INTERROMPU`).
- Une proposition existante pour l'actif (quel que soit son statut) empêche le calcul automatique ; seul le recalcul admin (section suivante) en relance un.
- Un seul calcul à la fois par actif : l'actif est marqué « en cours » de la mise en file à la fin du calcul (`NewAssetProposalService.isRunning`) ; le calcul automatique est alors ignoré, un recalcul est refusé.

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
- **Alerte** : à l'enregistrement d'une proposition `PROPOSE`, alarme Docteur WARN `NEW_ASSET_PROPOSAL:<actif>` (« Actif à valider », famille DATA, impact QUALITY) levée **explicitement** (`AlarmService.raise`, hors passe du Docteur : aucun contrôle ne la réconcilie). Une seule fois par proposition (alarme déjà ouverte ⇒ aucun effet, pas de doublon ni de désacquittement), acquittable comme toute alarme ; le message porte le lien `/admin#assets` (ouvre l'onglet Actifs). La pastille du menu admin compte **1 point par proposition `PROPOSE`** (alarme comprise, sans doublon) : une alarme acquittée ne masque donc pas une proposition encore à valider ([`12-doctor.md`](12-doctor.md)). Clôturée par Valider et Refuser ; un recalcul la clôt (`closeAlert`) puis la relève pour la nouvelle proposition.
- **Écran** : onglet « Actifs » de `/admin`, section « Actifs à valider » en haut (`assets.js`, pastille du nombre de propositions sur l'onglet) : par proposition, preset de départ, taux par profil (plafond, multiplicateur, taux, statut), drapeaux, mois d'historique, blocs, switchs, source et empreinte des bougies, marge k, avertissement « non tradable » ; une ligne annonce **avant validation** ce qui arrivera au preset ; boutons Valider / Refuser (confirmation).
- **Valider** (transaction unique) : écrit la **ligne de taux dédiée** de l'actif (`asset_base_rate`, `V8__asset_base_rate.sql`, entité `AssetBaseRate` : 4 taux, proposition source, validateur, date). Résolution et validation (`AssetBaseRateService`, source unique des taux, aussi éditable dans l'onglet admin « Taux de base ») : ligne de l'actif d'abord, sinon (BTC / ETH) table de calage, sinon repli BTC « non calibré ». Taux contrôlés par `Rates.validate()` (≥ 0, bornes absolues, croissants Prudent → Full) ; proposition sans taux (drapeaux `HISTORIQUE_INSUFFISANT`, `CALAGE_COUT_ELEVE`, `CALAGE_INTERROMPU`) ⇒ validation refusée (400), seul Refuser reste possible. **Preset** : l'actif ayant déjà un preset ou une Affectation dédié (tous utilisateurs) ⇒ `DEDIE_CONSERVE`, rien touché (cas PAXG réglé à la main) ; sinon `APPLIQUE_BTC` : les presets de base BTC (Trend, Rainbow Bull, Rainbow Bear) sont copiés en presets SYSTEM de l'actif (`ExpertBasePresetService.copyBasePresetsFromBtc`). Aucune Affectation n'est créée ici (l'Affectation Système est créée au lien, `AssetAffectationService.forAsset`).
- **Refuser** : rien n'est écrit, le lien reste sur le repli BTC « non calibré ».
- **Une décision, une fois** : proposition déjà `VALIDE` / `REFUSE` ⇒ 400.
- **Droits et journal** : `/api/admin/new-asset-proposals` réservé ROLE_ADMIN ([`../api/rest-rainbow-live.md`](../api/rest-rainbow-live.md)). Chaque décision écrit `AdminAction.MODULATION`, cible `new-asset-proposal:<id>`, payload `{action, asset, rates, presetPlan, presetsCreated, flags, historySource, candleChecksum}` (refus de validation : `REFUSED`) ; auteur et horodatage portés par la ligne de journal, `decided_by` / `decided_at` par la proposition.
- Le drapeau `tradeio.new-asset-proposal.enabled` commande le calcul (automatique et recalcul) ; à `false`, l'écran et l'API lisent les propositions existantes et « Recalculer » répond 409 avec un message clair.

## Recalcul admin (« Recalculer »)
`POST /api/admin/new-asset-proposals/recompute?asset=<ACTIF>` (ROLE_ADMIN, `NewAssetRecomputeService`) relance le calcul d'un actif : après un refus, un calcul dégradé (`CALAGE_INTERROMPU`…), un historique devenu suffisant, ou pour recalibrer une ligne déjà validée (ex. PAXG, ligne copiée du taux BTC par `V10`). Bouton : ligne de l'actif de l'onglet Actifs ([`11-front-app.md`](11-front-app.md)).
- **Contexte** : plus ancien lien non archivé de l'actif (utilisateur, wallet, provider) ; aucun lien actif ⇒ 400. Même calcul, même file asynchrone, même délai maximal que l'accueil ; réponse `202 {asset, status: EN_FILE, message}`, le résultat arrive à la fin du calcul.
- **Remplacement, sans changement de schéma** : `new_asset_proposal` n'a ni unicité par actif ni clé étrangère (`asset_base_rate.proposal_id` est une simple colonne). La dernière proposition de l'actif (`PROPOSE` ou `REFUSE`) voit son contenu remplacé dans la même ligne (statut `PROPOSE`, décision effacée) ; si elle est `VALIDE`, une **nouvelle ligne** `PROPOSE` est écrite (la validée reste la cible de `proposal_id`). Pendant le calcul, la proposition précédente reste telle quelle.
- **Taux jamais touchés** : la ligne `asset_base_rate` déjà validée n'est pas modifiée tant que le nouveau résultat n'est pas validé (Valider l'écrase, comme à la 1ʳᵉ validation).
- **Alarme** : l'ancienne alarme « Actif à valider » est close, la nouvelle levée à la fin du calcul.
- **Refus propres** : calcul déjà en file ou en cours pour l'actif ⇒ 409 ; pipeline désactivé ⇒ 409 (« Calcul des nouveaux actifs désactivé… ») ; symbole invalide ⇒ 400.
- **Journal** : `AdminAction.MODULATION`, cible `new-asset-recompute:<ACTIF>`, payload `{action: recompute, asset, linkId, previousStatus}` (refus : `REFUSED`).

## Tests
`NewAssetProposalAdminServiceTest` : alarme créée une seule fois, avec lien, acquittable, non rouverte ; Valider écrit la ligne et la résolution la prend avant le repli BTC (BTC / ETH intacts) ; presets BTC copiés si aucun preset dédié ; preset PAXG dédié jamais écrasé ; taux invalides ou absents refusés ; Refuser conserve le repli et clôt l'alarme ; décision unique ; journal. `AdminNewAssetProposalSecurityTest` : non-admin refusé (liste, Valider, Refuser, Recalculer). `NewAssetRecomputeTest` : remplacement en place après refus / calcul dégradé, nouvelle ligne après validation sans écriture de taux, un seul calcul par actif. `NewAssetRecomputeServiceTest` : demande valide, drapeau coupé, déjà en cours, symbole invalide, journal. `NewAssetProposalFlagTest` : drapeau vrai par défaut, faux en recette et en test. `AssetBaseRateMigrationTest` : V8 idempotente.

`NewAssetProposalTest` : calage allégé = C4 ; BTC rejoué comme nouvel actif (taux proches de la table de C3) ; historique court = `PROVISOIRE` ; bougies insuffisantes et Binance indisponible = dégradé sans exception ; garde-fou de coût ; étiquette source stockée et propagée ; proposition `PROPOSE` sans écriture ailleurs ; déclenchement asynchrone filtré (BTC / ETH / déjà proposé).
