package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Un actif est-il admissible chez un provider ? Renvoie {@link Result} éligible, ou la première {@link IneligibilityReason}. Lecture seule, aucun ordre.
 * Ordre des règles (les moins chères d'abord, le réseau en dernier) :
 * <ol>
 *   <li>noms ({@link AssetNameRules}) : fiat, stablecoins, tokens à levier, earn / wrapped (encodage propre au provider) ;</li>
 *   <li>liste noire ({@code asset_blocklist}) ;</li>
 *   <li>route d'achat : {@link PathFinder#routes} depuis {@link ExecutionDefaults#CASH_STABLE} sur le catalogue en base, ≤ {@link ExecutionDefaults#MAX_PATH_DEPTH}
 *       jambes, sans nœud interdit (fiat, USDT), passerelle choisie comprise (BTC), jamais un intermédiaire « affichage seul » (ETH) ;</li>
 *   <li>volume spot 24 h (catalogue) : somme des paires live cotées en USD / USDC / USDT chez ce provider, seuil admin. Volume inconnu (exchange sans volume,
 *       lecture périmée au-delà de {@link AssetEligibilityDefaults#VOLUME_MAX_AGE}, aucune paire cotée en USD) : <b>non vérifiable</b>, éligible avec
 *       {@code volumeVerified = false} ({@link AssetEligibilityDefaults#UNKNOWN_VOLUME_ELIGIBLE}) ;</li>
 *   <li>historique : au moins {@link AssetEligibilityDefaults#MIN_D1_DAYS} jours de bougies D1 closes chez ce provider ({@link D1HistoryProbe}, cache DB).</li>
 * </ol>
 */
@Slf4j
@Service
public class AssetEligibilityService {

    /** Verdict. {@code volumeUsd} nul si non vérifiable ; {@code d1Days} nul si la règle n'a pas été atteinte. */
    public record Result(String asset, WebProviderCode provider, boolean eligible, IneligibilityReason reason, BigDecimal volumeUsd,
                         boolean volumeVerified, Integer d1Days) {

        static Result refused(String asset, WebProviderCode provider, IneligibilityReason reason) {
            return new Result(asset, provider, false, reason, null, false, null);
        }
    }

    private final InstrumentCatalog catalog;
    private final AssetBlocklistRepository blocklist;
    private final AssetEligibilitySettingsService settings;
    private final PathFinder pathFinder;
    private final D1HistoryProbe historyProbe;
    private final DomainClock clock;
    private final Set<String> forbiddenNodes;

    public AssetEligibilityService(InstrumentCatalog catalog, AssetBlocklistRepository blocklist, AssetEligibilitySettingsService settings,
                                   PathFinder pathFinder, D1HistoryProbe historyProbe, DomainClock clock,
                                   @Value("${" + ExecutionDefaults.FORBIDDEN_NODES_PROPERTY + ":}") String forbiddenNodes) {
        this.catalog = catalog;
        this.blocklist = blocklist;
        this.settings = settings;
        this.pathFinder = pathFinder;
        this.historyProbe = historyProbe;
        this.clock = clock;
        this.forbiddenNodes = ExecutionDefaults.forbiddenNodes(forbiddenNodes);
    }

    public Result check(String assetSymbol, WebProvider provider) {
        Result result = doCheck(assetSymbol == null ? "" : assetSymbol.trim().toUpperCase(Locale.ROOT), provider);
        log.debug("Éligibilité {} chez {} : {}", result.asset(), result.provider(), result.eligible() ? "OK" : result.reason());
        return result;
    }

    private Result doCheck(String asset, WebProvider provider) {
        WebProviderCode code = provider.getCode();
        Optional<IneligibilityReason> byName = AssetNameRules.classify(asset, code);
        if (byName.isPresent()) {
            return Result.refused(asset, code, byName.get());
        }
        if (blocklist.existsByAssetSymbol(asset)) {
            return Result.refused(asset, code, IneligibilityReason.BLOCKLISTED);
        }

        List<InstrumentInfo> live;
        List<ExchangeInstrument> all;
        try {
            live = catalog.liveInstruments(provider);
            all = catalog.allInstruments(provider);
        } catch (InstrumentLookupException e) {
            log.warn("Éligibilité {} chez {} : catalogue indisponible ({})", asset, code, e.getMessage());
            return Result.refused(asset, code, IneligibilityReason.CATALOG_UNAVAILABLE);
        }
        if (!hasBuyRoute(live, asset)) {
            return Result.refused(asset, code, IneligibilityReason.NO_BUY_ROUTE);
        }

        Optional<BigDecimal> volume = usdVolume(all, asset);
        if (volume.isPresent() && volume.get().compareTo(settings.minVolumeUsd()) < 0) {
            return new Result(asset, code, false, IneligibilityReason.VOLUME_TOO_LOW, volume.get(), true, null);
        }
        if (volume.isEmpty() && !AssetEligibilityDefaults.UNKNOWN_VOLUME_ELIGIBLE) {
            return Result.refused(asset, code, IneligibilityReason.VOLUME_UNKNOWN);
        }

        OptionalInt days = historyProbe.closedDays(code, asset);
        if (days.isEmpty()) {
            return new Result(asset, code, false, IneligibilityReason.HISTORY_UNAVAILABLE, volume.orElse(null), volume.isPresent(), null);
        }
        if (days.getAsInt() < AssetEligibilityDefaults.MIN_D1_DAYS) {
            return new Result(asset, code, false, IneligibilityReason.HISTORY_TOO_SHORT, volume.orElse(null), volume.isPresent(), days.getAsInt());
        }
        return new Result(asset, code, true, null, volume.orElse(null), volume.isPresent(), days.getAsInt());
    }

    /** Éligible, sinon {@link AssetIneligibleException} (400 côté API, raison lisible). */
    public Result require(String assetSymbol, WebProvider provider) {
        Result result = check(assetSymbol, provider);
        if (!result.eligible()) {
            throw new AssetIneligibleException(result);
        }
        return result;
    }

    /** Route d'achat depuis USDC (≤ 2 jambes, passerelle comprise) sur ces paires live : source unique, aussi utilisée pour la suspension d'un lien. */
    public boolean hasBuyRoute(List<InstrumentInfo> live, String asset) {
        return pathFinder.routes(live, forbiddenNodes, Set.of(ExecutionDefaults.CASH_STABLE), asset, ExecutionDefaults.MAX_PATH_DEPTH,
                ExecutionDefaults.displayOnlyIntermediaries()).stream().anyMatch(PathFinder.RouteRef::chosen);
    }

    /** Somme des volumes 24 h (cotation USD) des paires live de l'actif, lus depuis moins de {@link AssetEligibilityDefaults#VOLUME_MAX_AGE} ; vide si aucune. */
    public Optional<BigDecimal> usdVolume(List<ExchangeInstrument> all, String asset) {
        return Optional.ofNullable(usdVolumes(all.stream().filter(i -> asset.equals(i.getBase())).toList()).get(asset));
    }

    /** Volume 24 h (USD) par actif de base en UNE passe sur les paires : mêmes règles que {@link #usdVolume} ; un actif sans volume exploitable est absent. */
    public java.util.Map<String, BigDecimal> usdVolumes(List<ExchangeInstrument> all) {
        Instant oldest = clock.now().minus(AssetEligibilityDefaults.VOLUME_MAX_AGE);
        return all.stream()
                .filter(i -> InstrumentCatalog.LIVE_STATE.equals(i.getState()) && AssetEligibilityDefaults.USD_QUOTES.contains(i.getQuote()))
                .filter(i -> i.getVolume24hQuote() != null && i.getVolumeReadAt() != null && !i.getVolumeReadAt().isBefore(oldest))
                .collect(java.util.stream.Collectors.toMap(ExchangeInstrument::getBase, ExchangeInstrument::getVolume24hQuote, BigDecimal::add));
    }
}
