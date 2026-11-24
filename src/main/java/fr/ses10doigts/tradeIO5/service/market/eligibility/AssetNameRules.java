package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exclusions par NOM de symbole (pures, sans I/O) : fiat, stablecoins, tokens à levier / dérivés, tokens « earn » / staking / wrapped.
 * Les symboles arrivent normalisés par les clients de catalogue (majuscules ; Kraken : {@code XXBT -> BTC}, {@code .F} replié sur l'actif nu,
 * autres suffixes {@code .S .M .B .P .T} conservés côté client et ignorés avant le catalogue). Les règles sont rejouées ici par provider en
 * seconde ligne de défense.
 */
public final class AssetNameRules {

    /** Stablecoins (dollar, euro et autres) : jamais un actif DCA. */
    public static final Set<String> STABLECOINS = Set.of("USDT", "USDC", "DAI", "USDS", "FDUSD", "TUSD", "BUSD", "USDP", "PYUSD", "USDD",
            "GUSD", "LUSD", "FRAX", "USDE", "USD1", "RLUSD", "USDG", "AUSD", "EURC", "EURT", "EURS", "EURI");

    /** Wrapped / liquid staking, tous providers : un faux « nouvel » actif qui double l'actif sous-jacent. */
    public static final Set<String> WRAPPED = Set.of("WBTC", "WETH", "WBNB", "WBETH", "BETH", "STETH", "WSTETH", "CBETH", "RETH", "WEETH",
            "EETH", "CBBTC", "BNSOL", "MSOL", "JITOSOL", "BSOL");

    /** 3L / 3S / 5L / 5S : {@code BTC3L}, {@code ETH5S}. */
    private static final Pattern LEVERAGE_NUMERIC = Pattern.compile("^[A-Z0-9]{2,}\\d+[LS]$");
    /** UP / DOWN / BULL / BEAR : {@code BTCUP}, {@code ETHDOWN}, {@code BTCBULL}. Racine d'au moins 2 caractères. */
    private static final Pattern LEVERAGE_WORD = Pattern.compile("^[A-Z0-9]{2,}(UP|DOWN|BULL|BEAR)$");

    /** Kraken : tout suffixe {@code .X} (staking {@code .S}, rewards {@code .M}, {@code .B}, {@code .P}, tokenisés {@code .T}, auto-earn {@code .F}). */
    private static final Pattern KRAKEN_SUFFIX = Pattern.compile("^.+\\.[A-Z]$");
    /** Kraken : ETH staké historique (sans suffixe). */
    private static final Set<String> KRAKEN_EARN = Set.of("ETH2");
    /** Binance : Simple Earn flexible {@code LDBTC}. Reste d'au moins 3 caractères : {@code LDO} (Lido) n'est pas concerné. */
    private static final String BINANCE_EARN_PREFIX = "LD";
    private static final int BINANCE_EARN_MIN_REST = 3;

    /** Première exclusion par nom, ou vide. {@code provider} peut être nul (règles génériques seulement). */
    public static Optional<IneligibilityReason> classify(String symbol, WebProviderCode provider) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.of(IneligibilityReason.EARN_OR_WRAPPED);
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (ExecutionDefaults.FIAT_CURRENCIES.contains(s)) {
            return Optional.of(IneligibilityReason.FIAT);
        }
        if (STABLECOINS.contains(s)) {
            return Optional.of(IneligibilityReason.STABLECOIN);
        }
        if (LEVERAGE_NUMERIC.matcher(s).matches() || LEVERAGE_WORD.matcher(s).matches()) {
            return Optional.of(IneligibilityReason.LEVERAGED_TOKEN);
        }
        if (WRAPPED.contains(s) || isEarnVariant(s, provider)) {
            return Optional.of(IneligibilityReason.EARN_OR_WRAPPED);
        }
        return Optional.empty();
    }

    /** Variante « earn » / staking propre à l'encodage du provider. */
    static boolean isEarnVariant(String symbol, WebProviderCode provider) {
        if (provider == WebProviderCode.KRAKEN) {
            return KRAKEN_SUFFIX.matcher(symbol).matches() || KRAKEN_EARN.contains(symbol);
        }
        if (provider == WebProviderCode.BINANCE) {
            return symbol.startsWith(BINANCE_EARN_PREFIX) && symbol.length() - BINANCE_EARN_PREFIX.length() >= BINANCE_EARN_MIN_REST;
        }
        return false;
    }

    private AssetNameRules() {
    }
}
