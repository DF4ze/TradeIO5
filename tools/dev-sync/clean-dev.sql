-- Nettoyage appliqué UNIQUEMENT à la base de dev après restauration d'une copie de prod.
-- Objectif : aucun effet de bord vers l'extérieur. Pas de DDL (le schéma se migre par Flyway, jamais à la main).

-- Clés de la PROD : TRADE supprimées, clés READ des exchanges neutralisées (FK wallet.credential_id ; chiffrées avec la
-- clé maître de la prod, illisibles en dev). import-dev.sh réinjecte ensuite les clés LOCALES du développeur par-dessus.
DELETE FROM api_credentials WHERE scope = 'TRADE';
DELETE FROM provider_pending_key;

UPDATE api_credentials ac
  JOIN provider p ON p.id = ac.web_provider_id
   SET ac.enabled = 0, ac.api_key = 'dev-neutralisee', ac.secret_key = NULL, ac.passphrase = NULL
 WHERE p.code IN ('KRAKEN','OKX','BINANCE','BINANCE_TESTNET','BITVAVO','GATE_EU','ONE_TRADING','REVOLUT_X');

-- Aucun lien n'exécute ; kill switch ENGAGÉ par défaut (import-dev.sh remet ensuite l'état local) ; plans d'ordres non exécutables.
UPDATE rainbow_live_binding SET execution_enabled = 0;
UPDATE execution_control SET kill_switch = 1, updated_by = 'dev-sync';
UPDATE rainbow_live_order_plan SET status = 'CANCELLED' WHERE status IN ('PLANNED','EXECUTING','PARTIAL');
