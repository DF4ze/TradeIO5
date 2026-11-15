---
statut: journal
sujet: Protocole de calibrage Rainbow, attribution des pertes, couches portefeuille/macro, risque
modifié_le: 2026-11-15
empreinte: 5567e5a908
---
# Étude — Protocole de calibrage Rainbow, attribution des pertes, couches de pilotage, gestion du risque

Statut : **étude, aucun code**. Ouverte le 2026-11-14 (réflexion de Clem). Rien ici n'est tranché sauf mention « décidé ».

Contexte : l'Espace Expert permet de régler les presets et de simuler en séparant Trend Bull / Bear ([`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md)). Le bench d'optimisation automatique a été abandonné ([`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md), [`../calibration/calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md)) : cette étude le rouvre sous une autre forme.

## 1. Vision en couches (idée de Clem)

| Couche | Rôle | Existant |
|---|---|---|
| L1 Rainbow | exécutant : dit si acheter / vendre est bon par rapport au cours (math) | fait |
| L2 Portefeuille | tient compte de l'état du wallet : exposition, PnL, prix moyen | partiel : bande, puissance, biais ([`08d`](../architecture/08d-rainbow-modulation.md)) ; rien par régime, rien lié au PnL |
| L3 Macro / média | pilotage « aérien » : indicateurs macro, veille YouTube | `riskCursor` orphelin, `effetRisque = 1` ; pas d'historique rejouable ([spec V1 §3](spec-rainbow-v1-trend-global-exposition-risque-macro.md)) |

Objectif : d'abord orchestrer au mieux ce qui existe, ensuite de nouvelles orchestrations.

## 2. Protocole de calibrage

Repris et développé dans l'étude maître [`etude-optimisation-mode-autonome.md`](etude-optimisation-mode-autonome.md) : définition d'un bon paramétrage (§2), rejeu intégral (§3), méthode par lots dans un espace réduit (§4).

À inclure dans le périmètre du bench (Clem, 2026-11-14) : le **carry** et le **biais d'achat**, désormais accessibles dans l'interface ; plus généralement les réglages L2 (bande, puissance, biais), pas seulement les paramètres du preset.

## 3. Attribution des pertes (« où est-ce que je perds ? »)

À faire avant le protocole : indépendant de toute optimisation, et c'est la base pour savoir quoi régler. La simulation sort déjà, par bougie, bag, réserve, réalisé, potentiel et phases Bull/Bear ⇒ post-traitement.

Postes à chiffrer par phase (Bull et Bear séparés) : achats hauts suivis d'un drawdown ; ventes trop tôt (manque à gagner vs conserver) ; bag porté à l'entrée du Bear ; cash dormant en Bull ; retard du switch de Trend ; frais.

### Timeline des actions (idée de Clem, 2026-11-14)

Schématiser le rejeu comme une suite d'épisodes datés, un par passage de zone, pour localiser où naît la perte :

| Épisode | Contenu |
|---|---|
| Entrée en zone d'achat (ex. X2) | acheté X $ à Y $, prix de revient après achat, PnL latent |
| Zone neutre | durée, variation du prix, PnL latent en fin d'épisode |
| Zone de vente | vendu X $ à Y $, PnL réalisé (vs prix de revient), PnL latent restant |

Par épisode : régime (Bull / Bear), zone, dates, montants, prix de revient, PnL réalisé et latent. Agrégat ensuite par zone et par régime ⇒ lecture directe des zones perdantes. Sert de sortie standard du bench (§2) : chaque jeu testé produit sa timeline, comparable d'un jeu à l'autre.

**Décidé (2026-11-14)** : la timeline vit dans l'Espace Expert, avec les autres graphiques de la simulation ([`13-espace-expert.md`](../architecture/13-espace-expert.md)) : synchronisée avec eux (zoom, déplacement, survol) et filtrée par la plage choisie par l'utilisateur (épisodes et agrégats recalculés sur la plage visible). Calcul côté Java (`SimulationEngine`), le front n'en recalcule rien, comme pour le reste de la simulation.

