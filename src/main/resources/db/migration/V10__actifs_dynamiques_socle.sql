-- V10 : socle des actifs dynamiques (lot A). Aucun changement de comportement visible.
-- 1) asset_blocklist : liste noire admin (un actif bloque = aucun nouveau lien).
-- 2) asset_eligibility_settings : reglage admin unique (volume 24 h minimum, USD). La ligne est creee par l'initializer.
-- 3) exchange_instrument : volume 24 h (en monnaie de cotation) + date de lecture.
-- 4) asset_base_rate : ligne PAXG = copie des 4 taux de la ligne BTC (execution reelle actuelle conservee), si absente et si BTC existe.
-- Idempotent (rejouable) : CREATE TABLE IF NOT EXISTS, information_schema + PREPARE/EXECUTE, NOT EXISTS (MySQL 8 / MariaDB 10.11).

CREATE TABLE IF NOT EXISTS asset_blocklist (
    id bigint NOT NULL AUTO_INCREMENT,
    asset_symbol varchar(16) NOT NULL,
    reason varchar(255),
    created_by varchar(64) NOT NULL,
    created_at datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_blocklist_asset (asset_symbol)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS asset_eligibility_settings (
    id bigint NOT NULL,
    min_volume_usd decimal(20,2) NOT NULL,
    updated_by varchar(64),
    updated_at datetime(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'exchange_instrument' AND column_name = 'volume_24h_quote');
SET @s := IF(@c = 0, "ALTER TABLE exchange_instrument ADD COLUMN volume_24h_quote decimal(38,8) NULL", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'exchange_instrument' AND column_name = 'volume_read_at');
SET @s := IF(@c = 0, "ALTER TABLE exchange_instrument ADD COLUMN volume_read_at datetime(6) NULL", 'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

INSERT INTO asset_base_rate (asset_symbol, rate_prudent, rate_balanced, rate_dynamic, rate_full, proposal_id, validated_by, validated_at)
SELECT 'PAXG', b.rate_prudent, b.rate_balanced, b.rate_dynamic, b.rate_full, NULL, 'migration V10', NOW(6)
FROM asset_base_rate b
WHERE b.asset_symbol = 'BTC' AND NOT EXISTS (SELECT 1 FROM asset_base_rate p WHERE p.asset_symbol = 'PAXG');
