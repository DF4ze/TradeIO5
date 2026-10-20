# Spec V1 — DCA Rainbow : bande d'exposition, puissance et biais, embrayage risque macro

> **Mise à jour 2026-10-04** : le §1 (4 combos de bornes **%**) est **caduc** — le Rainbow est passé aux bornes ATR (`service/dca/atr`) et le paramétrage par trend est **Bull/Bear (2 jeux par actif, pas de Sideways)**. La question ouverte est le branchement d'une Trend (simple, ou double global/local) vers ces jeux : [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §2. Le §2 (bande d'exposition) et les §4 à §7 (puissance, biais, journal, onboarding, paramètres admin) sont un design validé par Clem le 2026-10-13, non implémenté ; le §3 (risque macro) reste une intention.

Date : 2026-09-25. Fait suite à [[tradeio5_rainbow_etude_2026-09-25_regime_exposition_risque]] et aux
échanges du même jour avec Clem. Les décisions ci-dessous sont actées par Clem ; les points marqués
« À trancher à l'implémentation » sont des choix techniques laissés ouverts, pas des décisions
produit. Cette spec ne code rien — elle cadre le prochain(s) prompt(s) d'implémentation.

Portée : toujours BTC seul (comme V0), toujours en D1. Aucun des 3 points n'est backtestable sur un
historique de score macro pour l'instant (cf. §3) — à traiter comme limite connue, pas comme blocage
de la spec.

---

## 1. Trend global + Trend local → 4 paramétrages Rainbow (CADUC, voir bandeau)

**Décision** : pas de M1/W1. Les deux indicateurs de trend tournent sur **D1**, mais avec un
paramétrage d'hystérésis différent — le "global" doit être nettement moins réactif que le "local"
actuel. Les deux trends passent à **2 états seulement : UP / DOWN** (suppression du RANGE pour cet
usage). Matrice 2×2 = **4 combos** (global UP/local UP, global DOWN/local UP, global UP/local DOWN,
global DOWN/local DOWN).

**Décision (revient sur la reco initiale de l'étude du 25/09)** : les 4 combos ont chacun leur
**jeu complet de bornes % Rainbow** (`percdown2`/`percdown1`/`percup1`/`percup2`/`percup3`), pas
seulement des multiplicateurs différents sur une géométrie de zone commune. Un bench de calibration
dédié doit déterminer, pour chacun des 4 états, le meilleur jeu de bornes (et multiplicateurs
associés). Conséquence assumée : surface de calibration ×4 (20 bornes au lieu de 5) et possibilité
de saut de zone à la transition d'un combo à l'autre (le prix peut changer de zone sans avoir bougé,
simplement parce que le régime a basculé) — accepté par Clem.

**Architecture proposée** :
- Réutiliser `TrendAnalyzer`/`RegressiveTrendScoreCalculator`/`RegressionHysteresisCalculator` tel
  quel comme moteur, mais instancié deux fois avec des jeux de constantes différents :
  - **Trend local** (existant, inchangé) : fenêtres 7/14/30, hystérésis entrée ±1/6, sortie 0.
  - **Trend global** (nouveau) : fenêtres plus longues (ex. 30/60/90 — valeurs exactes à calibrer,
    pas de valeur "évidente" comme pour le local) et/ou seuils d'entrée/sortie plus larges, pour
    filtrer le bruit que le local capte légitimement.
- **Ne pas toucher à `TrendRegime` existant** (consommé ailleurs, ex. `TrendConfirmationStrategy`) :
  introduire une classification à 2 états dédiée à cet usage (ex. `RainbowTrendDirection.UP/DOWN`),
  dérivée du `score` continu de `TrendState`.
- `RainbowDcaBacktestRequest` porte alors 4 jeux de paramètres complets (bornes + multiplicateurs)
  (`Map<RainbowTrendCombo, RainbowParams>` ou 4 champs nommés UP_UP/UP_DOWN/DOWN_UP/DOWN_DOWN),
  sélection du jeu actif à chaque jour de la boucle de `RainbowDcaBacktestService.backtest()` à
  partir des deux `TrendState` du jour (précalculés en amont sur toute la timeline, comme le fait
  déjà le bench Trend unifié).
- **Règle de passage à 2 états — les deux variantes seront implémentées et comparées au bench** (pas
  de choix a priori) :
  - (a) signe du score continu : `score > 0 => UP`, `score < 0 => DOWN`.
  - (b) hystérésis à 2 états avec dead-band propre (ex. `score > +seuil => UP`, `score < -seuil =>
    DOWN`, entre les deux => état précédent conservé), pour éviter le flip-flop autour de zéro.
  Le bench de calibration doit produire les deux jeux de résultats (4 combos × règle a, 4 combos ×
  règle b) pour permettre à Clem de trancher sur données réelles.
- **Transition pendant un armement (tranché)** : le jeu de paramètres actif est réévalué chaque
  jour, y compris pendant un armement ARMÉ/DÉCLENCHÉ en cours — pas d'état "jeu figé au moment de
  l'armement" à mémoriser (calcul stateless sur les 2 `TrendState` du jour). Conséquence assumée :
  les bornes/seuils de déclenchement peuvent différer entre le jour d'armement et le jour de
  déclenchement si le combo change entretemps.

**À trancher à l'implémentation** :
- Fenêtres et seuils exacts de l'hystérésis "global" (nécessite le bench de calibration dédié,
  comme celui qui a produit les seuils du local) — ce même bench doit aussi produire les 4 jeux de
  bornes % Rainbow et les résultats comparés règle (a) vs (b).
