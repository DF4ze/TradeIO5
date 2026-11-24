package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetRegistry;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService;
import fr.ses10doigts.tradeIO5.service.market.dataset.CandlePairResolver;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Préparation d'un actif au 1er lien (idempotente, rien n'est jamais modifié si déjà là) :
 * <ol>
 *   <li>ligne {@code asset} : symbole, nom = symbole, {@code decimals} dérivés du pas de quantité ({@code lotSz}) de la paire USD / USDC / USDT
 *   du catalogue du provider, à défaut {@link #DEFAULT_DECIMALS} ;</li>
 *   <li>ligne {@code asset_provider} du provider du lien SI l'actif n'en a aucune (paire de bougies USDC > USDT > USD, {@link CandlePairResolver}) ;
 *   les lignes existantes (BTC, ETH, PAXG) ne sont jamais touchées, les autres providers sont créés à la volée par le moteur de bougies ;</li>
 *   <li>presets de base : semés pour BTC / ETH / PAXG, copiés du BTC pour tout autre actif (simulation immédiate).</li>
 * </ol>
 * L'Affectation Système « &lt;ACTIF&gt; Système » est créée ensuite par {@code AssetAffectationService.forAsset}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetLinkBootstrap {

    /** Décimales d'un actif dont le catalogue ne donne aucun pas de quantité exploitable (celles de BTC). */
    public static final int DEFAULT_DECIMALS = 8;
    private static final int MAX_DECIMALS = 18;

    private final AssetRepository assets;
    private final AssetProviderRepository assetProviders;
    private final ExpertBasePresetService basePresets;
    private final InstrumentCatalog catalog;
    private final CandlePairResolver pairs;

    @Transactional
    public Asset prepare(String symbol, WebProvider provider, MarketDataSource candleSource) {
        Asset asset = assets.findBySymbol(symbol).orElse(null);
        if (asset == null) {
            asset = assets.save(Asset.builder().symbol(symbol).name(symbol).decimals(decimalsFor(provider, symbol)).build());
            log.info("Actif {} créé au 1er lien (decimals={})", symbol, asset.getDecimals());
        }
        if (candleSource != null && assetProviders.findByAsset_SymbolOrderByPriorityAsc(symbol).isEmpty()) {
            String pair = pairs.pairFor(symbol, candleSource);
            if (pair != null) {
                assetProviders.save(AssetProvider.builder().asset(asset).source(candleSource).providerSymbol(pair).priority(0)
                        .maxHorizonDays(candleSource == MarketDataSource.KRAKEN ? MarketDataProviderLimits.KRAKEN_H1_MAX_HORIZON_DAYS : null)
                        .enabled(true).build());
                log.info("asset_provider créé : {} / {} ({})", symbol, candleSource, pair);
            }
        }
        basePresets.ensureBasePresets();
        if (!AssetRegistry.SEED_ASSETS.contains(symbol)) {
            basePresets.copyBasePresetsFromBtc(symbol);
        }
        return asset;
    }

    /** Décimales d'après le {@code lotSz} de la première paire live de l'actif cotée en USD / USDC / USDT ; {@link #DEFAULT_DECIMALS} à défaut. */
    int decimalsFor(WebProvider provider, String symbol) {
        if (provider == null || provider.getCode() == null || !catalog.supports(provider.getCode())) {
            return DEFAULT_DECIMALS;
        }
        return catalog.storedInstruments(provider.getCode()).stream()
                .filter(i -> InstrumentCatalog.LIVE_STATE.equals(i.getState()) && symbol.equals(i.getBase())
                        && AssetEligibilityDefaults.USD_QUOTES.contains(i.getQuote()))
                .map(ExchangeInstrument::getLotSz).filter(l -> l != null && l.signum() > 0).findFirst()
                .map(AssetLinkBootstrap::scaleOf).orElse(DEFAULT_DECIMALS);
    }

    private static int scaleOf(BigDecimal lotSz) {
        return Math.max(0, Math.min(MAX_DECIMALS, lotSz.stripTrailingZeros().scale()));
    }
}
