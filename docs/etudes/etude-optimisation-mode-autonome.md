---
statut: journal
sujet: Optimiser TradeIO vers un mode Full Autonome — définition du bon paramétrage, instrument de mesure, méthode, agencement des briques
modifié_le: 2026-11-14
empreinte: 02575de365
---
# Étude — Optimiser TradeIO vers un mode « Full Autonome »

Statut : **étude, aucun code**. Ouverte le 2026-11-14. But fixé par Clem : « TradeIO doit permettre un mode Full Autonome qui sait jouer sur tous ses atouts pour gagner de l'argent ». Cette étude est le document maître ; les sujets détaillés (timeline des actions, retour dans la bande, risque ATH) sont dans [`etude-protocole-calibrage-couches.md`](etude-protocole-calibrage-couches.md).

Sources lues : `architecture/` 01, 02, 05, 08, 08d, 09, 13 ; `decisions/` ; `calibration/calibration-rainbow-atr-v3-regime.md` ; `etude-bench-auto-trend-mix-nouvel-actif.md` ; spec V1 ; `mindset/` ; `SimulationEngine` ; logs prod du 2026-10-15 au 2026-11-14.

## 0. Thèse

1. On ne peut pas optimiser sans définir ce qu'on maximise. Aujourd'hui il n'y a pas de définition unique d'un « bon paramétrage » : §2 en propose une.
2. L'instrument de mesure actuel ne mesure pas ce qui tourne en prod (§3). Tant que le bench ne rejoue pas toute la chaîne avec un vrai capital, toute optimisation règle autre chose que le gain réel.
3. Les preuves déjà accumulées (§1) disent que l'argent se joue surtout dans **combien de capital est exposé et quand** (couche portefeuille), moins dans les réglages fins du Rainbow ou de la Trend. C'est une hypothèse forte à vérifier en premier, parce qu'elle décide de l'ordre de tout le reste.
4. La recherche automatique de paramètres a déjà échoué deux fois hors échantillon. Elle peut resservir, mais seulement dans un espace réduit, avec un score robuste et une validation en conditions réelles (§4).
5. Le mode autonome est une boucle fermée mesure → recalibrage, avec garde-fous, atteinte par paliers (§6, §8).

## 1. Ce que l'existant a déjà prouvé

| Constat | Source | Ce que ça implique |
|---|---|---|
| Recherche auto des jeux Bull/Bear, même sous garde-fous : meilleure en échantillon, **moins bonne hors échantillon dans 4 cas sur 4** | étude Trend Mix §7 | surapprentissage ; un score d'entraînement ne prouve rien |
| Grille du Trend Mix (≈ 14 400 combinaisons) : aucun edge robuste ; une Trend « oracle » ne fait guère mieux que le défaut | idem | la Trend n'est pas le levier ; ne plus l'optimiser, seulement contrôler sa qualité (K1–K7b) |
| Modulation ATH à l'achat = « levier n°1 » du bench (score ÷ 3 à 9 sans elle) | calibration v3 | ce n'est pas un talent de timing : c'est du **capital déployé en plus dans les creux** (280 % du budget DCA fixe en moyenne, jusqu'à 478 %). Effet de taille de mise, donc de risque |
| Capital de la simulation = `max(investi − vendu)` calculé après coup | `SimulationEngine#wallet` | capital « idéal » connu seulement à la fin : jamais à court de cash, ce qui n'existe pas en vrai |
| Bande, puissance, biais, carry, cash commun, frais, minimum d'ordre : absents du rejeu | `13-espace-expert.md`, `08d` | le bench règle le signal, la prod exécute le signal **modulé** : deux objets différents |
| Prod : aucun signal de vente du 2026-10-15 au 2026-11-14 (Trend DOWN), exposition bloquée hors bande | logs VPS | en Bear le système ne sait qu'accumuler ; l'exposition n'est pas pilotée |
| PAXG : pas de valeur mesurée face au DCA fixe ; ≈ 0,8 % de frais par jambe sur Kraken | calibration v3, known-gaps §5c | sur un actif peu volatil les frais peuvent manger l'edge |
| Dans l'étude Trend, l'excès annuel cumulé du Rainbow piloté vs DCA fixe à budget égal ressort négatif pour tous les candidats (BTC ≈ −300) | étude Trend Mix §7 | unité et référentiel à revérifier avec le §2 ; si confirmé, le Rainbow ne gagne pas par son timing à budget égal |

