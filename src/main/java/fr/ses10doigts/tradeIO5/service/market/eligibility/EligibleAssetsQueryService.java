package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Actifs éligibles chez les providers de l'utilisateur, triés par volume décroissant. Lecture du CATALOGUE EN BASE uniquement (aucun appel réseau par
 * requête) : noms exclus ({@link AssetNameRules}), liste noire, volume 24 h au-dessus du seuil admin (volume inconnu : retenu, {@code volumeVerified}
 * faux) et route d'achat depuis USDC. L'historique D1 (≥ {@value AssetEligibilityDefaults#MIN_D1_DAYS} jours) n'est pas lu ici (cache de bougies, réseau
 * possible) : il est vérifié à la création du lien ({@link AssetEligibilityService#require}), qui peut donc encore refuser un actif listé.
 */
@Service
@RequiredArgsConstructor
public class EligibleAssetsQueryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** État de calibration de l'actif : ligne de taux validée, ou simulation seule en attente de validation. */
    public enum CalibrationState { CALIBRE, EN_ATTENTE_VALIDATION }

    /** Un actif chez un provider ({@code provider} = pastille). */
    public record EligibleAsset(String symbol, WebProviderCode provider, BigDecimal volumeUsd, boolean volumeVerified, boolean alreadyLinked,
                                CalibrationState state) {
    }

    private final WalletRepository wallets;
    private final RainbowLiveBindingRepository bindings;
    private final InstrumentCatalog catalog;
    private final AssetEligibilityService eligibility;
    private final AssetEligibilitySettingsService settings;
    private final AssetBlocklistRepository blocklist;
    private final AssetBaseRateService baseRates;

    /**
     * @param provider exchange demandé (parmi ceux de l'utilisateur) ; nul = tous les exchanges de ses wallets actifs
     * @param query    filtre « contient » insensible à la casse sur le symbole ; nul ou vide = aucun
     * @param limit    nombre maximal d'actifs (défaut {@value #DEFAULT_LIMIT}, plafond {@value #MAX_LIMIT})
     * @throws IllegalArgumentException provider inconnu ou non connecté par l'utilisateur
     */
    @Transactional(readOnly = true)
    public List<EligibleAsset> list(User user, String provider, String query, Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        String needle = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        Map<WebProviderCode, WebProvider> mine = providersOf(user);
        if (provider != null && !provider.isBlank()) {
            WebProviderCode wanted = parse(provider);
            if (!mine.containsKey(wanted)) {
                throw new IllegalArgumentException("Exchange non connecté pour cet utilisateur : " + provider);
            }
            mine = Map.of(wanted, mine.get(wanted));
        }
        Set<String> linked = bindings.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream()
                .map(b -> key(b.getWallet().getWebProviderCode(), b.getAssetSymbol())).collect(Collectors.toSet());
        Set<String> blocked = blocklist.findAll().stream().map(b -> b.getAssetSymbol()).collect(Collectors.toSet());
        BigDecimal min = settings.minVolumeUsd();
        List<EligibleAsset> out = new ArrayList<>();
        for (WebProviderCode code : mine.keySet()) {
            out.addAll(forProvider(code, needle, max, min, blocked, linked));
        }
        return out.stream().sorted(ORDER).limit(max).toList();
    }

    private static final Comparator<EligibleAsset> ORDER = Comparator
            .comparing(EligibleAsset::volumeUsd, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
            .thenComparing(EligibleAsset::symbol).thenComparing(EligibleAsset::provider);

    private List<EligibleAsset> forProvider(WebProviderCode code, String needle, int max, BigDecimal min, Set<String> blocked, Set<String> linked) {
        List<ExchangeInstrument> all = catalog.storedInstruments(code);
        List<InstrumentInfo> live = catalog.storedLiveInstruments(code);
        Map<String, BigDecimal> volumes = eligibility.usdVolumes(all);
        Set<String> seen = new HashSet<>();
        List<String> candidates = new ArrayList<>();
        for (InstrumentInfo i : live) {
            String base = i.base();
            if (base != null && seen.add(base) && (needle.isEmpty() || base.contains(needle)) && !blocked.contains(base)
                    && AssetNameRules.classify(base, code).isEmpty()) {
                BigDecimal volume = volumes.get(base);
                if (volume == null || volume.compareTo(min) >= 0) { // volume inconnu : retenu (non vérifié)
                    candidates.add(base);
                }
            }
        }
        candidates.sort(Comparator.comparing((String s) -> volumes.get(s), Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())).thenComparing(s -> s));
        Map<String, EligibleAsset> found = new LinkedHashMap<>();
        for (String base : candidates) {
            if (found.size() >= max) {
                break;
            }
            if (eligibility.hasBuyRoute(live, base)) {
                BigDecimal volume = volumes.get(base);
                found.put(base, new EligibleAsset(base, code, volume, volume != null, linked.contains(key(code, base)),
                        baseRates.isCalibrated(base) ? CalibrationState.CALIBRE : CalibrationState.EN_ATTENTE_VALIDATION));
            }
        }
        return List.copyOf(found.values());
    }

    /** Exchanges des wallets actifs de l'utilisateur dotés d'un catalogue. */
    private Map<WebProviderCode, WebProvider> providersOf(User user) {
        Map<WebProviderCode, WebProvider> out = new LinkedHashMap<>();
        for (Wallet wallet : wallets.findByUserAndEnabledTrue(user)) {
            WebProvider provider = wallet.getWebProvider();
            if (provider != null && provider.getCode() != null && catalog.supports(provider.getCode())) {
                out.putIfAbsent(provider.getCode(), provider);
            }
        }
        return out;
    }

    private static WebProviderCode parse(String provider) {
        try {
            return WebProviderCode.valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Exchange inconnu : " + provider);
        }
    }

    private static String key(WebProviderCode code, String asset) {
        return code + ":" + asset;
    }
}
