---
statut: journal
sujet: Résultats E0 — diagnostic descriptif des switchs Bull → Bear (BTC, ETH, PAXG) contre dates aléatoires, verdict, multiplicité, limites
modifié_le: 2026-11-20
empreinte: cf3ebd3d17
---
# E0 — Diagnostic des switchs Bull → Bear (2026-11-20)

Mécanisme et règle de verdict : [`../architecture/15j-diagnostic-switchs.md`](../architecture/15j-diagnostic-switchs.md). Cadre : [`etude-pistes-timing-sorties.md`](etude-pistes-timing-sorties.md). Tableaux par switch : [`etude-diagnostic-switchs-tableaux.md`](etude-diagnostic-switchs-tableaux.md). Un run (graine 20261116, bougies jusqu'au 2026-11-17, frais 0,40 % par ordre du simulateur, barème simplifié).

## Verdict BTC : `PISTE_FERMÉE`, cause (a)
N = 20 switchs fermés (21 au total, 1 ouvert : 2026-11-08). Bougies OKX 2018-01-11 → 2026-11-17, historique rejouable dès 2018-04-20 (1 617 j Bull, 1 517 j Bear).

| Condition (règle fixée avant lecture) | Valeur BTC | Référence | Tenue |
|---|---|---|---|
| (a) gain de coupure net > 0 sur ≥ 60 % des switchs | 50 % (10 / 20) | 60 % | **non** |
| (b) médiane du gain net > médiane du hasard | −2,4 % | −2,8 % | oui (0,4 pt : bruit) |
| (c) baisse restante 90 j plus mauvaise que le hasard | −21,2 % | −12,3 % | oui |

Phase de marché (rendement de P_S sur 365 j) : haussière 13 switchs (46 % net > 0, médiane gain net −5,1 %, baisse restante 90 j −22,1 %) ; baissière 4 switchs (50 %, −4,5 %, −12,2 %) ; 3 indéterminés (début d'historique). Aucune phase ne satisfait seule (a) et (c).

## Médianes, switchs contre hasard (BTC)
| Mesure | Switchs | Hasard (médiane ; P10 / P90) | Rang de la médiane |
|---|---|---|---|
| Baisse consommée au switch | −13,1 % | — | — |
| Baisse restante 90 j | −21,2 % | −12,3 % ; −36,9 / −0,4 | 31 % |
| Baisse restante 180 j | −30,9 % | −18,6 % ; −49,0 / −0,9 | 32 % |
| Rendement +90 j | −8,3 % | +6,0 % ; −27,6 / +68,3 | 35 % |
| Rendement +180 j | +22,7 % | +14,7 % ; −40,5 / +152,6 | 56 % |
| Gain de coupure brut | −1,6 % | −2,0 % ; −35,6 / +28,3 | 51 % |
| Gain de coupure net | −2,4 % | −2,8 % ; −36,1 / +27,3 | 51 % |

Faux switchs (clôture > plus haut depuis le dernier retour Bull avant S + 60 j) : 25 % (5 / 20 connus). Durée médiane de la phase Bear : 59 j.

## Lecture
- Après un switch, la baisse restante est **réellement pire que le hasard** (rang 31 %, rendement à 90 j négatif contre positif au hasard) : le signal détecte bien des phases baissières.
- Mais le gain de coupure n'est pas distinct du hasard (rang 51 %, médiane quasi égale) : la distribution est très asymétrique. 3 switchs de grand bear (2018-09 : +63 %, 2022-04 : +115 %, 2025-10 : +43 %) font la moyenne (+8,1 % net) ; sans eux, la moyenne est −3,5 %. Les 10 autres switchs négatifs coûtent −5 à −28 % (retours Bull rapides : mars 2023, janvier 2024, mai 2024, août-septembre 2025).
- Conséquence de la règle : couper n'aurait gagné que dans 1 switch sur 2, surtout en gagnant peu (+0,1 à +24 % pour 7 des 10 gagnants) ; la règle exigeait 60 %.
- Cette lecture est celle de la règle de gain. **Elle ne tient pas compte de la priorité de Clem au drawdown** (« préférer amputer une prise de gain que battre des records de drawdown ») : le test de ce critère (à plafond de drawdown égal) est E1, non lancé.

## Repères de Clem (BTC)
- **Faux switch du 11 au 23 mars 2023** : détecté tel quel (S = 2023-03-11 à 20 454,70, retour Bull 2023-03-23). Consommée −17,7 %, baisse restante 90 / 180 j = 0,0 % (aucune clôture sous P_S), rendement +44,9 % à 30 j, +29,5 % à 90 j, +28,4 % à 180 j, faux switch oui, gain de coupure −27,7 % brut / −28,3 % net, phase Bear 12 j, rang du gain 15 % (cas parmi les pires du hasard), rang de la baisse restante 100 %.
- **Août à octobre 2025** : 3 switchs dans la fenêtre. 2025-08-30 (faux : retour 2025-09-11, gain net −6,5 %), 2025-09-25 (faux : retour 2025-10-01, −8,8 %), 2025-10-10 (S à 112 769, retour 2026-04-22 : baisse restante 90 j −24,9 %, 180 j −44,2 %, gain de coupure +44,2 % brut / +43,1 % net, rang 94 %). Le premier switch « vrai » de la séquence est le 10 octobre ; les deux précédents sont des faux switchs de même retard.
- ETH : mars 2023 sans switch dans la fenêtre (13 j Bull, 0 j Bear) ; switch le plus proche 2023-06-10. 2025-10-10 : gain net +67,5 %, rang 95 %. PAXG : un seul switch en août 2025 (2025-08-20, faux, retour 8 j après).

## ETH et PAXG (hors verdict)
- **ETH** : N = 20 fermés ; `PISTE_FERMÉE` (causes a et b) : part net > 0 = 45 %, médiane gain net −5,1 % contre −2,8 % au hasard, baisse restante 90 j −18,7 % contre −17,4 %. Faux switchs 15 %.
- **PAXG** : N = 2 fermés (historique rejouable dès 2025-01-30) : `NON_CONCLUANT`, descriptif seul.

## Événements non indépendants
BTC, ETH et PAXG sont corrélés : les switchs d'ETH tombent près de ceux de BTC ; ETH et PAXG n'ajoutent pas d'indépendance réelle. Les phases Bear voisines d'un même actif se chevauchent (trois switchs en 41 jours à l'été-automne 2025), les 20 switchs BTC ne sont pas 20 événements indépendants : les 3 grands bears pèsent beaucoup, et la part de 50 % est sensible à 1 ou 2 switchs.

## Multiplicité
Une configuration, un run, aucune variante. Trois conditions conjointes (a, b, c) et un seuil (60 %, N ≥ 5) fixés avant lecture. Mesures rapportées sans correction (toutes les mesures par switch, 3 actifs) : seule la règle de verdict décide ; les autres lectures sont descriptives.

## Limites
- Mesure sur prix : aucun rejeu de stratégie, aucune contrainte de cash ni de bande L2 ; la coupure testée est « vendre 100 % à P_S, racheter à P_R » sur le prix de clôture (pas d'exécution intra-journalière, retard d'exécution nul).
- Frais : 0,40 % par ordre, aller-retour = 2 ordres, sans spread ni glissement ni fiscalité.
- Hasard : dates uniformes (inclut les phases de hausse, où une coupure perd mécaniquement) ; durée de phase tirée des phases Bear fermées de l'actif (N = 20).
- Un seul grand cycle baissier par actif avant 2022 (2018, 2022, 2025) : peu de grands bears.
- Écart de définition (corrigé avant toute conclusion) : le premier run prenait pour « plus haut d'avant S » le plus haut de tout l'historique, ce qui rendait le repère de mars 2023 impossible à détecter comme faux switch (BTC 20 k$ pour un ATH à 69 k$). Remplacé par le plus haut depuis le dernier retour Bull (référence de la mesure 1). Seules les lignes « faux switch » ont changé (BTC 15 → 25 %, ETH 5 → 15 %, PAXG 50 % inchangé) ; verdict et conditions (a, b, c) identiques entre les deux exécutions.

## Suite
Verdict de la règle : piste **fermée** (cause a), E1 non lancé. Arbitrage ouvert pour Clem : confirmer la fermeture, ou lancer quand même E1 au titre de la priorité drawdown (la baisse restante pire que le hasard, condition c, le justifierait), avec le risque d'un gain de coupure nul en médiane.
