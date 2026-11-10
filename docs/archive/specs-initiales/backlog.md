---
statut: archivé
sujet: Spec initiale — BackLog
archivé_le: 2026-11-10
raison: Backlog de départ (juil. 2026) ; le suivi courant est dans known-gaps/
modifié_le: 2026-11-10
empreinte: 353fe251c2
---
# BackLog

> ⚠ **ARCHIVÉ le 2026-11-10** — backlog initial. Original : `../sources/specs-initiales/BackLog.ods`. Ne jamais lire comme référence de l'état courant.

## Feuille1

| Id | Story | Task | Unit State |  | Task State |  |
|---|---|---|---|---|---|---|
| 1.0 | Implémentation Kraken |  | RUNING |  |  |  |
| 1.1 |  | Créer la classe KrakenApiClient qui implémente ExchangeApiClient |  |  | DONE |  |
| 1.2 |  | Utiliser l’API officielle Kraken pour : |  |  | DONE |  |
| 1.3 |  | getExchangeCode() → retourner "KRAKEN" |  |  | DONE |  |
| 1.4 |  | getBalance() / getAllBalances() → récupérer soldes (mapping asset → quantity) |  |  | DONE |  |
| 1.5 |  | getMarketPrice() → ticker spot pour une paire donnée |  |  | DONE |  |
| 1.6 |  | getHistoricalTrades() / getTradesSince() → extraire l’historique des trades |  |  | RUNNING | Bug ? |
| 1.7 |  | Gérer authentification API key/secret a l’Initialisation |  |  | DONE |  |
| 2.0 | Consolidation Multi-Exchange |  | RUNING |  |  |  |
| 2.1 |  | S’assurer que tous les échanges (BinanceApiClient, CryptoComApiClient, KrakenApiClient) respectent strictement l’interface |  |  | DONE |  |
| 2.2 |  | Vérifier que WalletSource inclut Kraken et autres exchanges prévus |  |  | DONE |  |
| 2.3 |  | Gestion unifiée des symboles (BTC vs XBT, LDUSDC → USDC) |  |  | TODO |  |
| 3.0 | Mécanismes de Trading déjà prévus |  | RUNING |  |  |  |
| 3.1 |  | GridBot : espacement dynamique des lignes en fonction du portefeuille |  |  | TODO |  |
| 3.2 |  | DCA intelligent : ajustement en fonction d’indicateurs (RSI, Rainbow Chart) |  |  | RUNNING |  |
| 3.3 |  | Trailing Stop intelligent : delta ajustable + modulation par temps et volatilité |  |  | TODO |  |
| 3.4 |  | Crash Recovery : pause ou renfort achat après chute brutale |  |  | TODO |  |
| 3.5 |  | Rééquilibrage auto : selon pondération cible |  |  | TODO |  |
| 3.6 |  | Simulateur DCA (tool MCP calculate_dca) : calendrier d'achats, prix moyen pondere, total investi, PnL a date, cache H1 partage (CachingMarketDataApiClient) |  |  | DONE |  |
| 4.0 | Services Transverses |  | TODO |  |  |  |
| 4.1 |  | Journalisation intelligente : historique, exports JSON/CSV, rapports hebdos |  |  | TODO |  |
| 4.2 |  | Alertes : variations rapides |  |  | TODO |  |
| 4.3 |  | Transfert auto de bénéfices : % vers wallet Ledger |  |  | TODO |  |
| 3.2.1 | Définir la fréquence d’achat | Ex : toutes les semaines, ou configurable par l’utilisateur |  |  | DONE |  |
| 3.2.2 | Définir le montant de base | Ex : 25€ par défaut, configurable |  |  | DONE |  |
| 3.2.3 | Intégrer indicateur RSI | Lire RSI et ajuster montant selon zone de sur/sous-cotation |  |  | TODO |  |
| 3.2.4 | Intégrer Rainbow Chart | Ajustement supplémentaire selon position dans les bandes |  |  | TODO |  |
| 3.2.5 | Détection sous-cotation / sur-cotation | Si actif sous-coté → augmenter l’achat, sur-coté → diminuer ou vendre |  |  | TODO |  |
| 3.2.6 | Journalisation | Sauvegarder chaque opération dans base / log |  |  | TODO |  |
| 3.2.7 | Tests unitaires | Vérifier calculs dynamiques et déclenchement des achats |  |  | TODO |  |
| 3.2.8 | Tests d’intégration | Avec Kraken / Binance pour vérifier exécution réelle des ordres |  |  | TODO |  |
| 3.2.9 | Gérer un cache Bougies | A la façon du cache sur les Balances |  |  | DONE |  |
| 5.0 | Sécurisation |  |  |  |  |  |
| 5.1 |  | Sortir les clés d’API du code ! | TODO |  |  |  |
|  |  |  |  |  | TODO |  |
