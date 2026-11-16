---
statut: decision
sujet: modulation-exposition-puissance
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-16
empreinte: feb8b14a74
---
# modulation-exposition-puissance

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Bande d'exposition min/max ; marge m = 25 % de la largeur de bande.
- Puissance 0.25–2 ; biais β ±0.5 ; G=2 (réglable admin).
- Retour dans la bande (2026-11-14, remplace « aucun ordre REBALANCE » ; non codé, cf. [étude](../etudes/etude-protocole-calibrage-couches.md) §5) : hors bande, deux mécanismes cumulés, à la vente comme à l'achat (sens inverse).
  - **A** bornes du Rainbow décalées selon le dépassement (le Rainbow déclenche plus tôt de lui-même).
  - **C** retour demandé par l'utilisateur au changement de bande, deux modes : **immédiat**, ou **prix limite X** (« à ce prix, je dois être revenu dans l'exposition demandée » : paliers à mesure que le prix va vers X ; s'il s'arrête avant, le reste du bag / du cash est gardé).
  - Pas d'échéance en jours : contraire au principe (on garde tant que la perte maximale acceptée n'est pas atteinte).
  - X vient de l'utilisateur au moment où il modifie sa bande : saisi en prix ou en % de mouvement (une tolérance, comme un slippage sur sa demande). **0 % = régulation immédiate** (le mode immédiat n'est que ce cas).
- Modulateur loggé en JSON.
- baseAmount côté user.
- Modulation ATH détachée du preset (2026-11-16) : **plafond de mise** utilisateur, global à tous ses liens ; achat : mise (facteur ATH × multiplicateur de zone) ≤ k × base ; vente : facteur ATH de vente plafonné de même ; valeurs de départ par profil de risque (Prudent, Équilibré, Dynamique, Full), réglées par l'admin comme les plafonds de drawdown. Les paramètres ATH du preset restent le signal ; le plafond s'applique par-dessus. Rejeu d'abord, live ensuite ([roadmap](../etudes/roadmap-moteur-optimisation.md)).
- 2 curseurs distincts (2026-10-13) : exposition (actif vs stable) et puissance (amount achats ET ventes). Le curseur statique seul est remis en question (wallet doit "vivre").
