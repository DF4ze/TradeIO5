-- V1 : schéma initial, GÉNÉRÉ depuis les entités JPA (FlywaySchemaTest, -Dflyway.generate=true).
-- Ne pas éditer à la main avant l'activation de Flyway ; une fois appliquée, ne plus jamais la modifier
-- (toute évolution = nouvelle migration V<n>__*.sql, cf. docs/operations/flyway.md).

    create table admin_action_log (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        actor varchar(64) not null,
        target varchar(120),
        payload varchar(1000),
        action enum ('ARM_BINDING','AUTO_REENGAGE','BULK','CREDENTIAL_ENABLED','CREDENTIAL_STORED','DOCTOR','KILL_SWITCH','LIMITS','MODULATION','ORDER_READ','ORDER_SEND','ORDER_VALIDATE','PLAN','PRESET','PROVIDER','RECONCILE','RUN') not null,
        outcome enum ('ERROR','OK','REFUSED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table api_credentials (
        enabled bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        user_id bigint not null,
        web_provider_id bigint not null,
        api_key varchar(2048) not null,
        passphrase varchar(2048),
        secret_key varchar(2048),
        scope enum ('READ','TRADE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table asset (
        decimals integer not null,
        id bigint not null auto_increment,
        name varchar(255),
        symbol varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table asset_affectation (
        system_instance bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        rainbow_down_id bigint,
        rainbow_up_id bigint,
        trend_preset_id bigint,
        updated_at datetime(6) not null,
        user_id bigint not null,
        asset_symbol varchar(16) not null,
        name varchar(64),
        primary key (id)
    ) engine=InnoDB;

    create table asset_group (
        id bigint not null auto_increment,
        code varchar(16) not null,
        name varchar(64) not null,
        valuation enum ('NOMINAL') not null,
        primary key (id)
    ) engine=InnoDB;

    create table asset_group_member (
        position integer not null,
        asset_id bigint not null,
        group_id bigint not null,
        id bigint not null auto_increment,
        primary key (id)
    ) engine=InnoDB;

    create table asset_provider (
        enabled bit not null,
        max_horizon_days integer,
        priority integer not null,
        asset_id bigint not null,
        id bigint not null auto_increment,
        provider_symbol varchar(50) not null,
        source enum ('BINANCE','CHAINLINK','COINBASE','DATABASE','FILE','KRAKEN','MEMORY','OKX','REVOLUT_X','SUSHISWAP','UNISWAP') not null,
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
        source enum ('BINANCE','CHAINLINK','COINBASE','DATABASE','FILE','KRAKEN','MEMORY','OKX','REVOLUT_X','SUSHISWAP','UNISWAP') not null,
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

    create table doctor_alarm (
        occurrences integer not null,
        acked_at datetime(6),
        first_seen datetime(6) not null,
        id bigint not null auto_increment,
        last_seen datetime(6) not null,
        resolved_at datetime(6),
        snooze_until datetime(6),
        check_id varchar(48) not null,
        acked_by varchar(64),
        subject varchar(140) not null,
        alarm_key varchar(200) not null,
        ack_comment varchar(300),
        message varchar(500),
        family enum ('DATA','EXECUTION','INFRA','LOGS','PLANNING') not null,
        impact enum ('EXECUTION','INFO','QUALITY') not null,
        severity enum ('CRIT','OK','UNKNOWN','WARN') not null,
        state enum ('ACKED','ACTIVE','RESOLVED') not null,
        primary key (id)
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

    create table exchange_instrument (
        cost_min decimal(38,18),
        lot_sz decimal(38,18) not null,
        min_sz decimal(38,18) not null,
        tick_sz decimal(38,18) not null,
        fetched_at datetime(6) not null,
        id bigint not null auto_increment,
        base varchar(20) not null,
        fee_currency varchar(20),
        quote varchar(20) not null,
        state varchar(20) not null,
        inst_id varchar(40) not null,
        native_pair varchar(40),
        provider enum ('BINANCE','BINANCE_TESTNET','BITVAVO','COINALYZE','COINSTATS','DEFILLAMA','FARSIDE','FINNHUB','FOREXFACTORY','GATE_EU','KRAKEN','LEDGER','METAMASK','OKX','ONE_TRADING','REVOLUT_X','SOSOVALUE','TWELVE_DATA','YAHOO_FINANCE','YOUTUBE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table execution_control (
        absolute_max_day_usd decimal(19,4),
        absolute_max_order_usd decimal(19,4),
        fee_red_pct decimal(10,4),
        fee_warn_pct decimal(10,4),
        kill_switch bit not null,
        max_day_multiplier decimal(10,4) not null,
        max_order_multiplier decimal(10,4) not null,
        id bigint not null,
        updated_at datetime(6) not null,
        updated_by varchar(64),
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

    create table job_run (
        errors integer not null,
        duration_ms bigint not null,
        id bigint not null auto_increment,
        started_at datetime(6) not null,
        job_name varchar(64) not null,
        error_message varchar(500),
        summary varchar(500),
        status enum ('FAILED','OK','PARTIAL') not null,
        triggered_by enum ('CRON','MANUAL') not null,
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

    create table portfolio_snapshot (
        assets_value float(53),
        cash_usd float(53),
        exposure_max float(53),
        exposure_min float(53),
        exposure_pct float(53),
        snapshot_day date not null,
        total_value float(53),
        fetched_at datetime(6),
        id bigint not null auto_increment,
        recorded_at datetime(6) not null,
        user_id bigint not null,
        wallet_id bigint not null,
        price_basis enum ('D1_OPEN_2355') not null,
        status enum ('NOT_TRADABLE','OK','UNAVAILABLE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table portfolio_snapshot_asset (
        asset_value float(53),
        price float(53),
        quantity float(53) not null,
        id bigint not null auto_increment,
        snapshot_id bigint not null,
        asset_symbol varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

    create table provider (
        enabled bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        name varchar(100) not null,
        api_base_url varchar(255),
        code enum ('BINANCE','BINANCE_TESTNET','BITVAVO','COINALYZE','COINSTATS','DEFILLAMA','FARSIDE','FINNHUB','FOREXFACTORY','GATE_EU','KRAKEN','LEDGER','METAMASK','OKX','ONE_TRADING','REVOLUT_X','SOSOVALUE','TWELVE_DATA','YAHOO_FINANCE','YOUTUBE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table provider_pending_key (
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        algorithm varchar(32) not null,
        provider_code varchar(32) not null,
        fingerprint varchar(64) not null,
        public_key_pem varchar(2048) not null,
        private_key_enc varchar(8192) not null,
        scope enum ('READ','TRADE') not null,
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
        base_amount float(53),
        execution_enabled bit not null,
        fee_test_amount decimal(24,8),
        fee_test_pct decimal(12,6),
        min_buy_usd float(53),
        priority integer not null,
        simulation bit not null,
        affectation_id bigint,
        archived_at datetime(6),
        fee_test_at datetime(6),
        first_live_approved_at datetime(6),
        first_live_confirmed_at datetime(6),
        id bigint not null auto_increment,
        tradability_checked_at datetime(6),
        user_id bigint not null,
        wallet_id bigint not null,
        asset_symbol varchar(16) not null,
        quote_member varchar(16),
        fee_test_route varchar(64),
        preferred_route varchar(64),
        route_key varchar(64),
        fee_test_level enum ('GREEN','RED','WARNING'),
        tradability enum ('BALANCE_UNAVAILABLE','CREDENTIAL_INVALID','INSTRUMENT_UNAVAILABLE','NOT_TRADABLE_WITHOUT_FIAT','OK','PROVIDER_UNSUPPORTED','TRADABLE_VIA_INTERMEDIARY','WALLET_DISABLED'),
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_binding_run (
        live_action_amount_usdc float(53),
        live_action_quantity float(53),
        live_candle_substituted bit,
        live_cash_reserved float(53),
        live_cash_usd float(53),
        live_position_qty float(53),
        live_tradable_qty float(53),
        run_day date not null,
        binding_id bigint not null,
        computed_at datetime(6),
        id bigint not null auto_increment,
        live_fetched_at datetime(6),
        live_wallet_id bigint,
        live_modulation varchar(2000),
        live_cash_detail varchar(255),
        live_action_type enum ('BLOCKED','BUY','NONE','SELL'),
        live_block_reason enum ('BAND_LIMIT','INSUFFICIENT_CASH','NONE','NOT_TRADABLE_WITHOUT_FIAT','UNAVAILABLE'),
        live_candle_source enum ('BINANCE','CHAINLINK','COINBASE','DATABASE','FILE','KRAKEN','MEMORY','OKX','REVOLUT_X','SUSHISWAP','UNISWAP'),
        live_status enum ('NOT_TRADABLE','OK','UNAVAILABLE'),
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
        position_qty float(53) not null,
        reserve_qty float(53) not null,
        sell_armed bit not null,
        sell_armed_days integer not null,
        state_day date not null,
        affectation_id bigint,
        id bigint not null auto_increment,
        config_hash varchar(64),
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_order_event (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        plan_id bigint not null,
        step_id bigint,
        cl_ord_id varchar(32),
        payload varchar(1000),
        type enum ('ALERT','BLOCKED','PARTIAL_REMAINDER','PLAN_RESULT','PLAN_STARTED','STEP_ACK','STEP_RECONCILED','STEP_REQUOTED','STEP_RESULT','STEP_SKIPPED','STEP_SUBMITTING','STEP_UNKNOWN') not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_order_plan (
        revision integer not null,
        run_day date not null,
        total_cost_pct decimal(12,6),
        created_at datetime(6) not null,
        execution_finished_at datetime(6),
        execution_started_at datetime(6),
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        inputs_hash varchar(64) not null,
        execution_block_reason varchar(120),
        block_reason varchar(255),
        fee_test_level enum ('GREEN','RED','WARNING'),
        mode enum ('DRY_RUN','LIVE','OFF') not null,
        status enum ('BLOCKED','CANCELLED','DISABLED','EXECUTED','EXECUTING','EXPIRED','FAILED','PARTIAL','PLANNED','SUPERSEDED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_order_plan_asset (
        base_amount decimal(24,8),
        carry_in_usd decimal(24,8),
        carry_out_usd decimal(24,8),
        cost_pct decimal(12,6),
        priority integer not null,
        simulated bit not null,
        id bigint not null auto_increment,
        plan_id bigint not null,
        wallet_id bigint,
        asset_symbol varchar(16) not null,
        warning varchar(255),
        action enum ('BLOCKED','BUY','NONE','SELL'),
        block_reason enum ('BELOW_MIN','INSUFFICIENT_FUNDS_AFTER_FEES','NOT_TRADABLE_WITHOUT_FIAT','NO_PATH','READING_UNAVAILABLE'),
        fee_test_level enum ('GREEN','RED','WARNING'),
        outcome enum ('BLOCKED','DISABLED','NO_ACTION','OK') not null,
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_live_order_step (
        avg_fill_px decimal(38,18),
        fee_amount decimal(38,18),
        fee_pct decimal(12,6) not null,
        filled_sz decimal(38,18),
        px decimal(38,18) not null,
        quote_amount decimal(38,18) not null,
        quote_mid decimal(38,18),
        real_slippage_pct decimal(12,6),
        received_amount decimal(38,18),
        sent_sz decimal(38,18),
        slippage_pct decimal(12,6) not null,
        spread_pct decimal(12,6) not null,
        step_rank integer not null,
        sz decimal(38,18) not null,
        finished_at datetime(6),
        id bigint not null auto_increment,
        plan_id bigint not null,
        submitted_at datetime(6),
        wallet_id bigint,
        asset_symbol varchar(16) not null,
        fee_currency varchar(20),
        received_currency varchar(20),
        cl_ord_id varchar(32) not null,
        inst_id varchar(32) not null,
        last_error varchar(64),
        ord_id varchar(64),
        estimation enum ('BOOK','TICKER') not null,
        ord_type enum ('LIMIT_IOC') not null,
        side enum ('BUY','SELL') not null,
        status enum ('CANCELED','FILLED','PARTIAL','PLANNED','REJECTED','SUBMITTED','UNKNOWN') not null,
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
        mode varchar(16),
        name varchar(64) not null,
        buy_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
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
        signal_action_amount_usdc float(53),
        signal_action_price float(53),
        signal_action_quantity float(53),
        signal_ath_distance float(53),
        signal_atr float(53),
        signal_bound_down1 float(53),
        signal_bound_down2 float(53),
        signal_bound_up1 float(53),
        signal_bound_up2 float(53),
        signal_bound_up3 float(53),
        signal_buy_armed bit,
        signal_buy_factor float(53),
        signal_buy_locked bit,
        signal_close float(53),
        signal_cooldown_remaining integer,
        signal_moon_mode bit,
        signal_moon_reserve_qty float(53),
        signal_sell_armed bit,
        signal_sell_factor float(53),
        signal_sma float(53),
        signal_zone integer,
        sma_period integer,
        trailing_stop_buy_pct float(53),
        trailing_stop_sell_pct float(53),
        affectation_id bigint,
        id bigint not null auto_increment,
        signal_computed_at datetime(6),
        user_id bigint not null,
        asset_symbol varchar(16) not null,
        config_hash varchar(32),
        signal_active_set varchar(255),
        signal_config_hash varchar(255),
        signal_trend_regime varchar(255),
        buy_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        sell_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        signal_action_type enum ('BLOCKED','BUY','NONE','SELL'),
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

    create table rainbow_modulation_control (
        autonomy_days integer not null,
        bias_max decimal(10,4) not null,
        min_band_width_pts decimal(10,4) not null,
        modulation_product_max decimal(10,4) not null,
        power_max decimal(10,4) not null,
        power_min decimal(10,4) not null,
        regulation_margin_pct decimal(10,4) not null,
        sell_boost_max decimal(10,4) not null,
        id bigint not null,
        updated_at datetime(6) not null,
        updated_by varchar(64),
        primary key (id)
    ) engine=InnoDB;

    create table rainbow_preset (
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
        owner_id bigint,
        updated_at datetime(6) not null,
        asset_symbol varchar(16) not null,
        reference_provider varchar(32),
        name varchar(64) not null,
        base_role enum ('RAINBOW_BEAR','RAINBOW_BULL','TREND'),
        buy_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
        origin enum ('SYSTEM','USER') not null,
        sell_reentry_mode enum ('FIXED_DELAY','IMMEDIATE','TRAILING_STOP'),
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
        fee_currency varchar(20),
        quote_currency varchar(20),
        external_transaction_id varchar(255) not null,
        origin enum ('DCA','MANUAL','TRADEIO'),
        trade_side enum ('BUY','SELL') not null,
        primary key (id)
    ) engine=InnoDB;

    create table transit_stock (
        origin_step_rank integer,
        quantity decimal(38,18) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        origin_plan_id bigint,
        resolved_at datetime(6),
        user_id bigint not null,
        wallet_id bigint not null,
        currency varchar(16) not null,
        target_asset varchar(16) not null,
        finish_side enum ('BUY','SELL'),
        primary key (id)
    ) engine=InnoDB;

    create table trend_preset (
        atr_multiplier float(53) not null,
        atr_period integer not null,
        confirm_days integer not null,
        enter_threshold float(53) not null,
        exit_threshold float(53) not null,
        long_window integer not null,
        medium_window integer not null,
        revision integer not null,
        short_window integer not null,
        slope_scale float(53) not null,
        sma_period integer not null,
        wick_down bit not null,
        wick_up bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        owner_id bigint,
        updated_at datetime(6) not null,
        asset_symbol varchar(16) not null,
        range_mapping varchar(16) not null,
        reference_provider varchar(32),
        name varchar(64) not null,
        base_role enum ('RAINBOW_BEAR','RAINBOW_BULL','TREND'),
        origin enum ('SYSTEM','USER') not null,
        primary key (id)
    ) engine=InnoDB;

    create table user_modulation_history (
        after_bias float(53),
        after_exposure_max float(53),
        after_exposure_min float(53),
        after_margin_pct float(53),
        after_power float(53),
        before_bias float(53),
        before_exposure_max float(53),
        before_exposure_min float(53),
        before_margin_pct float(53),
        before_power float(53),
        id bigint not null auto_increment,
        occurred_at datetime(6) not null,
        user_id bigint not null,
        profile_after enum ('BALANCED','CUSTOM','DYNAMIC','FULL','PRUDENT'),
        profile_before enum ('BALANCED','CUSTOM','DYNAMIC','FULL','PRUDENT'),
        source enum ('MANUAL','PROFILE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table user_roles (
        role_id integer not null,
        user_id bigint not null,
        primary key (role_id, user_id)
    ) engine=InnoDB;

    create table user_trading_settings (
        bias float(53),
        exposure_max float(53),
        exposure_min float(53),
        max_day_usd float(53),
        max_order_usd float(53),
        power float(53),
        regulation_margin_pct float(53),
        risk_cursor integer not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        alerts_disabled varchar(200),
        display_mode enum ('ADVANCED','EXPERT','SIMPLE'),
        starting_profile enum ('BALANCED','CUSTOM','DYNAMIC','FULL','PRUDENT'),
        theme enum ('DARK','LIGHT'),
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
        web_provider_code enum ('BINANCE','BINANCE_TESTNET','BITVAVO','COINALYZE','COINSTATS','DEFILLAMA','FARSIDE','FINNHUB','FOREXFACTORY','GATE_EU','KRAKEN','LEDGER','METAMASK','OKX','ONE_TRADING','REVOLUT_X','SOSOVALUE','TWELVE_DATA','YAHOO_FINANCE','YOUTUBE'),
        primary key (id)
    ) engine=InnoDB;

    create index ix_admin_action_log_created_at 
       on admin_action_log (created_at);

    alter table api_credentials 
       add constraint uk_credential_user_provider_scope unique (user_id, web_provider_id, scope);

    alter table asset_affectation 
       add constraint uk_asset_affectation_user_asset_name unique (user_id, asset_symbol, name);

    alter table asset_group 
       add constraint uk_asset_group_code unique (code);

    alter table asset_group_member 
       add constraint uk_asset_group_member_asset unique (asset_id);

    alter table asset_provider 
       add constraint uk_asset_provider_asset_source unique (asset_id, source);

    alter table candle 
       add constraint uk_candle_source_pair_tf_ts unique (source, pair, time_frame, timestamp);

    alter table content_sources 
       add constraint uk_content_source_channel_id unique (channel_id);

    create index ix_doctor_alarm_key_state 
       on doctor_alarm (alarm_key, state);

    create index ix_doctor_alarm_state 
       on doctor_alarm (state);

    alter table etf_flow_snapshot 
       add constraint uk_etf_flow_snapshot_asset_date unique (asset, date);

    alter table exchange_instrument 
       add constraint uk_exchange_instrument_provider_inst unique (provider, inst_id);

    alter table indicator_parameter 
       add constraint uk_indicatorparam_param_key unique (parameter_set_id, param_key);

    alter table indicator_parameter_set 
       add constraint uk_indicatorparamset_code_name unique (indicator_code, name);

    create index ix_job_run_job_started 
       on job_run (job_name, started_at);

    alter table portfolio_snapshot 
       add constraint uk_portfolio_snapshot_user_wallet_day unique (user_id, wallet_id, snapshot_day);

    alter table portfolio_snapshot_asset 
       add constraint uk_portfolio_snapshot_asset_snapshot_asset unique (snapshot_id, asset_symbol);

    alter table provider 
       add constraint uk_provider_code unique (code);

    alter table provider_pending_key 
       add constraint uk_pending_key_user_provider_scope unique (user_id, provider_code, scope);

    alter table rainbow_ath_reference 
       add constraint uk_rainbow_ath_reference_asset_day unique (asset_symbol, ref_day);

    alter table rainbow_live_binding 
       add constraint uk_rainbow_live_binding_user_asset_wallet unique (user_id, asset_symbol, wallet_id);

    alter table rainbow_live_binding_run 
       add constraint uk_rainbow_live_binding_run_binding_day unique (binding_id, run_day);

    alter table rainbow_live_engine_state 
       add constraint uk_rainbow_live_engine_state_affectation_day unique (affectation_id, state_day);

    create index ix_rainbow_live_order_event_plan 
       on rainbow_live_order_event (plan_id);

    alter table rainbow_live_order_plan 
       add constraint uk_rainbow_live_order_plan_user_day_rev unique (user_id, run_day, revision);

    create index idx_rainbow_live_preset_event_user_at 
       on rainbow_live_preset_event (user_id, occurred_at);

    alter table rainbow_live_run 
       add constraint uk_rainbow_live_run_affectation_day unique (affectation_id, run_day);

    alter table rainbow_live_trend_config 
       add constraint UKmiihysxofj591fc5w2lhcg065 unique (preset_id);

    alter table rainbow_preset 
       add constraint uk_rainbow_preset_owner_asset_name unique (owner_id, asset_symbol, name);

    alter table rainbow_preset 
       add constraint uk_rainbow_preset_asset_base_role unique (asset_symbol, base_role);

    alter table transaction 
       add constraint uk_transaction_ext_id unique (external_transaction_id);

    create index idx_transit_stock_wallet 
       on transit_stock (wallet_id, resolved_at);

    alter table trend_preset 
       add constraint uk_trend_preset_owner_asset_name unique (owner_id, asset_symbol, name);

    alter table trend_preset 
       add constraint uk_trend_preset_asset_base_role unique (asset_symbol, base_role);

    create index idx_user_modulation_history_user_at 
       on user_modulation_history (user_id, occurred_at);

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

    alter table asset_affectation 
       add constraint FK3up1ysi34dr0pagtuspd2u9ap 
       foreign key (rainbow_down_id) 
       references rainbow_preset (id);

    alter table asset_affectation 
       add constraint FKlw2801l0wcvk8ccfub7wva4of 
       foreign key (rainbow_up_id) 
       references rainbow_preset (id);

    alter table asset_affectation 
       add constraint FK6wfkm8ai6ion4wbck6drluxsb 
       foreign key (trend_preset_id) 
       references trend_preset (id);

    alter table asset_affectation 
       add constraint FKfal0mydigtsm9q41c05q7etkd 
       foreign key (user_id) 
       references users (id);

    alter table asset_group_member 
       add constraint FK8ex95pmwnu4ekg6gt83us3xij 
       foreign key (asset_id) 
       references asset (id);

    alter table asset_group_member 
       add constraint FK4bw3x2k7upvq0yn966hrsceyb 
       foreign key (group_id) 
       references asset_group (id);

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

    alter table portfolio_snapshot 
       add constraint FKf4lqarj6egnuq0ug61k42skgx 
       foreign key (user_id) 
       references users (id) 
       on delete cascade;

    alter table portfolio_snapshot 
       add constraint FK6cedt4mxubp9ab1wcgtwm89eh 
       foreign key (wallet_id) 
       references wallet (id) 
       on delete cascade;

    alter table portfolio_snapshot_asset 
       add constraint FKr6v3bdb3ud7tnk2dc11csx1we 
       foreign key (snapshot_id) 
       references portfolio_snapshot (id) 
       on delete cascade;

    alter table provider_pending_key 
       add constraint FKtmrisrp21h9pxlwqlmrxheebt 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_binding 
       add constraint FKfnpcxcp7ivd553ns8e8qmngv4 
       foreign key (affectation_id) 
       references asset_affectation (id);

    alter table rainbow_live_binding 
       add constraint FKswhsbufw25y92hiq33ulgo4bj 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_binding 
       add constraint FKd6ymsdvc4qgdfq1koso6b4jh6 
       foreign key (wallet_id) 
       references wallet (id);

    alter table rainbow_live_binding_run 
       add constraint FKp0rmqlharqdusd4pm4322hq2u 
       foreign key (binding_id) 
       references rainbow_live_binding (id) 
       on delete cascade;

    alter table rainbow_live_engine_state 
       add constraint FKfck3yev231vok49yhrkf8nmwc 
       foreign key (affectation_id) 
       references asset_affectation (id) 
       on delete cascade;

    alter table rainbow_live_order_event 
       add constraint FKm5tpyhe0od69dr9v4bunwspb1 
       foreign key (plan_id) 
       references rainbow_live_order_plan (id);

    alter table rainbow_live_order_plan 
       add constraint FK827b9uthq2wdvtmyqrrna64jh 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_order_plan_asset 
       add constraint FKtnj8tujmtxmuqdv0uvnuqg6la 
       foreign key (plan_id) 
       references rainbow_live_order_plan (id) 
       on delete cascade;

    alter table rainbow_live_order_step 
       add constraint FKlkqkqr5s2j4bt2innsos2pstx 
       foreign key (plan_id) 
       references rainbow_live_order_plan (id) 
       on delete cascade;

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
       add constraint FKg5ufr8ofq2ub0dywgafyowal6 
       foreign key (affectation_id) 
       references asset_affectation (id) 
       on delete cascade;

    alter table rainbow_live_run 
       add constraint FKoluxak4rl3tb78vj2ir0fmqpr 
       foreign key (user_id) 
       references users (id);

    alter table rainbow_live_trend_config 
       add constraint FK602chppbbt916lr585rgjftbt 
       foreign key (preset_id) 
       references rainbow_live_preset (id);

    alter table rainbow_preset 
       add constraint FKdramadmup0jkd2ewphb3evc3h 
       foreign key (owner_id) 
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

    alter table trend_preset 
       add constraint FKf4erxvnri1qq27vw3uf740qbe 
       foreign key (owner_id) 
       references users (id);

    alter table user_modulation_history 
       add constraint FK3tgdw9teparmkkfl20gne6dnc 
       foreign key (user_id) 
       references users (id) 
       on delete cascade;

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
