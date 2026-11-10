---
statut: decision
sujet: passerelle-actif-non-stable
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-10
empreinte: 12fc32c841
---
# passerelle-actif-non-stable

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- PAXG sur Kraken via USDC -> BTC -> PAXG. Fiat interdit, USDT exclu.
- Échec : retry puis stock en transit.
- Réalité : achats PAXG faits à la main dans l'UI Kraken (frais API trop élevés), absents de la liste des transactions TradeIO (2026-11-09).