- Valeur du dead-band pour la variante (b).

---

## 2. Bande d'exposition (garde-fou du portefeuille, réglé par l'utilisateur)

**Décision (2026-10-13)** : l'idée d'un curseur-cible est abandonnée. Une consigne fixe s'oublie (100 % puis un bear : plus aucune vente) et, à l'équilibre, elle supprime toute activité alors que le wallet doit vivre. L'exposition devient une **bande** ; le régime reste géré par le Trend (jeux Bull/Bear) et les zones Rainbow, la bande ne pilote rien : elle borne.

- **Exposition** = valeur des actifs du périmètre (BTC/ETH/PAXG) ÷ (actifs + cash USDC), calculée sur la lecture portefeuille réelle (`PortfolioReading`), par wallet comme le `CashLedger` *(à confirmer ; agrégé multi-exchange = plus tard)*.
- L'utilisateur fixe `[min, max]` (ex. 30–90 %). Largeur minimale `minBandWidthPts` (§7).
- **Zone libre au centre** : aucune modulation, les achats/ventes Rainbow passent tels quels. La régulation n'existe que dans les marges de largeur `m = 25 % × (max − min)` à l'intérieur de chaque borne.
- **Marge haute** : `s = clamp((exp − (max − m)) / m, 0, 1)`, `l = s²(3 − 2s)` (smoothstep, courbe continue) :
  - achat × `(1 − l)` (0 à la borne) ;
  - vente × `(1 + (G − 1)·l)`, `G = 2` : ×1 → ×2.
- **Marge basse** : symétrique (`s` mesuré depuis `min + m`) : vente × `(1 − l)`, achat × `(1 + (G − 1)·l)`.
- Exemple, bande 30–90 (`m` = 15 pts) : la régulation haute démarre à 75 % ; à 80 %, `s = 0,33`, `l ≈ 0,26` ⇒ achat ×0,74, vente ×1,26.
- Même famille que la modulation par ATH (facteur continu sur le montant), mais axée sur le poids du portefeuille ; les deux se multiplient.
- **Plafond dur après ordre** : `achat ≤ max·V − P`, `vente ≤ P − min·V` (`V` valeur totale, `P` valeur des actifs). Un ordre seul ne franchit jamais une borne.
- **Hors bande** (variation de prix, dépôt, retrait) : **aucun ordre de rééquilibrage** (décision de Clem, 2026-10-13 : on n'achète et ne vend que quand le Rainbow le dit). Au-delà d'une borne, `l = 1` : l'achat qui s'en rapproche est ramené à 0, la vente est accentuée (×G), et le plafond dur empêche tout ordre de franchir la borne.
- **Anti flip-flop** : courbe continue, plafond dur après ordre, largeur de bande minimale, produit des modulateurs plafonné (§5), cooldowns Rainbow existants conservés ; pas de mécanisme dédié supplémentaire (décision de Clem).
- **Oubli** : pas d'alerte de bande (l'alerte utilisateur concerne surtout le bag vide) ; côté écran, informer l'utilisateur de l'exposition et de l'effet de ses réglages pour qu'il puisse corriger.
- Cas limites : `min = 0` (pas de marge basse) et `max = 100` (pas de marge haute).
- Backtest : non couvert (aucun wallet simulé dans le run live) ; à spécifier à part si besoin.

---

## 3. Risque macro comme "embrayage" via `riskCursor`

