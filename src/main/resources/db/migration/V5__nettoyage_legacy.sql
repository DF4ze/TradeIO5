-- Nettoyage des restes du modèle d'avant les Affectations (migrées en prod le 2026-11-07).
-- 1) colonnes preset_id de rainbow_live_run / rainbow_live_engine_state / rainbow_live_binding (FK, unique (preset_id, jour), colonne)
-- 2) tables rainbow_live_trend_config, rainbow_live_preset, rainbow_asset_strategy, rainbow_live_user_settings
-- 3) colonne rainbow_live_preset_event.strategy_revision
-- Idempotent (rejouable ; bases issues de V1 sans ces colonnes tolérées) : DDL conditionné par information_schema (MySQL 8 / MariaDB 10.11).

-- rainbow_live_run
SET @n := (SELECT constraint_name FROM information_schema.key_column_usage WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_run' AND column_name = 'preset_id' AND referenced_table_name IS NOT NULL LIMIT 1);
SET @s := IF(@n IS NULL, 'DO 0', CONCAT('ALTER TABLE rainbow_live_run DROP FOREIGN KEY ', @n));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_run' AND index_name = 'uk_rainbow_live_run_preset_day');
SET @s := IF(@c = 0, 'DO 0', 'ALTER TABLE rainbow_live_run DROP INDEX uk_rainbow_live_run_preset_day');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_run' AND column_name = 'preset_id');
SET @s := IF(@c = 0, 'DO 0', 'ALTER TABLE rainbow_live_run DROP COLUMN preset_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- rainbow_live_engine_state
SET @n := (SELECT constraint_name FROM information_schema.key_column_usage WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_engine_state' AND column_name = 'preset_id' AND referenced_table_name IS NOT NULL LIMIT 1);
SET @s := IF(@n IS NULL, 'DO 0', CONCAT('ALTER TABLE rainbow_live_engine_state DROP FOREIGN KEY ', @n));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_engine_state' AND index_name = 'uk_rainbow_live_engine_state_preset_day');
SET @s := IF(@c = 0, 'DO 0', 'ALTER TABLE rainbow_live_engine_state DROP INDEX uk_rainbow_live_engine_state_preset_day');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_engine_state' AND column_name = 'preset_id');
SET @s := IF(@c = 0, 'DO 0', 'ALTER TABLE rainbow_live_engine_state DROP COLUMN preset_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- rainbow_live_binding
SET @n := (SELECT constraint_name FROM information_schema.key_column_usage WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_binding' AND column_name = 'preset_id' AND referenced_table_name IS NOT NULL LIMIT 1);
SET @s := IF(@n IS NULL, 'DO 0', CONCAT('ALTER TABLE rainbow_live_binding DROP FOREIGN KEY ', @n));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_binding' AND column_name = 'preset_id');
SET @s := IF(@c = 0, 'DO 0', 'ALTER TABLE rainbow_live_binding DROP COLUMN preset_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- tables legacy (ordre des dépendances : trend_config -> preset -> asset_strategy)
DROP TABLE IF EXISTS rainbow_live_trend_config;
DROP TABLE IF EXISTS rainbow_live_preset;
DROP TABLE IF EXISTS rainbow_asset_strategy;
DROP TABLE IF EXISTS rainbow_live_user_settings;

-- rainbow_live_preset_event.strategy_revision
SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rainbow_live_preset_event' AND column_name = 'strategy_revision');
SET @s := IF(@c = 0, 'DO 0', 'ALTER TABLE rainbow_live_preset_event DROP COLUMN strategy_revision');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
