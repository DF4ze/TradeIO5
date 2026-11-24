package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Paire des bougies D1 d'un actif chez un exchange, d'après le catalogue en base (aucun appel réseau) : cotation USDC si la paire existe, sinon USDT,
 * sinon USD ({@link #QUOTE_PREFERENCE}). Sert à la création à la volée des lignes {@code asset_provider} et à la lecture D1 sans ligne ; les lignes
 * existantes ne sont jamais modifiées. Exchange sans catalogue (Binance : source de bougies seulement) ou catalogue sans paire de l'actif : convention
 * {@link ProviderSymbolMapper#forSource(String, MarketDataSource)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandlePairResolver {

    /** Ordre de préférence des cotations des bougies (précise la règle « tout en USDC » : repli USDT puis USD, comme BTC / ETH / PAXG). */
    public static final List<String> QUOTE_PREFERENCE = List.of("USDC", "USDT", "USD");

    private final InstrumentCatalog catalog;

    public String pairFor(String asset, MarketDataSource source) {
        Optional<WebProviderCode> code = providerOf(source);
        if (code.isPresent() && catalog.supports(code.get())) {
            List<ExchangeInstrument> own = catalog.storedInstruments(code.get()).stream()
                    .filter(i -> InstrumentCatalog.LIVE_STATE.equals(i.getState()) && asset.equals(i.getBase())).toList();
            for (String quote : QUOTE_PREFERENCE) {
                Optional<ExchangeInstrument> found = own.stream().filter(i -> quote.equals(i.getQuote())).findFirst();
                if (found.isPresent()) {
                    String pair = source == MarketDataSource.KRAKEN && found.get().getNativePair() != null && !found.get().getNativePair().isBlank()
                            ? found.get().getNativePair() : ProviderSymbolMapper.forSource(asset, source, quote);
                    log.debug("Paire de bougies {} chez {} : {} (catalogue)", asset, source, pair);
                    return pair;
                }
            }
        }
        return ProviderSymbolMapper.forSource(asset, source);
    }

    /** Exchange du provider portant la source de bougies, s'il y en a un. */
    static Optional<WebProviderCode> providerOf(MarketDataSource source) {
        try {
            return Optional.of(WebProviderCode.valueOf(source.name()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
