-- Plafonds de drawdown du wallet par profil de départ (signalement du simulateur) : 4 colonnes sur rainbow_modulation_control.
-- La ligne existante reçoit les défauts (Prudent -15, Équilibré -25, Dynamique -40, Full -60).
-- Idempotent (rejouable) : DDL conditionné par information_schema (MySQL 8 / MariaDB 10.11).

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'drawdown_cap_prudent_pct');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN drawdown_cap_prudent_pct decimal(10,4) NOT NULL DEFAULT -15.0000", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'drawdown_cap_balanced_pct');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN drawdown_cap_balanced_pct decimal(10,4) NOT NULL DEFAULT -25.0000", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'drawdown_cap_dynamic_pct');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN drawdown_cap_dynamic_pct decimal(10,4) NOT NULL DEFAULT -40.0000", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_modulation_control' AND column_name = 'drawdown_cap_full_pct');
SET @s := IF(@c = 0, "ALTER TABLE rainbow_modulation_control ADD COLUMN drawdown_cap_full_pct decimal(10,4) NOT NULL DEFAULT -60.0000", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