Lecture d'ensemble : chaque fois qu'on a mesuré à capital égal, l'avantage du timing fin est faible ou nul ; chaque fois qu'un jeu « gagne », il déploie plus. D'où l'hypothèse **H0 : l'essentiel du gain vient de l'exposition (couche portefeuille), pas du réglage du signal**.

## 2. Qu'est-ce qu'un bon paramétrage

### 2.1 Cadre de mesure : le wallet fermé réel
- Capital de départ `C0` en USDC, fixé **avant** le rejeu, sans apports. Plus de capital calculé après coup.
- Toute la chaîne rejouée comme en prod : Trend → jeu Bull/Bear → signal → modulation (bande, puissance, biais) → cash disponible → frais → minimum d'ordre et carry.
- Capital commun aux actifs joués ensemble (BTC + ETH + PAXG se partagent le cash, comme en prod).

### 2.2 Objectif
**Maximiser la valeur finale du wallet (USDC + actifs au prix de marché), sous contrainte de risque.** En pratique : rendement annualisé, avec un drawdown maximal du wallet à ne pas dépasser (tolérance de l'utilisateur, liée au profil de départ).

Références à battre, à capital égal :
- **(a)** HODL à exposition constante (milieu de bande, acheté au départ) ;
- **(b)** DCA fixe de même budget ;
- **(c)** Rainbow sans Trend (jeu unique) ;
- **(d)** 100 % USDC (zéro).

Référence principale retenue : **(b) DCA fixe**. Un jeu qui ne la bat pas hors échantillon n'apporte rien, quel que soit son score interne.

### 2.3 D'où vient le gain (décomposition obligatoire)
`gain vs référence = effet exposition + effet timing − frais`
- **Exposition** : exposition moyenne × mouvement du marché (être peu exposé quand ça baisse, beaucoup quand ça monte).
- **Timing** : prix d'achat et de vente vs prix moyen de la période (acheter sous la moyenne, vendre au-dessus).
- **Frais** : frais réels + spread.

Cette décomposition tranche H0 et dit quelle couche régler. Elle se calcule par épisode de la timeline (étude calibrage §3) et s'additionne.

### 2.4 Un objectif différent par régime (cohérent avec `mindset/`)
- **Bear = accumuler** : quantité d'actif obtenue par USDC (prix de revient vs prix moyen de la phase), cash restant en fin de phase (ne pas être à sec avant le creux), drawdown.
- **Bull = encaisser** : USDC réalisés, prix de vente vs sommet de la phase, bag conservé (moon).
- **Passage de relais** : chaque phase est valorisée au prix de marché à la bascule ⇒ la somme des phases égale le total. Un jeu Bear se juge aussi sur ce qu'il remet au Bull suivant (bag, prix de revient, cash).

### 2.5 « Bon » = robuste, pas « meilleur »
Un jeu n'est retenu que s'il tient quand on le bouscule :
- **plateau** : les voisins (chaque paramètre ±1 cran) donnent presque le même résultat ;
- **date de départ** : même verdict en décalant le début de 1 à 30 jours (une machine à états y est très sensible) ;
- **source des candles** : même verdict sur Binance / Kraken / OKX (constat de Clem : le Rainbow PAXG change beaucoup selon le provider) ;
- **hors échantillon** : phases jamais vues à l'optimisation ;
- **non dégénéré** : vend et rachète, capital déployé plafonné (K9, K11).

### 2.6 Score proposé
- Vetos : drawdown > tolérance, jeu dégénéré, capital dépassé.
- Score = **médiane** du rendement excédentaire vs le DCA fixe (b) sur l'ensemble des rejeux bousculés, moins une pénalité de dispersion. On retient le centre du plus grand plateau, jamais le maximum.
- Le score de Clem (harmonique réalisé / potentiel) reste affiché comme lecture secondaire. Les KPI K8–K16 de l'étude Trend sont réutilisés tels quels plutôt que réinventés.

## 3. L'instrument : un rejeu intégral fidèle à la prod

Prérequis n°1. Sans lui, §2 n'est pas mesurable.

- **Même code que le live**, sans I/O : `TrendMixCalculator` → `RainbowSetSelector` → `RainbowAtrStrategy#step` → `LiveDemand` → modulation (`ExposureModulator` est déjà un calcul pur) → registre de cash → frais (taux du Fee Test) → minimum d'ordre / carry.
- Entrées : `C0`, réglages L2 (bande, puissance, biais, marge), presets, source de candles, plage.
- Sorties : courbe du wallet, timeline des épisodes, décomposition §2.3, KPI.
- **Test de vérité** : rejouer la période de prod et retrouver les runs réellement enregistrés depuis octobre (actions, montants). Tant que la parité n'est pas là, le bench ne sert pas à décider.
- La simulation Expert actuelle reste l'outil de réglage visuel du signal ; le rejeu intégral s'y ajoute (type de simulation supplémentaire), il ne la remplace pas.

## 4. Méthode : chercher sans se raconter d'histoires

Contrainte de fond : BTC D1 depuis 2017 = environ 3 cycles et quelques dizaines de phases. Avec ~30 paramètres par jeu, n'importe quelle recherche trouve un « gagnant » par hasard (règle K16 : 1 paramètre libre pour 10 régimes observés).

1. **Tri par sensibilité** (pas d'optimisation) : autour des jeux manuels, par régime, faire varier un paramètre à la fois et mesurer l'effet sur le score §2.6. Résultat : la liste ordonnée voulue par Clem. On garde libres les 4 à 6 paramètres qui comptent, les autres sont figés.
2. **Recherche dans l'espace réduit** : grille ou tirage quasi aléatoire (pas de montée coordonnée, piégée par les effets non monotones), par lots dans l'ordre des couches (§7). Chaque lot repart du meilleur plateau du lot précédent — c'est le protocole « par lots / par passes » de Clem, rendu sûr par l'espace réduit et la règle du plateau.
3. **Validation** : walk-forward par phases + bousculades du §2.5. Marge de décision = 2σ des perturbations (notion déjà en place). On compte le nombre d'essais : plus on essaie, plus la barre monte.
4. **Champion / challenger en prod** : le jeu candidat tourne en simulation à côté du jeu en place (les liens en mode Simulation et le run quotidien existent déjà) pendant N semaines. Promotion seulement s'il fait au moins aussi bien. C'est le seul vrai hors-échantillon, et la brique de base du recalibrage autonome.
5. **Horizons** : le « moment actuel » se juge sur plusieurs horizons de phases (récentes pondérées plus fort, pire horizon en garde-fou), résumés en une valeur. Pas J-7 / J-14 / J-21 : trop peu de trades en D1.

## 5. Inventaire des atouts et rôle cible

| Brique | État | Rejouable sur l'historique ? | Rôle dans le mode autonome |
|---|---|---|---|
| Trend Mix | en prod | oui | L1 : choisit Bull / Bear. Figé, contrôlé, non optimisé |
| Rainbow ATR (jeux Bull / Bear) | en prod | oui | L1 : exécutant (quand acheter / vendre vs le cours) |
| To the moon | en prod | oui | L1 : assurance du bag en Bull |
| Modulation ATH | en prod (dans le signal) | oui | à déplacer en **L2 risque** (taille de mise), plafonnée |
| Bande, puissance, biais, marge | en prod (live) | calcul pur, **pas encore dans le rejeu** | L2 : pilotage de l'exposition |
| Retour dans la bande (A + C) | décidé, non codé | à rejouer | L2 |
| Carry, minimum d'ordre, Fee Test, routes | en prod | à intégrer au rejeu | coûts réels dans la mesure |
| Supertrend en stop suiveur | idée | oui | L2 : protection |
| ETF flow BTC / ETH | historisé en base, verdict « validateur J-1 » | oui (profondeur à vérifier) | L3 : premier candidat |
| DXY, SP500, NASDAQ ; plafond stablecoin ; Fear & Greed | lus au fil de l'eau | historique à constituer (faisabilité à vérifier par provider) | L3 : score de risque marché (`riskCursor`) |
| OI / funding / liquidations | lus ; MovementQualification jamais déclenchée | partiel | L3, priorité basse |
| Zones de rejet | calibrées, poids faible | oui | modulateur secondaire |
| Carnet d'ordres | live | non | exécution seulement |
| Calendrier macro | live (ForexFactory) | non | L3 : fenêtre de risque, en observation |
| Veille média YouTube | en prod | non | L3 : en observation |
| Pipeline Opinion → Scenario → Decision | branche en pause | non (event-sourcé) | ne pas relancer ; réutiliser ses indicateurs |

Règle d'entrée en L3 : une source n'agit sur les montants que comme **modulateur borné** (`riskCursor`). Rejouable ⇒ mesurée au bench d'abord. Non rejouable ⇒ tourne « à blanc » en prod : son avis est journalisé avec ce qu'il aurait changé, et jugé après plusieurs mois.

## 6. Architecture cible : la boucle autonome

`Données → Régime (Trend) → Signal (Rainbow) → Dimensionnement (L2 : budget, bande par régime, plafond de risque, retour dans la bande) → Contexte (L3, modulateur borné) → Exécution (plan, Fee Test, plafonds, kill switch) → Mesure (journal, timeline, décomposition) → Recalibrage (champion / challenger)` puis retour au début.

Paliers d'autonomie :
| Palier | Contenu | État |
|---|---|---|
| N1 | signal quotidien, exécution manuelle | aujourd'hui |
| N2 | exécution automatique, presets fixes | presque (cron d'exécution désactivé) |
| N3 | L2 automatique : exposition par régime, budget de risque, retour dans la bande | à concevoir |
| N4 | recalibrage automatique borné : challenger proposé, testé à blanc, promu sous conditions | à concevoir |
| N5 | contexte L3 actif | après N4 |

Garde-fous communs : tout changement automatique est borné, journalisé, réversible ; validation humaine au début de chaque palier, levée ensuite.

## 7. Leviers, classés par gain attendu (hypothèses à confirmer par le §2.3)

1. **Exposition par régime** : bande Bull ≠ bande Bear, allègement à la bascule Bear (« 50 % d'exposition à la déclaration d'un Bear ? »). Si H0 est vraie, c'est le premier levier.
2. **Budget et risque** : plafond de mise (ATH × zone), autonomie du cash (être à sec avant le creux est une perte sèche), rôle de la Puissance.
3. **Vente en Bull** : fraction, délai, moon — c'est là que le gain devient réel.
4. **Frais et routes** : fréquence et taille des ordres, surtout PAXG.
5. **Réglages fins L1** par régime, dans l'espace réduit.
6. **Contexte L3**.
7. Trend : rien à gagner d'après les mesures ; contrôle seulement.

## 8. Feuille de route (une porte à chaque étape)

| Étape | Livrable | Porte de sortie |
|---|---|---|
| E1 | Définition validée (§2) + rejeu intégral + timeline + décomposition | parité avec les runs de prod |
| E2 | Diagnostic des jeux actuels BTC / ETH / PAXG | H0 tranchée, leviers classés par chiffres |
| E3 | L2 par régime et risque (design, rejeu, décisions) | bat les références (a) et (b) hors échantillon |
| E4 | Tri par sensibilité puis recalibrage L1 réduit | plateau + hors échantillon |
| E5 | Champion / challenger à blanc en prod | challenger ≥ champion sur N semaines |
| E6 | L3 rejouable (ETF flow, puis macro), le reste en observation | effet mesuré, borné |
| E7 | Autonomie : cron d'exécution, promotion automatique bornée | décision de Clem à chaque palier |

## 9. Limites

- Aucune méthode ne garantit un gain : peu de cycles, le passé ne se répète pas à l'identique.
- Tant que E1 n'est pas faite, les chiffres des benchs existants ne sont pas comparables à la prod.
- H0 peut être fausse ; l'étape E2 est faite pour le savoir avant d'investir ailleurs.
- Cadre inchangé : tout en USDC, aucun noeud fiat.

## 10. Décisions de Clem (2026-11-14)

- **Objectif** : valeur finale maximale du wallet sous plafond de drawdown — validé. Valeur du drawdown maximal : à fixer.
- **Référence principale** : DCA fixe de même budget (le HODL à exposition constante reste affiché en lecture secondaire).
- **Capital du bench** : capital de départ seul, sans apports.
- **Premier chantier** : le simulateur complet (rejeu intégral, §3).
  - Emplacement : Espace Expert, nouveau type de simulation à côté de Trend / Rainbow / Affectation.
  - Périmètre : les liens réels de l'utilisateur (affectations, bande, puissance, biais), modifiables avant de lancer ; cash commun.
  - Frais : taux réels par exchange et par route. Ils sont longs à récupérer et changent rarement ⇒ **cache global** des frais, réutilisable par les autres écrans (ajout d'un lien notamment).
- **Drawdown maximal réglable par profil** : c'est la « prise de risque » que Clem veut introduire. Premier lot : le simulateur le mesure et signale un dépassement, sans effet sur les ordres.
