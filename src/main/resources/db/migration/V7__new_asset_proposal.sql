-- Proposition d'accueil d'un nouvel actif (D1b, lot 1) : preset de depart + taux de base par profil + drapeaux, en attente d'un admin.
-- Rien n'est applique par le calcul. Idempotent (rejouable) : CREATE TABLE IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS new_asset_proposal (
    id bigint NOT NULL AUTO_INCREMENT,
    asset_symbol varchar(16) NOT NULL,
    status enum ('PROPOSE','REFUSE','VALIDE') NOT NULL,
    preset_source varchar(16) NOT NULL,
    history_source varchar(64),
    candle_first_day date,
    candle_last_day date,
    candle_count integer,
    candle_checksum varchar(32),
    history_months integer,
    block_count integer,
    switch_count integer,
    flags varchar(255),
    margin_k decimal(10,4),
    levels_json varchar(4000),
    replays bigint,
    compute_ms bigint,
    warning varchar(500),
    requested_by varchar(64),
    created_at datetime(6) NOT NULL,
    decided_at datetime(6),
    decided_by varchar(64),
    PRIMARY KEY (id),
    KEY ix_new_asset_proposal_asset (asset_symbol, status)
) ENGINE=InnoDB;
