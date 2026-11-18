---
statut: journal
sujet: Roadmap — moteur d'optimisation automatique (presets Rainbow par actif + politique de pilotage utilisateur)
modifié_le: 2026-11-18
empreinte: f21e5c0a27
---
# Roadmap — moteur d'optimisation automatique

Cadre et décisions : [`etude-optimisation-mode-autonome.md`](etude-optimisation-mode-autonome.md) §11, [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md). Simulateur existant : [`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md). Règles de code : [`../CODING_RULES.md`](../CODING_RULES.md).

## Méthode (chaque étape)
1. Prompt d'étape fourni dans la conversation (non conservé dans le repo).
2. Réalisation → tests verts → doc mise à jour dans la même session.
3. **Bilan de dérive** avant l'étape suivante : diff réel vs périmètre prévu, contrats modifiés, parité du rejeu inchangée, hypothèses invalidées, doc à jour. Le prompt suivant est rédigé d'après ce bilan, pas d'après ce plan.
4. Porte de sortie = critère chiffré ; échec ⇒ on s'arrête et on décide.

Règles transverses : tout en USDC ; rejeu = code du live (jamais de logique dupliquée) ; aucune donnée > J-1 dans une décision ; DB modifiée ⇒ Flyway `V<n>__*.sql` idempotent + recette (sinon rien) ; bench coûteux ⇒ demander avant de lancer ; crons désactivés par défaut (`-`).

## Étapes

| # | Étape | Livrable | Porte de sortie |
|---|---|---|---|
| 1 | **Hook de politique + politiques manuelles + test H0** | `ModulationPolicy` (interface pure : état du jour → `ModulationSettings`) appelée dans `MultiLinkReplayEngine` en plus de `settingsByDay` ; politique constante = comportement actuel ; réglages du jour journalisés dans le résultat ; clamp `RainbowModulationControl` conservé. État d'entrée : régime par lien, exposition, valeur / plus haut / drawdown du wallet, PnL. 2–3 politiques manuelles (bande par régime ; biais selon l'écart à l'exposition cible ; dé-risque à la bascule Bear), option `policy` dans `FULL`, comparaison à la constante (valeur finale, drawdown, exposition / timing / frais) sur BTC, ETH, PAXG | Rejeu identique avec politique constante (parité et tests existants inchangés) ; **verdict H0 chiffré** : la couche exposition pèse-t-elle plus que le réglage fin ? Décide de l'ordre 4 / 5 |
| 2 | **ATH : plafond de mise utilisateur (rejeu d'abord)** | Plafond k (achat : ATH × zone ≤ k × base ; vente : facteur ATH plafonné), global à l'utilisateur, défauts par profil, appliqué dans le **rejeu** (`LinkSignalReplay`), exposé dans `FULL` et le compare ; **sans changement de schéma** (persistance, défauts admin et écran Réglages en étape 7) ; live inchangé | Plafond respecté (aucune mise > k × base) ; sans plafond = résultats actuels ; effet du plafond mesuré sur BTC, ETH, PAXG |
| 3 | **Évaluateur + moteur de recherche headless** | `ReplayScorer` pur (entrées **explicites** : capital, dates, empreinte des bougies ; fenêtres 12 m ×3 / 24 m ×2 / historique ×1 ancrées ; gain vs DCA fixe ; vetos drawdown du profil, jeu dégénéré, capital dépassé ; timing pur affiché en second ; modes frais simplifiés et complet) + `ParamSpace` (bornes, gel ; inclut le montant de base), tri par sensibilité, Sobol + successive halving, raffinement du plateau, bousculades (date ±30 j, provider, voisins), compteur d'essais, pool dédié ; `OptimizationReport` JSON ; runner de test manuel + endpoint admin asynchrone désactivé par défaut ; démo sur fonction synthétique et sur l'espace L2 réel | Rejeu **reproductible à l'octet** (mêmes entrées ⇒ même sortie) ; retrouve un optimum connu ; coût en ms / run, budget de 4 et 5 estimé |
| 4 | **PolicyOptimizer (L2)** | Correction du biais de budget du scorer (DCA fixe à budget de référence commun, gain en % du capital) ; espace de 6 paramètres (bande par régime ×4, plafond d'achat ATH, multiple du montant de base) calibré sur un wallet de référence multi-actifs, pour chacun des 4 profils ; walk-forward en plis annuels ; rapport avec recommandation « adopter / garder le point de départ » ; rien n'est appliqué | Retenue seulement si bat le point de départ sur la majorité des plis sans veto ; sinon résultat nul assumé |
| 4b | **Pré-étape 5 : correction de l'évaluateur** (**faite**, 2026-11-16) | Référence DCA au même cash commun (jamais au-delà, sans recharge) ; veto de drawdown, score et dispersion sur 12 m / 24 m seulement (fenêtre complète rapportée) ; pli à entraînement vetoé = `GARDER_DEPART` (`ENTRAINEMENT_VETOE`), jamais d'`INVALID_CANDIDATE` retenu ; statut `HISTORIQUE_INSUFFISANT` ; étape 4 rejouée | Départ vetoé à l'entraînement < 30 %, aucun `INVALID_CANDIDATE` retenu, référence ≤ capital, pénalité < 50 % du score (ou alerte) : **non atteinte** (départ vetoé 58 %, drawdown 12 m / 24 m) ⇒ arbitré (2026-11-17) : protocole V6 en opt-in swappable, défauts inchangés, passage à l'étape 5 ; voir [`etude-calibrage-politique-l2.md`](etude-calibrage-politique-l2.md) |
| 5 | **PresetOptimizer (L1, par actif)** (**code fait**, 2026-11-17 ; walk-forward : `GARDER_PRESET` BTC / ETH, PAXG non optimisable ; cadences à lancer sur accord ; voir [`etude-optimisation-presets.md`](etude-optimisation-presets.md)) | Rainbow Bull / Bear de chaque actif, Trend figée, dérive bornée δ autour du preset en place, challenger non appliqué ; espace libre et δ en configuration (défaut ±25 %), score gain brut + exposition plafonnée, cadences 30 / 90 / 180 j ; walk-forward imbriqué (lancé seulement après accord) | Gain du processus (ré-optimiser tous les X j) vs preset statique démontré, ou abandon |
| 6 | Orchestrateur + promotion auto-bornée | **en pause** : rien à promouvoir tant que A–B ci-dessous n'ont pas de gain démontré | — |
| 7 | Mode Full Auto (live) | **en pause** (dépend de 6 et de L2, résultat nul à l'étape 4) | — |

## Pivot « Rainbow d'abord » (2026-11-17)
L1 et L2 automatiques n'ont montré aucun gain (étapes 4 et 5 : évaluateurs aveugles, presets en place vetoés, espace de recherche qui gelait ATH / Moon / multiplicateurs). Priorité : **trouver quels réglages Rainbow font gagner, avant toute automatisation**. Position de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Pivot Rainbow d'abord »).

| # | Étape | Livrable | Porte de sortie |
|---|---|---|---|
| A (**faite**, 2026-11-18 ; [`etude-sensibilite-rainbow.md`](etude-sensibilite-rainbow.md), mécanisme [`15b`](../architecture/15b-carte-sensibilite-rainbow.md)) | **Carte de sensibilité BTC** | Preset BTC en place ; **Bull seul**, puis **Bear seul** (l'autre jeu intact) ; un paramètre à la fois sur sa plage, **tous** ceux de l'interface Expert (bornes ATR, stops, délais, fraction vendue, ATH, multiplicateurs de zones, Moon ; interrupteurs et modes en variantes) ; vue 24 mois ; comparaison au **preset en place ET au DCA fixe** ; mesure en **PnL réel** (ventes encaissées, latent décoté) ; tableau classé par influence | Tableau lisible ; les paramètres influents recoupent le ressenti de Clem : **atteinte côté lisibilité** (Bull : réglages de vente ; Bear : `multX2`, `atrMultDown2`) ; recoupement avec le ressenti de Clem à confirmer au bilan. Verdict : des réglages battent le preset en place et le DCA en PnL réel sur 24 mois mais sur une seule fenêtre et 442 valeurs testées ; rien de validé hors échantillon (étape B) |
| B | **Optimisation façon main** | Sur les 3–5 paramètres les plus influents : ajuster un par un, garder si le PnL réel monte, Bull et Bear en alternance (méthode du rejeu Expert) ; contrôle hors échantillon, une seule fois | Bat le preset en place **et** le DCA hors échantillon, ou résultat nul assumé |
| C | **Autres actifs, puis automatisation périodique** | ETH, PAXG (historique requis), puis ré-optimisation tous les X j | Seulement si A et B montrent un gain réel |

## Dépendances
A → B → C ; 6 et 7 reprennent après C. La plomberie existante (`SearchEngine`, rejeu, rapport) est réutilisée.

## Ouvert
Vue de test (24 mois, cycles bull / bear), traitement du « gel » hors régime, décote du latent, cadence X, rayon δ.
