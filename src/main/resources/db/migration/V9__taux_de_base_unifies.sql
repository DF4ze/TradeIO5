-- V9 : taux de base unifiés dans asset_base_rate (source unique, 1 ligne par actif, 4 taux).
-- 1) Copie des 8 taux réels de rainbow_modulation_control (BTC, ETH) vers asset_base_rate si la ligne de l'actif n'existe pas déjà.
-- 2) Repli : si une ligne manque encore (base neuve, pas de ligne de modulation), insertion de la table de calage (valeurs initiales).
-- 3) Suppression des 8 colonnes base_rate_<btc|eth>_<profil> (base_rate_margin_k reste).
-- Idempotent (rejouable) : chaque étape gardée par information_schema ou NOT EXISTS (MySQL 8 / MariaDB 10.11).

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_full');
SET @s := IF(@c = 1, "INSERT INTO asset_base_rate (asset_symbol, rate_prudent, rate_balanced, rate_dynamic, rate_full, proposal_id, validated_by, validated_at) SELECT 'BTC', base_rate_btc_prudent, base_rate_btc_balanced, base_rate_btc_dynamic, base_rate_btc_full, NULL, 'migration V9', NOW(6) FROM rainbow_modulation_control WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM asset_base_rate WHERE asset_symbol = 'BTC')", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_full');
SET @s := IF(@c = 1, "INSERT INTO asset_base_rate (asset_symbol, rate_prudent, rate_balanced, rate_dynamic, rate_full, proposal_id, validated_by, validated_at) SELECT 'ETH', base_rate_eth_prudent, base_rate_eth_balanced, base_rate_eth_dynamic, base_rate_eth_full, NULL, 'migration V9', NOW(6) FROM rainbow_modulation_control WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM asset_base_rate WHERE asset_symbol = 'ETH')", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

INSERT INTO asset_base_rate (asset_symbol, rate_prudent, rate_balanced, rate_dynamic, rate_full, proposal_id, validated_by, validated_at)
SELECT 'BTC', 0.00027740, 0.00047360, 0.00077120, 0.00142300, NULL, 'migration V9', NOW(6) FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM asset_base_rate WHERE asset_symbol = 'BTC');

INSERT INTO asset_base_rate (asset_symbol, rate_prudent, rate_balanced, rate_dynamic, rate_full, proposal_id, validated_by, validated_at)
SELECT 'ETH', 0.00038040, 0.00063490, 0.00101900, 0.00153300, NULL, 'migration V9', NOW(6) FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM asset_base_rate WHERE asset_symbol = 'ETH');

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_prudent');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_btc_prudent", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_balanced');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_btc_balanced", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_dynamic');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_btc_dynamic", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_full');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_btc_full", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_prudent');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_eth_prudent", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_balanced');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_eth_balanced", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_dynamic');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_eth_dynamic", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_full');
SET @s := IF(@c = 1, "ALTER TABLE rainbow_modulation_control DROP COLUMN base_rate_eth_full", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