**Décision, reformulation importante par rapport à l'étude du 25/09** : `riskCursor` (0-10, déjà
persisté sur `UserTradingSettings`, aujourd'hui orphelin) n'est **pas** un facteur de tolérance
personnelle statique. C'est un **gain/embrayage** appliqué à un **score de risque marché calculé**
(à partir des indicateurs macro existants : DXY, S&P/Nasdaq, ETF flow, calendrier macro, etc.).
`riskCursor = 0` → aucune incidence du risque macro sur le Rainbow (embrayage débrayé) ;
`riskCursor = 10` → incidence maximale.

**Architecture proposée (2 briques distinctes)** :
1. **Un nouveau calculateur de score de risque marché**, indépendant de `Decision.confidence`
   (toujours inexploitable pour un backtest historique — event-sourcé, pas d'historique requêtable
   par date, cf. étude §Point 3). Ce calculateur agrège les indicateurs/Opinions macro déjà
   existants (`GlobalMarketOpinion`, `MacroMarketOpinion`, éléments déjà utilisés par
   `MacroRiskWindowModulator`) en un score continu normalisé (échelle à définir, ex. -1..+1 ou
   0..1). C'est une **nouvelle** agrégation : ces briques sont aujourd'hui internes au moteur
   Opinion/Decision tactique, pas exposées telles quelles pour un usage DCA.
