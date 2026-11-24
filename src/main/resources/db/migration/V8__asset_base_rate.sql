-- Ligne de taux dediee par actif (D1b, lot 2) : taux de base par profil ecrits quand un admin valide une proposition.
-- La resolution des taux passe d'abord par cette ligne, puis par le repli BTC « non calibre » (aucune ligne = comportement inchange).
-- Aucun effet sur les ordres : proposition indicative d'onboarding uniquement. Idempotent (rejouable) : CREATE TABLE IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS asset_base_rate (
    id bigint NOT NULL AUTO_INCREMENT,
    asset_symbol varchar(16) NOT NULL,
    rate_prudent decimal(12,8) NOT NULL,
    rate_balanced decimal(12,8) NOT NULL,
    rate_dynamic decimal(12,8) NOT NULL,
    rate_full decimal(12,8) NOT NULL,
    proposal_id bigint,
    validated_by varchar(64) NOT NULL,
    validated_at datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_base_rate_asset (asset_symbol)
) ENGINE=InnoDB;
