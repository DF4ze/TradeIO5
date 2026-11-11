-- Nettoyage appliqué UNIQUEMENT à la base de recette (copie de prod), avant le premier démarrage.
-- Objectif : aucun effet de bord vers l'extérieur et aucun conflit avec la prod.
-- Pas de DDL ici (le schéma se migre par Flyway au démarrage, jamais à la main).

-- Clés TRADE : jamais en recette.
DELETE FROM api_credentials WHERE scope = 'TRADE';

-- Clés READ des exchanges : neutralisées (désactivées, secrets vidés) et NON supprimées, car wallet.credential_id les
-- référence (FK RESTRICT). Raisons : nonce Kraken strictement croissant PAR CLÉ (partage prod/recette => Invalid nonce)
-- et aucun appel authentifié vers un exchange depuis la recette. Les clés de données publiques (Coinalyze, CoinStats,
-- Twelve Data...) sont conservées : sans nonce, lecture seule.
UPDATE api_credentials ac
  JOIN provider p ON p.id = ac.web_provider_id
   SET ac.enabled = 0, ac.api_key = 'recette-neutralisee', ac.secret_key = NULL, ac.passphrase = NULL
 WHERE p.code IN ('KRAKEN','OKX','BINANCE','BINANCE_TESTNET','BITVAVO','GATE_EU','ONE_TRADING','REVOLUT_X');

DELETE FROM provider_pending_key;

-- Ceinture et bretelles : aucun lien ne peut exécuter, même si un mode LIVE était activé par erreur.
UPDATE rainbow_live_binding SET execution_enabled = 0;
