---
statut: journal
sujet: Prompt lot 2 du simulateur complet — rejeu multi-actifs, cash et exposition communs, parité par wallet
modifié_le: 2026-11-14
empreinte: 8933e97656
---
# Prompt — Simulateur complet, lot 2 : multi-actifs, cash et exposition communs

**Lis d'abord [`prompt-simulateur-complet.md`](prompt-simulateur-complet.md)** (contexte, décisions, repères code, règles, méthode) : il vaut pour ce lot. Ce fichier ne donne que le spécifique du lot 2. Prérequis : lot 1 livré (commit `d0c399e`).

## Ce que le lot 1 a livré (à relire dans le code, ne pas refaire)
- `service/dca/atr/bench/SizingCore#decide(assetSymbol, LiveDemand, tradable, CashLedger, LiveModulation) -> Decision` : cœur pur partagé live/simu (modulation puissance/biais/bande, vente plafonnée à la position tradable, achat tout-ou-rien contre le `CashLedger`, mise à jour de l'`ExposureLedger`). `LiveSizing` n'est plus qu'un adaptateur qui écrit `RainbowLiveLiveBlock`. `ExposureLedger.ofValues(assets, total)` et `LiveModulation` sont publics pour les wallets simulés.
- `service/expert/simulation/FullReplayEngine` (pur, **mono-actif**) : Trend Mix → jeu Bull/Bear → pas Rainbow sur la position **virtuelle** → `LiveDemand` (vente = fraction × position tradable **simulée**, `withLinkBaseAmount`) → `SizingCore` → wallet fermé `(cash, qty)`. Fidélités acquises : amorçage de l'état moteur par rejeu comme `loadOrBootstrap`, ATH stocké (`rainbow_ath_reference`), cours de décision 23:55 (`actionPrice`) substitué à la clôture (SMA/ATR/régime/jeu recalculés sur la bougie ouverte), réglages de modulation du jour relus du journal live, ré-amorçage après un jour sans run. Deux exécutions : wallet **recalé** sur le snapshot live chaque jour (parité de la décision) et wallet **en continu**.
- `FullReplayService` + `ParityReport` + `GET /api/expert/simulations/parity` (`ExpertSimulationParityController`, mode EXPERT) : rapport d'écarts classés par cause ; acceptation ≥ 95 % de jours identiques et tout écart expliqué. **Parité BTC acceptée à 18/19** (écart du 28/10 : ancien moteur sans resync, non reconstituable) — `decisions/espace-expert.md`.
- Limite connue, **c'est l'objet de ce lot** : cause d'écart « dimensionnement (wallet multi-actifs, cash commun…) » — le cash et l'exposition des autres actifs du wallet ne sont pas rejoués.
- Pas encore de `SimulationType` ni d'écran (lot 5).

## Objectif
Étendre le rejeu à **tous les liens de l'utilisateur sur un même wallet** (BTC, ETH, PAXG…) qui se partagent **le même cash et la même exposition**, exactement comme la passe quotidienne live, et prouver la parité sur la période de prod pour chaque lien.

## Périmètre
- **Un wallet** par rejeu (un `CashLedger` USDC et un `ExposureLedger` par wallet). Plusieurs wallets : voir questions. Capital `C0` commun ; défaut = valeur du wallet réel du lien (cash USDC + actifs au prix : `decisions/wallet-reel-capital-commun.md`) ; en parité, `C0` et positions initiales = snapshots du premier run de chaque lien.
- **Signal par affectation, dimensionnement par lien** (`RainbowLiveExecutionService#runUser` → `runLinks`) : un état moteur, une position virtuelle, un ATH et des séries D1 **par affectation/actif** ; chaque lien redimensionne la demande de son affectation (`baseAmount`, `bagPercent`) sur la position tradable simulée de **son** actif. Source des candles = celle du lien (séries mutualisées par (actif, source), comme `PassContext`) ; calendriers D1 différents : un actif sans bougie ce jour-là n'a pas de demande (comme le live : lien non dimensionné).
- **Ordre de service identique au live** : l'ordre de `RealPortfolioSource#liveSlots` (`findByUserOrderByPriorityAscAssetSymbolAsc`, donc priorité puis symbole — **vérifier dans le code**, la doc évoque aussi rang d'actif/`bindingId`). Achat accepté ⇒ cash réservé (tout-ou-rien, `BLOCKED` + `INSUFFICIENT_CASH`) ; **les ventes ne créditent pas le cash le jour même** ; chaque action acceptée met à jour `P` de l'`ExposureLedger` pour le lien suivant (`V = P + cash` constant dans la journée). Le wallet n'est mis à jour (cash += ventes, −= achats, quantités) qu'une fois tous les liens du jour décidés.
- **Exposition** : le live calcule `P` depuis les positions du périmètre du wallet lues sur l'exchange, valorisées avec les clôtures des **demandes du jour** (`closesOf`) ; un actif détenu sans prix ce jour-là ⇒ exposition **inconnue** (pas de modulation de bande). Reproduire cette règle à l'identique (c'est une source d'écart de parité probable). Actifs détenus mais sans lien/affectation : voir questions.
- Mono-lien reste un cas particulier : le rejeu du lot 1 doit retomber **exactement** sur les mêmes résultats (non-régression : test de parité BTC inchangé, `FullReplayEngineTest` vert).

