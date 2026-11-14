-- Cache global des frais réels du compte (durée de vie 24 h, TradingFeeService). Aucune donnée secrète.
-- Idempotent (rejouable) : CREATE TABLE IF NOT EXISTS ; lignes supprimées avec le credential.

CREATE TABLE IF NOT EXISTS trading_fee_cache (
    id bigint NOT NULL AUTO_INCREMENT,
    credential_id bigint NOT NULL,
    inst_key varchar(64) NOT NULL,
    maker_pct decimal(19,8) NOT NULL,
    taker_pct decimal(19,8) NOT NULL,
    fetched_at datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trading_fee_cache_cred_inst (credential_id, inst_key),
    CONSTRAINT fk_trading_fee_cache_credential FOREIGN KEY (credential_id) REFERENCES api_credentials (id) ON DELETE CASCADE
) ENGINE=InnoDB;
