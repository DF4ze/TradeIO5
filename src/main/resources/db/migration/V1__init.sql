-- V1 : schéma initial, GÉNÉRÉ depuis les entités JPA (FlywaySchemaTest, -Dflyway.generate=true).
-- Ne pas éditer à la main avant l'activation de Flyway ; une fois appliquée, ne plus jamais la modifier
-- (toute évolution = nouvelle migration V<n>__*.sql, cf. docs/operations/flyway.md).

    create table api_credentials (
        enabled bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        user_id bigint not null,
        web_provider_id bigint not null,
        api_key varchar(255) not null,
        passphrase varchar(255),
        secret_key varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table asset (
        decimals integer not null,
        id bigint not null auto_increment,
        name varchar(255),
        symbol varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table asset_provider (
        enabled bit not null,
        max_horizon_days integer,
        priority integer not null,
        asset_id bigint not null,
        id bigint not null auto_increment,
        provider_symbol varchar(50) not null,
        source enum ('BINANCE','CHAINLINK','COINBASE','DATABASE','FILE','KRAKEN','MEMORY','OKX','SUSHISWAP','UNISWAP') not null,
        primary key (id)
    ) engine=InnoDB;

    create table candle (
        close decimal(30,10) not null,
        high decimal(30,10) not null,
        low decimal(30,10) not null,
        open decimal(30,10) not null,
        volume decimal(30,10) not null,
        id bigint not null auto_increment,
        timestamp datetime(6) not null,
        pair varchar(20) not null,
        source enum ('BINANCE','CHAINLINK','COINBASE','DATABASE','FILE','KRAKEN','MEMORY','OKX','SUSHISWAP','UNISWAP') not null,
        time_frame enum ('D1','H1','H12','H4','M1','M2','M3','M6','MIN1','MIN5','W1','W2','Y1','Y3') not null,
        primary key (id)
    ) engine=InnoDB;

    create table content_sources (
        active bit not null,
        credibility_weight float(53) not null,
        id bigint not null auto_increment,
        channel_id varchar(255) not null,
        display_name varchar(255),
        platform enum ('YOUTUBE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table decision_snapshots (
        snapshot_at datetime(6),
        decision_id varchar(255) not null,
        owner varchar(255),
        snapshot_json TEXT,
        status varchar(255),
        symbol varchar(255),
        type varchar(255),
        primary key (decision_id)
    ) engine=InnoDB;

    create table etf_flow_snapshot (
        date date not null,
        total_net_inflow float(53) not null,
        fetched_at datetime(6) not null,
        id bigint not null auto_increment,
        asset enum ('BTC','ETH') not null,
        primary key (id)
    ) engine=InnoDB;

    create table events (
        timestamp datetime(6),
        id varchar(255) not null,
        target_id varchar(255),
        payload longtext,
        type enum ('DECISION','OPINION','SCENARIO'),
        primary key (id)
    ) engine=InnoDB;

    create table indicator_parameter (
        boolean_value bit,
        numeric_value float(53),
        id bigint not null auto_increment,
        parameter_set_id bigint not null,
        param_key varchar(50) not null,
        string_value varchar(100),
        type enum ('BOOLEAN','NUMERIC','STRING') not null,
        primary key (id)
    ) engine=InnoDB;

    create table indicator_parameter_set (
        enabled bit not null,
        version integer not null,
        id bigint not null auto_increment,
        name varchar(100) not null,
        description varchar(255),
        indicator_code enum ('ADX','ATR','BOLLINGER','DXY','EMA','ETF_FLOW','FEAR_GREED','FUNDING_RATE','LINEAR_REGRESSION','LIQUIDATIONS','MACD','NASDAQ','OBV','OPEN_INTEREST','ORDER_BOOK','RAINBOW','RAINBOW_ATR','REJECTION_ZONE','RSI','SMA','SP500','STABLECOIN_MARKET_CAP','SWING_STRUCTURE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table llm_call_logs (
        id bigint not null auto_increment,
        input_tokens bigint,
        occurred_at datetime(6),
        output_tokens bigint,
        total_tokens bigint,
        call_site varchar(255),
        model varchar(255),
        tier enum ('HIGH','LOW','MEDIUM'),
        primary key (id)
    ) engine=InnoDB;

    create table media_claims (
        confidence float(53) not null,
        id bigint not null auto_increment,
        video_content_id bigint not null,
        excerpt TEXT,
        symbol varchar(255) not null,
        horizon enum ('COURT_TERME','LONG_TERME','MOYEN_TERME') not null,
        sentiment enum ('BEARISH','BULLISH','NEUTRAL') not null,
        primary key (id)
    ) engine=InnoDB;

    create table provider (
        enabled bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        name varchar(100) not null,
        api_base_url varchar(255),
        code enum ('BINANCE','BINANCE_TESTNET','COINALYZE','COINSTATS','DEFILLAMA','FARSIDE','FINNHUB','FOREXFACTORY','KRAKEN','LEDGER','METAMASK','OKX','SOSOVALUE','TWELVE_DATA','YAHOO_FINANCE','YOUTUBE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_asset_strategy (
        allow_sell_during_cooldown bit,
        analysis_window_months integer not null,
        ath_buy_max float(53),
        ath_buy_min float(53),
        ath_on bit,
        ath_ref_dd_buy_pct float(53),
        ath_ref_dd_sell_pct float(53),
        ath_sell_max float(53),
        ath_sell_min float(53),
        atr_mult_down1 float(53),
        atr_mult_down2 float(53),
        atr_mult_up1 float(53),
        atr_mult_up2 float(53),
        atr_mult_up3 float(53),
        atr_period integer,
        base_amount float(53),
        block_buy_after_sell_until_down2 bit,
        cooldown_after_sell_on bit,
        cooldown_days integer,
        fixed_delay_days integer,
        initial_capital_usdc float(53) not null,
        moon_on bit,
        moon_reserve_pct float(53),
        moon_reserve_ratchet bit,
        moon_stop_sell_pct float(53),
        moon_trailing_stop_pct float(53),
        mult_triggered float(53),
        multx0_5 float(53),
        multx1 float(53),
        multx2 float(53),
        revision integer not null,
        sell_fraction float(53),
        sma_period integer,
        trailing_stop_buy_pct float(53),
        trailing_stop_sell_pct float(53),
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6) not null,
        asset_symbol varchar(16) not null,
        name varchar(64) not null,
        buy_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        mode enum ('FIXED','TREND_MIX') not null,
        sell_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        trend_config_json longtext,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_ath_reference (
        ath_value float(53) not null,
        ref_day date not null,
        ath_time_millis bigint not null,
        id bigint not null auto_increment,
        asset_symbol varchar(16) not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_binding (
        bag_percent float(53) not null,
        priority integer not null,
        id bigint not null auto_increment,
        preset_id bigint not null,
        user_id bigint not null,
        wallet_id bigint not null,
        asset_symbol varchar(16) not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_engine_state (
        buy_armed bit not null,
        buy_armed_days integer not null,
        buy_locked bit not null,
        cooldown integer not null,
        highest_since_armed float(53),
        lowest_since_armed float(53),
        moon_active bit not null,
        moon_peak float(53),
        reserve_qty float(53) not null,
        sell_armed bit not null,
        sell_armed_days integer not null,
        state_day date not null,
        id bigint not null auto_increment,
        preset_id bigint not null,
        config_hash varchar(64),
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_mock_wallet (
        cash_usdc float(53) not null,
        position_quantity float(53) not null,
        id bigint not null auto_increment,
        preset_id bigint not null,
        updated_at datetime(6) not null,
        asset_symbol varchar(16) not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_preset (
        allow_sell_during_cooldown bit,
        analysis_window_months integer not null,
        ath_buy_max float(53),
        ath_buy_min float(53),
        ath_on bit,
        ath_ref_dd_buy_pct float(53),
        ath_ref_dd_sell_pct float(53),
        ath_sell_max float(53),
        ath_sell_min float(53),
        atr_mult_down1 float(53),
        atr_mult_down2 float(53),
        atr_mult_up1 float(53),
        atr_mult_up2 float(53),
        atr_mult_up3 float(53),
        atr_period integer,
        base_amount float(53),
        block_buy_after_sell_until_down2 bit,
        cooldown_after_sell_on bit,
        cooldown_days integer,
        enabled bit not null,
        fixed_delay_days integer,
        initial_capital_usdc float(53) not null,
        moon_on bit,
        moon_reserve_pct float(53),
        moon_reserve_ratchet bit,
        moon_stop_sell_pct float(53),
        moon_trailing_stop_pct float(53),
        mult_triggered float(53),
        multx0_5 float(53),
        multx1 float(53),
        multx2 float(53),
        sell_fraction float(53),
        sma_period integer,
        trailing_stop_buy_pct float(53),
        trailing_stop_sell_pct float(53),
        asset_strategy_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6) not null,
        user_id bigint not null,
        asset_symbol varchar(16) not null,
        name varchar(64) not null,
        buy_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        mode enum ('FIXED','TREND_MIX'),
        sell_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_preset_event (
        strategy_revision integer,
        id bigint not null auto_increment,
        occurred_at datetime(6) not null,
        preset_after_id bigint,
        preset_before_id bigint,
        user_id bigint not null,
        asset_symbol varchar(16) not null,
        preset_after_name varchar(96),
        preset_before_name varchar(96),
        reason varchar(255),
        type enum ('DETACH','DISABLE','ENABLE','LIVE_SWITCH','STRATEGY_CHANGED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_run (
        allow_sell_during_cooldown bit,
        ath_buy_max float(53),
        ath_buy_min float(53),
        ath_on bit,
        ath_ref_dd_buy_pct float(53),
        ath_ref_dd_sell_pct float(53),
        ath_sell_max float(53),
        ath_sell_min float(53),
        atr_mult_down1 float(53),
        atr_mult_down2 float(53),
        atr_mult_up1 float(53),
        atr_mult_up2 float(53),
        atr_mult_up3 float(53),
        atr_period integer,
        base_amount float(53),
        block_buy_after_sell_until_down2 bit,
        cooldown_after_sell_on bit,
        cooldown_days integer,
        fixed_delay_days integer,
        moon_on bit,
        moon_reserve_pct float(53),
        moon_reserve_ratchet bit,
        moon_stop_sell_pct float(53),
        moon_trailing_stop_pct float(53),
        mult_triggered float(53),
        multx0_5 float(53),
        multx1 float(53),
        multx2 float(53),
        run_day date not null,
        sell_fraction float(53),
        sma_period integer,
        t0005_action_amount_usdc float(53),
        t0005_action_price float(53),
        t0005_action_quantity float(53),
        t0005_ath_distance float(53),
        t0005_atr float(53),
        t0005_bound_down1 float(53),
        t0005_bound_down2 float(53),
        t0005_bound_up1 float(53),
        t0005_bound_up2 float(53),
        t0005_bound_up3 float(53),
        t0005_buy_armed bit,
        t0005_buy_factor float(53),
        t0005_buy_locked bit,
        t0005_cash_after float(53),
        t0005_close float(53),
        t0005_cooldown_remaining integer,
        t0005_live_action_amount_usdc float(53),
        t0005_live_action_quantity float(53),
        t0005_live_cash_reserved float(53),
        t0005_live_cash_usdc float(53),
        t0005_live_position_qty float(53),
        t0005_live_tradable_qty float(53),
        t0005_moon_mode bit,
        t0005_moon_reserve_qty float(53),
        t0005_position_after float(53),
        t0005_sell_armed bit,
        t0005_sell_factor float(53),
        t0005_sma float(53),
        t0005_zone integer,
        t2355_action_amount_usdc float(53),
        t2355_action_price float(53),
        t2355_action_quantity float(53),
        t2355_ath_distance float(53),
        t2355_atr float(53),
        t2355_bound_down1 float(53),
        t2355_bound_down2 float(53),
        t2355_bound_up1 float(53),
        t2355_bound_up2 float(53),
        t2355_bound_up3 float(53),
        t2355_buy_armed bit,
        t2355_buy_factor float(53),
        t2355_buy_locked bit,
        t2355_cash_after float(53),
        t2355_close float(53),
        t2355_cooldown_remaining integer,
        t2355_live_action_amount_usdc float(53),
        t2355_live_action_quantity float(53),
        t2355_live_cash_reserved float(53),
        t2355_live_cash_usdc float(53),
        t2355_live_position_qty float(53),
        t2355_live_tradable_qty float(53),
        t2355_moon_mode bit,
        t2355_moon_reserve_qty float(53),
        t2355_position_after float(53),
        t2355_sell_armed bit,
        t2355_sell_factor float(53),
        t2355_sma float(53),
        t2355_zone integer,
        trailing_stop_buy_pct float(53),
        trailing_stop_sell_pct float(53),
        id bigint not null auto_increment,
        preset_id bigint not null,
        t0005_computed_at datetime(6),
        t0005_live_fetched_at datetime(6),
        t0005_live_wallet_id bigint,
        t2355_computed_at datetime(6),
        t2355_live_fetched_at datetime(6),
        t2355_live_wallet_id bigint,
        user_id bigint not null,
        asset_symbol varchar(16) not null,
        config_hash varchar(32),
        t0005_active_set varchar(255),
        t0005_config_hash varchar(255),
        t0005_trend_regime varchar(255),
        t2355_active_set varchar(255),
        t2355_config_hash varchar(255),
        t2355_trend_regime varchar(255),
        buy_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        sell_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        t0005_action_type enum ('BLOCKED','BUY','NONE','SELL'),
        t0005_live_action_type enum ('BLOCKED','BUY','NONE','SELL'),
        t0005_live_block_reason enum ('INSUFFICIENT_CASH','NONE','UNAVAILABLE'),
        t0005_live_status enum ('OK','STALE','UNAVAILABLE'),
        t2355_action_type enum ('BLOCKED','BUY','NONE','SELL'),
        t2355_live_action_type enum ('BLOCKED','BUY','NONE','SELL'),
        t2355_live_block_reason enum ('INSUFFICIENT_CASH','NONE','UNAVAILABLE'),
        t2355_live_status enum ('OK','STALE','UNAVAILABLE'),
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_trend_config (
        atr_multiplier float(53) not null,
        atr_period integer not null,
        confirm_days integer not null,
        enter_threshold float(53) not null,
        exit_threshold float(53) not null,
        long_window integer not null,
        medium_window integer not null,
        short_window integer not null,
        slope_scale float(53) not null,
        sma_period integer not null,
        wick_down bit not null,
        wick_up bit not null,
        id bigint not null auto_increment,
        preset_id bigint not null,
        range_mapping varchar(16) not null,
        bear_json varchar(8000) not null,
        bull_json varchar(8000) not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_user_settings (
        id bigint not null auto_increment,
        user_id bigint not null,
        ui_mode enum ('ADVANCED','AUTO','EXPERT','SIMPLE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table roles (
        id integer not null auto_increment,
        name enum ('ROLE_ADMIN','ROLE_MODERATOR','ROLE_SYS','ROLE_USER'),
        primary key (id)
    ) engine=InnoDB;

    create table scenario_events (
        id bigint not null auto_increment,
        occurred_at datetime(6),
        after_json TEXT,
        before_json TEXT,
        cause_json TEXT,
        event_type varchar(255),
        owner varchar(255),
        scenario_id varchar(255),
        scenario_type varchar(255),
        symbol varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table scenario_snapshots (
        snapshot_at datetime(6),
        owner varchar(255),
        scenario_id varchar(255) not null,
        scenario_type varchar(255),
        scope varchar(255),
        state_json TEXT,
        symbol varchar(255),
        primary key (scenario_id)
    ) engine=InnoDB;

    create table transaction (
        fee decimal(30,10),
        price decimal(30,10) not null,
        quantity decimal(30,10) not null,
        id bigint not null auto_increment,
        timestamp datetime(6) not null,
        user_id bigint not null,
        wallet_id bigint not null,
        web_provider_id bigint not null,
        asset varchar(20) not null,
        external_transaction_id varchar(255) not null,
        trade_side enum ('BUY','SELL') not null,
        primary key (id)
    ) engine=InnoDB;

    create table user_roles (
        role_id integer not null,
        user_id bigint not null,
        primary key (role_id, user_id)
    ) engine=InnoDB;

    create table user_trading_settings (
        risk_cursor integer not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table users (
        enabled bit not null,
        archived_at datetime(6),
        id bigint not null auto_increment,
        last_login datetime(6),
        username varchar(20) not null,
        email varchar(50) not null,
        password varchar(120) not null,
        primary key (id)
    ) engine=InnoDB;

    create table video_contents (
        id bigint not null auto_increment,
        published_at datetime(6),
        source_id bigint not null,
        error_reason varchar(255),
        title varchar(255),
        transcript LONGTEXT,
        video_id varchar(255) not null,
        status enum ('ERROR','IRRELEVANT','PENDING','PROCESSED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table wallet (
        enabled bit not null,
        creation_date datetime(6) not null,
        credential_id bigint,
        id bigint not null auto_increment,
        user_id bigint not null,
        web_provider_id bigint,
        name varchar(100) not null,
        description varchar(255),
        source enum ('BANK_ACCOUNT','EXCHANGE','NON_CUSTODIAL','OTHER') not null,
        web_provider_code enum ('BINANCE','BINANCE_TESTNET','COINALYZE','COINSTATS','DEFILLAMA','FARSIDE','FINNHUB','FOREXFACTORY','KRAKEN','LEDGER','METAMASK','OKX','SOSOVALUE','TWELVE_DATA','YAHOO_FINANCE','YOUTUBE'),
        primary key (id)
    ) engine=InnoDB;

    alter table api_credentials 
       add constraint uk_credential_user_provider unique (user_id, web_provider_id);

    alter table asset_provider 
       add constraint uk_asset_provider_asset_source unique (asset_id, source);

    alter table candle 
       add constraint uk_candle_source_pair_tf_ts unique (source, pair, time_frame, timestamp);

    alter table content_sources 
       add constraint uk_content_source_channel_id unique (channel_id);

    alter table etf_flow_snapshot 
       add constraint uk_etf_flow_snapshot_asset_date unique (asset, date);

    alter table indicator_parameter 
       add constraint uk_indicatorparam_param_key unique (parameter_set_id, param_key);

    alter table indicator_parameter_set 
       add constraint uk_indicatorparamset_code_name unique (indicator_code, name);

    alter table provider 
       add constraint uk_provider_code unique (code);

    alter table rainbow_asset_strategy 
       add constraint uk_rainbow_asset_strategy_asset_name unique (asset_symbol, name);

    alter table rainbow_ath_reference 
       add constraint uk_rainbow_ath_reference_asset_day unique (asset_symbol, ref_day);

    alter table rainbow_live_binding 
       add constraint uk_rainbow_live_binding_user_asset unique (user_id, asset_symbol);

    alter table rainbow_live_engine_state 
       add constraint uk_rainbow_live_engine_state_preset_day unique (preset_id, state_day);

    alter table rainbow_live_mock_wallet 
       add constraint UKeapf4gshqevtl6bbfi7xjyo4y unique (preset_id);

    alter table rainbow_live_preset 
       add constraint uk_rainbow_live_preset_user_asset_name unique (user_id, asset_symbol, name);

    create index idx_rainbow_live_preset_event_user_at 
       on rainbow_live_preset_event (user_id, occurred_at);

    alter table rainbow_live_run 
       add constraint uk_rainbow_live_run_preset_day unique (preset_id, run_day);

    alter table rainbow_live_trend_config 
       add constraint UKmiihysxofj591fc5w2lhcg065 unique (preset_id);

    alter table rainbow_live_user_settings 
       add constraint UKlwi4qntawy99jdqhlml7tsx8c unique (user_id);

    alter table transaction 
       add constraint uk_transaction_ext_id unique (external_transaction_id);

    alter table user_trading_settings 
       add constraint uk_trading_settings_user unique (user_id);

    alter table users 
       add constraint UKr43af9ap4edm43mmtq01oddj6 unique (username);

    alter table users 
       add constraint UK6dotkott2kjsp8vw4d0m25fb7 unique (email);

    alter table video_contents 
       add constraint uk_video_content_source_video unique (source_id, video_id);

    alter table wallet 
       add constraint uk_wallet_user_name unique (user_id, name);

    alter table api_credentials 
       add constraint FKjwkyyts8ew7sc57q19dlyg1o 
       foreign key (user_id) 
       references users (id);

    alter table api_credentials 
       add constraint FK4qlsd3pydcrcrb0guekt128hu 
       foreign key (web_provider_id) 
       references provider (id);

    alter table asset_provider 
       add constraint FK89koxbjt634ic88piqwirgan8 
       foreign key (asset_id) 
       references asset (id);

    alter table indicator_parameter 
       add constraint FK8ytrcccrjxlf5qdbekdvxbt7g 
       foreign key (parameter_set_id) 
       references indicator_parameter_set (id);

    alter table media_claims 
       add constraint FKaawwq1abtmd8sk0yq7cnpubo5 
       foreign key (video_content_id) 
       references video_contents (id);

    alter table rainbow_live_binding 
       add constraint FK8tc0bup9xpf2lbwwkl252gak3 
       foreign key (preset_id) 
       references rainbow_live_preset (id);

    alter table rainbow_live_binding 
       add constraint FKswhsbufw25y92hiq33ulgo4bj 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_binding 
       add constraint FKd6ymsdvc4qgdfq1koso6b4jh6 
       foreign key (wallet_id) 
       references wallet (id);

    alter table rainbow_live_engine_state 
       add constraint FKhnwwgjks321auqn1xmejlwig8 
       foreign key (preset_id) 
       references rainbow_live_preset (id) 
       on delete cascade;

    alter table rainbow_live_mock_wallet 
       add constraint FKkp15qju5trcln3hg8e10apnyo 
       foreign key (preset_id) 
       references rainbow_live_preset (id) 
       on delete cascade;

    alter table rainbow_live_preset 
       add constraint FK26tkmy3l90xeh8q10ngdv2swc 
       foreign key (asset_strategy_id) 
       references rainbow_asset_strategy (id);

    alter table rainbow_live_preset 
       add constraint FKjv3p5rldd6g78g1ci1t74xxwg 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_preset_event 
       add constraint FKnkvptgo9daedft0qc978qak0e 
       foreign key (user_id) 
       references users (id) 
       on delete cascade;

    alter table rainbow_live_run 
       add constraint FK3xlk4cprs7y5q8p54olaqoka7 
       foreign key (preset_id) 
       references rainbow_live_preset (id) 
       on delete cascade;

    alter table rainbow_live_run 
       add constraint FKoluxak4rl3tb78vj2ir0fmqpr 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_trend_config 
       add constraint FK602chppbbt916lr585rgjftbt 
       foreign key (preset_id) 
       references rainbow_live_preset (id) 
       on delete cascade;

    alter table rainbow_live_user_settings 
       add constraint FK990isfmo58t5enul369j9q03h 
       foreign key (user_id) 
       references users (id);

    alter table transaction 
       add constraint FKanjpo5tiapru7an6cw4cu37y4 
       foreign key (user_id) 
       references users (id);

    alter table transaction 
       add constraint FKtfwlfspv2h4wcgc9rjd1658a6 
       foreign key (wallet_id) 
       references wallet (id);

    alter table transaction 
       add constraint FKbr10oimccm0pjrlxxbsj1nyag 
       foreign key (web_provider_id) 
       references provider (id);

    alter table user_roles 
       add constraint FKh8ciramu9cc9q3qcqiv4ue8a6 
       foreign key (role_id) 
       references roles (id);

    alter table user_roles 
       add constraint FKhfh9dx7w3ubf1co1vdev94g3f 
       foreign key (user_id) 
       references users (id);

    alter table user_trading_settings 
       add constraint FKobuo1tadcptigpr48s6nu2tpw 
       foreign key (user_id) 
       references users (id);

    alter table video_contents 
       add constraint FK7bb8wqi86f2tujd5v4s3lybgr 
       foreign key (source_id) 
       references content_sources (id);

    alter table wallet 
       add constraint FKlkvr5yu8l8und64l35kmabpye 
       foreign key (credential_id) 
       references api_credentials (id);

    alter table wallet 
       add constraint FKgbusavqq0bdaodex4ee6v0811 
       foreign key (user_id) 
       references users (id);

    alter table wallet 
       add constraint FKc4o7765yg1uvliq9gr8x7bu2k 
       foreign key (web_provider_id) 
       references provider (id);