Précisions décidées (2026-11-14) :
- **Découpage** : nouvel épisode à chaque changement de zone **et** à chaque bascule Bull / Bear (même repère Bull/Bear que les autres graphiques).
- **Forme** : piste d'épisodes sous le graphique, colorée par PnL, plus un tableau repliable (détail des épisodes, agrégat par zone × régime).
- **Types de simulation** : `RAINBOW` (un seul régime, celui du Trend de référence) et `AFFECTATION` (les deux régimes, issus de l'assemblage Trend + Rainbow : aucune Trend à choisir sur cette page).

Précisé et implémenté (lot 4, 2026-11-15) : épisode tronqué à la plage ; PnL d'un épisode = variation de (réalisé + latent) au prix moyen, donc zone neutre = variation du latent ; définitions exactes dans [`13-espace-expert.md`](../architecture/13-espace-expert.md).

Pour chaque poste, dans l'ordre : un paramètre existant le règle-t-il ? → faut-il un nouveau paramètre ? → relève-t-il de L2 / L3 ?

## 4. Raisonnements simples à inventorier (hypothèses L2 testables en rejeu)

- Être à 50 % d'exposition à la déclaration d'un Bear : bon pour le rendement ? ⇒ bande par régime, dé-risque au switch.
- (liste à compléter au fil de l'attribution des pertes)

## 5. Vente forcée / retour dans la bande

Constat prod (logs VPS, 2026-11-14) : bande abaissée vers 10 %, exposition restée ~25 %. Depuis le 2026-10-15 (début des logs conservés) : **aucun signal SELL**, Trend DOWN, jeux Bear ⇒ achats seulement. La bande ne fait que moduler les quantités demandées par le Rainbow : les achats sont bien ramenés à 0 (depuis le 2026-11-11), mais sans signal de vente rien ne fait baisser l'exposition. Conséquence directe de « aucun ordre REBALANCE » ([`../decisions/modulation-exposition-puissance.md`](../decisions/modulation-exposition-puissance.md)).

Position de Clem (2026-11-14) : il faut faire quelque chose ; parfois tendre vers une vente « forcée », en gardant si possible le mécanisme du Rainbow.

**Décidé (2026-11-14)** : A + C, vente et achat symétriques — détail dans la [décision](../decisions/modulation-exposition-puissance.md). Écartés : vente de régulation automatique sous veto Rainbow (B), étalement sur N jours, échéance en jours.

Lecture : A couvre le côté favorable (vendre plus haut / acheter plus bas), le prix limite X de C le côté défavorable ⇒ encadrement sans notion de temps. À l'achat, X est au-dessus du cours : on n'achète que si le marché confirme.

Points d'attention pour la spec :
- C au prix X vend par construction dans la faiblesse (logique de stop : perte figée, exposé à l'aller-retour) : assumé.
- L'exposition bouge déjà seule avec le prix (25 % ⇒ ~14 % après −50 %) : paliers calculés en quantité, pas en % d'exposition.
- Moteur D1 : paliers jugés à la clôture du jour.
- Plafonds d'exécution (30 USD par ordre, 100 USD par jour) : un retour « immédiat » peut prendre plusieurs jours ; cash disponible côté achat.

À spécifier (plus tard, hors cœur de cette étude) : nombre et forme des paliers vers X ; forme du décalage A (linéaire selon le dépassement ? plafond ?) ; répartition entre actifs et liens (prorata, priorité ?) ; annulation / modification d'un retour en cours ; journal et affichage ; rejeu en simulation.

## 6. Risque : modulation ATH et montant réellement engagé

- Achat = base × facteur ATH (jusqu'à 4–5) × multiplicateur de zone (X2 = 2, ARMÉ = 3) ⇒ 10 à 15 × la mise de base, constaté par Clem sur le pine. Le bench mesurait ~280 % du budget d'un DCA fixe déployé en moyenne (112–478 %).
- `modulationProductMax` ne plafonne que puissance × biais × bande, pas ATH × zone.
- La proposition de montant de base (onboarding) ignore le facteur ATH ⇒ autonomie surestimée.
- Lié : détachement de la modulation ATH en réglage utilisateur (étude en attente, discussion Cowork du 2026-11-09 : 3 lots proposés, 6 décisions ouvertes).
- Pistes : plafond « mise max = k × base » ; budget par période ; amplitude ATH réglable ; rôle de la **Puissance** à redéfinir dans ce cadre (question de Clem).

## 7. Questions ouvertes

1. Retour dans la bande : paliers, forme du décalage A, répartition entre actifs (§5) — secondaire, le sujet principal reste le recalibrage.
2. Fenêtres du protocole : durées fixes ou phases de régime ?
3. Score : celui de Clem (harmonique réalisé/potentiel) conservé ? Séparé Bull / Bear ?
4. Risque : plafond par ordre, budget par période, ou les deux ? Défaut ?
5. L3 macro : y revenir maintenant ou après L2 ?

## 8. Ordre proposé

1. Attribution des pertes (§3) sur les presets actuels, BTC d'abord.
2. Vente forcée (§5) et risque / ATH (§6) : décisions puis specs.
3. Protocole de calibrage (§2), nourri par 1.
4. L3 macro.
