package fr.ses10doigts.tradeIO5.service.market;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Garde-fou côté tools MCP : un asset doit être désigné par son symbole nu (ex: {@code "BTC"}),
 * jamais par la paire native d'un exchange (ex: {@code "BTCUSDT"}, {@code "XXBTZUSD"},
 * {@code "BTC-USDT"}) — c'est la table {@code asset_provider} qui porte la traduction vers
 * chaque exchange, cf. {@code AssetInitializer} / {@code MarketDatasetEngine#getDatasetForAsset}
 * / docs/etude-fallback-multi-provider-marketdata.md.
 * <p>
 * Volontairement PAS branché dans {@code TreeAnalysisFacade} ou {@code DcaCalculatorService}
 * eux-mêmes : ces services doivent rester appelables avec des symboles arbitraires en test
 * ({@code MarketDataSource#MEMORY}, ou un asset pas encore migré dans {@code asset_provider}
 * avec repli explicite documenté) — cf. leurs tests unitaires respectifs. Ce validateur n'a de
 * sens qu'à la frontière "entrée LLM" (tools MCP), où on veut échouer vite avec un message
 * actionnable plutôt que de laisser remonter une {@code NoProviderAvailableException} moins
 * explicite (qui ne distingue pas "asset totalement inconnu" de "asset connu mais pas encore
 * migré dans asset_provider").
 */
@Component
@RequiredArgsConstructor
public class AssetSymbolValidator {

    private final AssetRepository assetRepository;

    /**
     * @throws IllegalArgumentException si {@code symbol} est vide/blanc ou ne correspond à
     *                                   aucun {@link Asset#getSymbol()} connu, avec un message
     *                                   listant les symboles valides.
     */
    public void requireKnownAsset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol est requis (symbole nu de l'actif, ex: BTC, ETH)");
        }
        if (assetRepository.findBySymbol(symbol).isPresent()) {
            return;
        }
        List<String> known = assetRepository.findAll().stream()
                .map(Asset::getSymbol)
                .sorted()
                .toList();
        throw new IllegalArgumentException("Symbole d'actif inconnu : '" + symbol + "'. Utiliser le symbole nu "
                + "de l'actif (ex: BTC, ETH), pas la paire d'un exchange (ex: BTCUSDT, XXBTZUSD, BTC-USDT). "
                + "Symboles connus : " + known);
    }
}
