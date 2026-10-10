package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Noms d'actifs Kraken (réponses de {@code BalanceEx}) vers symboles standard (BTC, ETH, USDC...).
 * <ul>
 *   <li>Noms historiques : {@code XXBT/XBT -> BTC}, {@code XETH -> ETH}, {@code ZUSD -> USD}...</li>
 *   <li>{@code .F} (Auto Earn) : fonds utilisables via l'actif nu (doc Kraken) => agrégés avec l'actif nu.</li>
 *   <li>Autres suffixes ({@code .S .M .B .P .T} : staking, rewards optionnels, produits Earn, tokenisés) : ignorés,
 *       non disponibles immédiatement pour trader.</li>
 * </ul>
 */
final class KrakenAssetNames {

    private static final String AUTO_EARN_SUFFIX = ".F";

    private static final Map<String, String> LEGACY_TO_SYMBOL = Map.ofEntries(
            Map.entry("XXBT", "BTC"),
            Map.entry("XBT", "BTC"),
            Map.entry("XETH", "ETH"),
            Map.entry("XLTC", "LTC"),
            Map.entry("XXRP", "XRP"),
            Map.entry("XXLM", "XLM"),
            Map.entry("XXDG", "DOGE"),
            Map.entry("XDG", "DOGE"),
            Map.entry("XETC", "ETC"),
            Map.entry("XXMR", "XMR"),
            Map.entry("XZEC", "ZEC"),
            Map.entry("ZUSD", "USD"),
            Map.entry("ZEUR", "EUR"),
            Map.entry("ZGBP", "GBP"),
            Map.entry("ZCAD", "CAD"),
            Map.entry("ZJPY", "JPY"));

    private KrakenAssetNames() {
    }

    /** Symbole standard du solde, ou vide si la variante n'est pas immédiatement disponible (staking...). */
    static Optional<String> toSymbol(String krakenAsset) {
        String asset = krakenAsset.toUpperCase(Locale.ROOT);
        if (asset.endsWith(AUTO_EARN_SUFFIX)) {
            asset = asset.substring(0, asset.length() - AUTO_EARN_SUFFIX.length());
        }
        if (asset.indexOf('.') >= 0) {
            return Optional.empty();
        }
        return Optional.of(LEGACY_TO_SYMBOL.getOrDefault(asset, asset));
    }
}