2. **`riskCursor` comme gain multiplicatif** sur l'effet de ce score : `effetRisque = score ×
   (riskCursor / 10)`, combiné aux autres facteurs (zone Rainbow, exposition) sur les quantités
   achat/vente — position dans la chaîne de multiplication et signe exact à définir avec Clem au
   moment de l'implémentation (ex. `montantFinal = baseAmount × zoneMultiplier × facteurExposition ×
   (1 − effetRisque)` pour un risque qui freine l'achat, ou une formule dédiée si le risque doit
   aussi accélérer la vente).
- `UserTradingSettingsService`/`riskCursor` (déjà persisté, endpoint REST existant) sert de gain
  utilisateur ; le score de risque marché est calculé côté serveur, indépendant de l'utilisateur.

**Limite connue, à trancher avec Clem avant de coder** :
- Un score de risque macro **historique rejouable jour par jour sur plusieurs années** n'existe pas
  aujourd'hui (mêmes indicateurs macro que ceux consommés par le moteur Opinion, mais jamais
  restitués comme série temporelle backtestable). Deux options : (a) construire ce score uniquement
  pour un usage **live** dans un premier temps (le backtest V1 reste sans risque macro, comme V0
  aujourd'hui), ou (b) investir d'abord dans l'historisation des séries macro nécessaires pour
  pouvoir le rejouer en backtest — effort nettement plus lourd, à chiffrer séparément si souhaité.

**À trancher à l'implémentation** :
- Quels indicateurs macro exacts entrent dans le score, et avec quelle pondération (nouveau sujet
  de calibration, pas de valeur "par défaut" identifiée dans le code existant).
- Signe exact de `effetRisque` (frein à l'achat seul, ou aussi accélération de la vente) : sa place dans la chaîne est fixée au §5.
- Portée : ce score de risque doit-il rester propre au DCA Rainbow, ou est-il conçu dès le départ
  pour être réutilisable par le chantier Decision→Order plus large (qui a le même besoin non résolu,
  cf. `PLACEHOLDER_QUANTITY` dans `DefaultMarketScenario`) ?

---

## 4. Puissance et biais achat/vente (réglés par l'utilisateur)

- **Puissance `P`** : un seul scalaire, multiplicateur du montant des achats **et** des ventes (baisser si la macro est mauvaise, monter par beau temps). Bornes `[powerMin, powerMax]` (§7), défaut 1. Pas de curseurs achat/vente séparés (ils s'enchevêtreraient avec l'exposition).
- **Biais `β`** : `P_achat = P·(1 + β)`, `P_vente = P·(1 − β)`, `β ∈ [−biasMax, +biasMax]` (§7, ±0,5), défaut 0. Dérive dirigée de l'exposition (β > 0 pousse vers la borne haute), bornée par la bande (§2).
- `riskCursor` (§3) reste le gain de l'embrayage macro **automatique** ; la puissance est le levier **manuel**. Les deux se multiplient.
- Réglages portés par l'utilisateur (bande `min`/`max`, `P`, `β`), portés par `UserTradingSettings` (colonnes nullables, `null` ⇒ valeurs neutres : bande 0–100, `P` = 1, `β` = 0). Appliqués dans `LiveSizing`, par lien, jamais dans le signal (partagé par preset, il reste comparable).
- Vente : `P_vente` s'applique à la quantité voulue, puis plafond à la position tradable (`bagPercent`).
- Montant sous `minSz`/`costMin` : report (carry) déjà décidé, cf. [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §5d.

## 5. Chaîne de composition et journal de modulation

Achat : `base × facteurATH × multZone` (signal, `ReferenceSizer`) → `× P_achat` → `× (1 − effetRisque)` (§3, sens à définir) → **modulation de bande** (§2) → plafond dur → cash du ledger → `minSz`/`costMin`. Vente : même ordre avec `P_vente` et la fraction × position.
Le produit des modulateurs (puissance, biais, macro, bande) est plafonné à `modulationProductMax` (§7).

**Journal** : chaque action du lien enregistre dans son bloc live (`RainbowLiveLiveBlock`) la valeur de chaque terme pour expliquer un montant après coup :
- `base`, `facteurATH`, `multZone` ;
- `P`, `β`, `P_achat`/`P_vente`, `effetRisque` ;
- exposition avant et après, bande, `s`, `l`, facteur de bande appliqué ;
- plafonds atteints (bande, cash, `minSz`/`costMin`) et montant final ;
- valeurs des paramètres admin utilisées (§7), car elles peuvent changer entre deux passes.
La page affiche « pourquoi ce montant » à partir de ce journal.

## 6. Onboarding

- **Explication** du fonctionnement : Trend → jeu Bull/Bear → zones → montant ; puis bande, puissance, biais.
- **Binding automatique** : quand l'utilisateur ajoute un exchange (clés valides), `BindingCheck` puis création d'un lien par actif du périmètre (BTC/ETH/PAXG) avec le preset système. Les liens restent **inactifs** (activation explicite conservée). Un actif `NOT_TRADABLE` n'est pas lié, la raison est affichée.
- **Amount proposé** après lecture des soldes et de la capacité du wallet : `baseAmount = cash alloué / (autonomyDays × multiplicateur moyen des zones du preset)`, borné par le minimum du catalogue (`costMin`/`minSz` avec marge) et vérifié au Fee Test. L'écran affiche « le cash tient ~N jours en zone extrême » ; valeur modifiable (changer le `baseAmount` remet le carry à zéro, cf. [`graphique/remarques-gui.md`](../graphique/remarques-gui.md) §2).
- Proposition de bande par défaut selon le profil, modifiable.

## 7. Paramètres administrables

Globaux (tous utilisateurs), modifiables depuis l'interface admin sans redéploiement, sur le modèle de `ExecutionControl` (ligne singleton, relue à chaque passe sans cache, `updatedAt`/`updatedBy`). Ligne absente ⇒ défauts en constantes mutualisées ; **bornes absolues en constante**, que la base ne peut pas relever ; validation à l'écriture ; chaque modification écrite dans `admin_action_log` ; effet à la prochaine passe, runs passés figés (valeurs utilisées dans le journal, §5).

| Paramètre | Défaut | Bornes absolues | Rôle |
|---|---|---|---|
| `regulationMarginPct` (m) | 25 % de la largeur de bande | 5–50 % | largeur des marges de régulation (§2) |
| `sellBoostMax` (G) | 2 | 1–4 | accentuation max de la vente (achat à la borne basse) |
| `biasMax` | 0,5 | 0–1 | amplitude du biais β |
| `powerMin` / `powerMax` | 0,25 / 2 | 0–3 | bornes de la puissance |
| `minBandWidthPts` | 10 | 5–50 | largeur minimale de la bande |
| `modulationProductMax` | 2,5 | 1–5 | plafond du produit des modulateurs |
| `autonomyDays` | 90 | 7–365 | jours d'autonomie du cash pour l'amount proposé (§6) |

Défauts validés par Clem (2026-10-13), y compris les propositions initiales.
API : `GET`/`PUT /api/admin/rainbow-modulation` (ADMIN). Écran : onglet « Modulation » de la page Admin (champ numérique + valeur par défaut affichée + réinitialisation, aperçu optionnel des courbes achat/vente selon l'exposition). Voir [`graphique/remarques-gui.md`](../graphique/remarques-gui.md) §3.

---

## Hors scope de cette spec (V1)
- Wallet agrégé multi-exchange pour le calcul d'exposition.
- Backtest de la bande / de la puissance.
- Historisation des séries macro pour un backtest de risque rejouable (si l'option (b) du §3 est retenue).

## Séquencement
Roadmap en 4 étapes : [`../prompts/prompt-roadmap-modulation-bande-puissance.md`](../prompts/prompt-roadmap-modulation-bande-puissance.md). §3 (risque macro) reste conditionné à l'arbitrage live-vs-backtest.