## À construire
1. **Moteur multi-liens (pur)** qui généralise `FullReplayEngine` : N entrées par actif (série, config Trend, jeux, état/position virtuelle/ATH, lien : base, bag, priorité) + un wallet commun ; boucle jour par jour. **Une seule implémentation du séquencement** : extraire/partager avec le live ce qui peut l'être (ordre des liens, construction du `CashLedger`/`ExposureLedger` du jour, application des décisions au wallet) plutôt que le recopier ; ne pas toucher au comportement du live (tests existants verts, gardes d'architecture inchangés).
2. **Sorties** : par jour, par lien (action, montant, quantité, raison, journal de modulation, cash/qty avant et après) ; courbe du wallet (valeur par actif, USDC, total) ; **DCA fixe de même budget** multi-actifs pour la référence (définition à valider : voir questions) ; valeur finale. Prévoir la forme de sortie pour les lots 4 (épisodes par actif) et 5 (graphiques), sans les construire.
3. **Parité multi-actifs** : étendre `FullReplayService`/`ParityReport` à tous les liens du wallet : comparaison par lien et par jour aux `RainbowLiveBindingRun` (+ `RainbowLiveRun`), exécution « wallet recalé » (restaurer **cash du wallet + cashReserved par les liens servis avant + quantité de chaque actif** depuis les snapshots du jour : le bloc `RainbowLiveLiveBlock` porte `cashUsd`, `cashReserved`, `positionQty`) et exécution « en continu ». Rapport : acceptation par lien **et** globale ; causes d'écart ; en particulier les `INSUFFICIENT_CASH`, l'effet de la priorité et l'exposition inconnue. Endpoint de parité étendu (même contrôleur, paramètres facultatifs, défaut = tous les liens du wallet), sans UI.
4. **Période** : les liens n'ont pas la même date de premier run ; fixer et documenter la règle (par lien depuis son premier run, avec `C0` du wallet reconstitué à la date commune la plus ancienne ? ou période commune ?) — à proposer à Clem.

## Base de parité
Même principe qu'au lot 1 : base locale = copie de la prod (`operations/dev-sync.md`), jamais de lecture directe de la prod, pas d'import sans l'accord de Clem. Vérifier que la copie contient les runs de **tous** les liens (ETH, PAXG) et leur `cashReserved`/`cashDetail`.

## Questions à poser à Clem avant de coder
- **DCA fixe de référence multi-actifs** : budget total ? réparti selon les `baseAmount` des liens, ou chaque lien à sa base ?
- **Wallets multiples** (un lien par wallet, plusieurs wallets par utilisateur) : un rejeu par wallet (capital par wallet, comme le live) ou un capital agrégé ? Proposition : un rejeu par wallet, sélectionnable.
- **Liens inactifs / en simulation** (`RainbowLiveBinding.simulation`, `executionEnabled`) : le live les dimensionne tous (`liveSlots` ne filtre pas) — les rejouer tous aussi ?
- **Actifs détenus sans lien** dans le périmètre du wallet : valorisés et figés (comptent dans `P`, jamais traités) ou ignorés ?
- Période de parité multi-actifs (voir point 4) et seuil global (≥ 95 % par lien ?).

## Livrables et porte de sortie
Moteur multi-liens à cash et exposition communs, parité par lien et globale sur la période de prod (écarts tous expliqués et acceptés par Clem), mono-lien non régressé. Docs : `architecture/13-espace-expert.md` (rejeu multi-actifs, limites levées), `08c-rainbow-chemin-achat-wallet.md` / `08d` si le séquencement partagé y est décrit, `decisions/espace-expert.md` (décisions nouvelles), puis doc-police `index` et `check`. Ne commite et ne déploie que sur demande de Clem.
