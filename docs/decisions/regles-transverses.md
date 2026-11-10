---
statut: decision
sujet: regles-transverses
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-10
empreinte: 3b7d140e4e
---
# regles-transverses

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Cotation : tout en USDC sur tous les providers (Revolut X inclus : BTC-USDC, ETH-USDC). USDT abandonné.
- Fiscalité FR : pas de noeud fiat (`ExecutionDefaults.FIAT_CURRENCIES`). Prévenir Clem seulement si une de ses demandes sort du cadre.
- Cache DB candles : tout fetch exchange est persisté (éviter bannissement). Une DB à ~1500 H1 = signe de sur-requêtage. L'appli ne doit pas nécessiter de reboot régulier.
- Utilitaires (ex. Postman) dans le repo sous `tools/` + référence dans docs/, jamais hors repo.
- Moins de fichiers temporaires dans le repo (bat, logs, rapports de bench).
- Prompts d'implémentation exécutés = supprimés ; plus de dossier prompts.
