---
statut: decision
sujet: modulation-exposition-puissance
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-10
empreinte: 5e379ce842
---
# modulation-exposition-puissance

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Bande d'exposition min/max ; marge m = 25 % de la largeur de bande.
- Puissance 0.25–2 ; biais β ±0.5 ; G=2 (réglable admin).
- Aucun ordre REBALANCE. Modulateur loggé en JSON.
- baseAmount côté user.
- Modulation ATH à extraire en paramètre user (étude en attente, 2026-11-09).
- 2 curseurs distincts (2026-10-13) : exposition (actif vs stable) et puissance (amount achats ET ventes). Le curseur statique seul est remis en question (wallet doit "vivre").
