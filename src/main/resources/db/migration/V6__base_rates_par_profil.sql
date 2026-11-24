-- Taux de base par profil (BTC / ETH) et marge k du calage, éditables en admin : 9 colonnes sur rainbow_modulation_control.
-- Proposition INDICATIVE d'onboarding (taux x cash alloué au lien), aucun effet sur les ordres. Source : docs/decisions/full-autonome-v1.md.
-- La ligne existante reçoit les défauts (table de calage k = 1,1, bande neutre).
-- Idempotent (rejouable) : DDL conditionné par information_schema (MySQL 8 / MariaDB 10.11).

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_prudent');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_btc_prudent decimal(12,8) NOT NULL DEFAULT 0.00027740", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_balanced');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_btc_balanced decimal(12,8) NOT NULL DEFAULT 0.00047360", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_dynamic');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_btc_dynamic decimal(12,8) NOT NULL DEFAULT 0.00077120", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_btc_full');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_btc_full decimal(12,8) NOT NULL DEFAULT 0.00142300", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_prudent');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_eth_prudent decimal(12,8) NOT NULL DEFAULT 0.00038040", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_balanced');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_eth_balanced decimal(12,8) NOT NULL DEFAULT 0.00063490", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_dynamic');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_eth_dynamic decimal(12,8) NOT NULL DEFAULT 0.00101900", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_eth_full');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_eth_full decimal(12,8) NOT NULL DEFAULT 0.00153300", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'base_rate_margin_k');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN base_rate_margin_k decimal(10,4) NOT NULL DEFAULT 1.1000", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
