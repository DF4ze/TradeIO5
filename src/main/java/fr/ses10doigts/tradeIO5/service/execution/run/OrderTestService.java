package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto.LocalCheck;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto.Limits;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto.NormalizedRequest;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import fr.ses10doigts.tradeIO5.service.execution.exchange.ExchangeUnavailableException;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderRequest;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderValidator;
import fr.ses10doigts.tradeIO5.service.execution.exchange.TradeCredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.execution.exchange.ValidationReport;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Testeur d'ordre : <b>valide</b> un ordre limit IOC sans jamais l'envoyer ; seul appelant du {@link SpotOrderValidator}
 * (contrôleurs -> service admin -> ce service -> {@code exchange}). Il n'appelle jamais {@code SpotOrderPort.submit}.
 * <ol>
 *   <li>construit l'ordre : arrondis au lot (taille, vers le bas) et au tick (prix : BUY vers le bas, SELL vers le haut,
 *       jamais plus défavorable que la saisie) ;</li>
 *   <li>contrôles <b>locaux</b>, toujours calculés : paire connue et négociable, minimum de taille, coût minimum, lot / tick,
 *       plafond absolu, fiat interdit, exchange avec client d'ordres ;</li>
 *   <li>verrous globaux, les mêmes que pour un vrai ordre (aucun passe-droit) : mode LIVE, déverrouillage, clé maître, kill
 *       switch levé, port de l'exchange, clé Trade déchiffrée ; un verrou non levé => {@code blockedBy} et <b>aucun appel
 *       exchange</b> ;</li>
 *   <li>validation par l'exchange (Kraken {@code validate}) ; OKX : contrôles locaux seuls.</li>
 * </ol>
 */
@Slf4j
@Service
public class OrderTestService {

    private static final String CLORDID_PREFIX = "t5test";
    private static final int CLORDID_RADIX = 36;
    private static final String LIVE_STATE = "live";

    private final ExecutionSettings settings;
    private final ExecutionControlService control;
    private final SecretCipher cipher;
    private final SpotOrderRouter router;
    private final Map<WebProviderCode, SpotOrderValidator> validators = new EnumMap<>(WebProviderCode.class);
    private final TradeCredentialResolver credentials;
    private final InstrumentCatalog catalog;
    private final ProviderRepository providers;
    private final UserRepository users;
    private final DomainClock clock;
    private final Set<String> forbiddenNodes;

    public OrderTestService(ExecutionSettings settings, ExecutionControlService control, SecretCipher cipher,
                            SpotOrderRouter router, List<SpotOrderValidator> validators, TradeCredentialResolver credentials,
                            InstrumentCatalog catalog, ProviderRepository providers, UserRepository users, DomainClock clock,
                            @Value("${" + ExecutionDefaults.FORBIDDEN_NODES_PROPERTY + ":}") String forbiddenNodes) {
        this.settings = settings;
        this.control = control;
        this.cipher = cipher;
        this.router = router;
        validators.forEach(v -> this.validators.put(v.provider(), v));
        this.credentials = credentials;
        this.catalog = catalog;
        this.providers = providers;
        this.users = users;
        this.clock = clock;
        this.forbiddenNodes = ExecutionDefaults.forbiddenNodes(forbiddenNodes);
    }

    /**
     * @throws IllegalArgumentException demande incohérente, utilisateur ou exchange inconnu
     */
    public ValidationReportDto validate(OrderTestRequest request) {
        requireWellFormed(request);
        User user = users.findById(request.userId()).orElseThrow(() -> new IllegalArgumentException("Utilisateur inconnu : " + request.userId()));
        WebProvider webProvider = providers.findByCode(request.provider())
                .orElseThrow(() -> new IllegalArgumentException("Exchange inconnu : " + request.provider()));
        List<LocalCheck> checks = new ArrayList<>();

        checks.add(new LocalCheck("provider_tradable", TradeCredentialService.TRADABLE_PROVIDERS.contains(request.provider()),
                TradeCredentialService.TRADABLE_PROVIDERS.contains(request.provider()) ? "client d'ordres prévu pour " + request.provider()
                        : "aucun client d'ordres pour " + request.provider()));
        checks.add(new LocalCheck("no_fiat", noFiat(request.instId()), "nœuds interdits : " + forbiddenNodes));

        ExchangeInstrument instrument = findInstrument(webProvider, request.instId(), checks);
        Limits limits = instrument == null ? null : new Limits(instrument.getMinSz(), instrument.getCostMin(), instrument.getLotSz(),
                instrument.getTickSz(), instrument.getFeeCurrency());
        OrderRequest order = null;
        NormalizedRequest normalized = null;
        if (instrument != null) {
            Normalized n = normalize(request, instrument, checks);
            normalized = n.view();
            order = n.order(clock);
        }

        Optional<ExecutionBlockReason> blocked = lockBlock(request.provider());
        Optional<ApiCredential> trade = blocked.isPresent() ? Optional.empty() : credentials.resolve(user, request.provider());
        if (blocked.isEmpty() && trade.isEmpty()) {
            blocked = Optional.of(ExecutionBlockReason.CREDENTIAL_INVALID);
        }
        if (blocked.isPresent()) {
            log.info("Testeur d'ordre : bloqué ({}) user={} {} {}", blocked.get(), user.getId(), request.provider(), request.instId());
            return new ValidationReportDto(false, blocked.get().name(), checks, normalized, null, null, null, limits);
        }
        boolean structural = checks.stream().filter(c -> !c.ok()).map(LocalCheck::name).noneMatch(STRUCTURAL::contains);
        if (order == null || !structural) {
            return new ValidationReportDto(false, null, checks, normalized, null,
                    "Contrôles locaux en échec : aucun appel à l'exchange.", null, limits);
        }
        ValidationReport report = callValidator(request.provider(), trade.get(), order);
        boolean localOk = checks.stream().allMatch(LocalCheck::ok);
        return new ValidationReportDto(report.accepted() && localOk, null, checks, normalized, report.exchangeCode(), report.message(),
                report.rawFiltered(), limits);
    }

    /** Contrôles dont l'échec empêche de construire un ordre exploitable : on ne les envoie pas à l'exchange. */
    private static final Set<String> STRUCTURAL = Set.of("provider_tradable", "no_fiat", "pair_known", "pair_live", "price_required",
            "size_positive");

    // ------------------------------------------------------------------ verrous

    private Optional<ExecutionBlockReason> lockBlock(WebProviderCode provider) {
        if (!settings.liveExecution()) {
            return Optional.of(ExecutionBlockReason.MODE_NOT_LIVE);
        }
        if (!settings.isLiveUnlocked()) {
            return Optional.of(ExecutionBlockReason.LIVE_LOCKED);
        }
        if (!cipher.isConfigured()) {
            return Optional.of(ExecutionBlockReason.MASTER_KEY_MISSING);
        }
        if (control.current().isKillSwitch()) {
            return Optional.of(ExecutionBlockReason.KILL_SWITCH);
        }
        if (router.forProvider(provider).isEmpty() || !validators.containsKey(provider)) {
            return Optional.of(ExecutionBlockReason.PORT_UNAVAILABLE);
        }
        return Optional.empty();
    }

    private ValidationReport callValidator(WebProviderCode provider, ApiCredential credential, OrderRequest order) {
        try {
            return validators.get(provider).validate(credential, order);
        } catch (TradeCredentialRejectedException e) {
            return ValidationReport.refused("CREDENTIAL_REJECTED", e.getMessage(), null);
        } catch (ExchangeUnavailableException e) {
            return ValidationReport.refused("EXCHANGE_UNAVAILABLE", e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ contrôles locaux

    private boolean noFiat(String instId) {
        return Arrays.stream(instId.split("-")).map(String::toUpperCase).noneMatch(forbiddenNodes::contains);
    }

    private ExchangeInstrument findInstrument(WebProvider provider, String instId, List<LocalCheck> checks) {
        List<ExchangeInstrument> all;
        try {
            all = catalog.allInstruments(provider);
        } catch (InstrumentLookupException e) {
            checks.add(new LocalCheck("pair_known", false, "catalogue indisponible : " + e.getMessage()));
            return null;
        }
        Map<String, ExchangeInstrument> byId = all.stream().collect(Collectors.toMap(ExchangeInstrument::getInstId, Function.identity(), (a, b) -> a));
        ExchangeInstrument instrument = byId.get(instId.toUpperCase());
        checks.add(new LocalCheck("pair_known", instrument != null, instrument != null ? instId.toUpperCase() + " au catalogue"
                : instId + " absente du catalogue " + provider.getCode()));
        if (instrument == null) {
            return null;
        }
        checks.add(new LocalCheck("pair_live", LIVE_STATE.equals(instrument.getState()), "état " + instrument.getState()));
        return instrument;
    }

    private record Normalized(NormalizedRequest view, BigDecimal sz, BigDecimal px, boolean valid) {
        OrderRequest order(DomainClock clock) {
            if (!valid || view == null) {
                return null;
            }
            return new OrderRequest(view.instId(), StepSide.valueOf(view.side()), StepOrderType.LIMIT_IOC, sz, px,
                    CLORDID_PREFIX + Long.toString(clock.now().toEpochMilli(), CLORDID_RADIX));
        }
    }

    private Normalized normalize(OrderTestRequest request, ExchangeInstrument i, List<LocalCheck> checks) {
        boolean buy = request.side() == StepSide.BUY;
        BigDecimal px = request.px();
        BigDecimal pxRounded = px == null || px.signum() <= 0 ? null
                : PathFinder.roundToStep(px, i.getTickSz(), buy ? RoundingMode.DOWN : RoundingMode.UP);
        checks.add(new LocalCheck("price_required", pxRounded != null && pxRounded.signum() > 0,
                pxRounded == null ? "plafond de prix requis (strictement positif)" : "plafond " + plain(pxRounded)));
        BigDecimal wanted = request.sz() != null ? request.sz()
                : (pxRounded == null || pxRounded.signum() == 0 ? BigDecimal.ZERO
                        : request.quoteAmount().divide(pxRounded, 18, RoundingMode.DOWN));
        BigDecimal sz = PathFinder.roundToStep(wanted, i.getLotSz(), RoundingMode.DOWN);
        checks.add(new LocalCheck("size_positive", sz.signum() > 0, "taille " + plain(sz) + " (lot " + plain(i.getLotSz()) + ")"));
        checks.add(new LocalCheck("lot_tick", true, (sz.compareTo(wanted) == 0 ? "taille sans arrondi" : "taille arrondie au lot de "
                + plain(wanted) + " à " + plain(sz)) + " ; " + (pxRounded == null || pxRounded.compareTo(px) == 0 ? "prix sans arrondi"
                : "prix arrondi au tick de " + plain(px) + " à " + plain(pxRounded))));
        checks.add(new LocalCheck("min_size", sz.compareTo(i.getMinSz()) >= 0, plain(sz) + " ≥ minimum " + plain(i.getMinSz())));
        BigDecimal cost = pxRounded == null ? null : sz.multiply(pxRounded);
        if (i.getCostMin() != null) {
            checks.add(new LocalCheck("cost_min", cost != null && cost.compareTo(i.getCostMin()) >= 0,
                    "coût " + plain(cost) + " ≥ minimum " + plain(i.getCostMin()) + " " + i.getQuote()));
        }
        if (pxRounded != null) {
            BigDecimal minCost = i.getMinSz().multiply(pxRounded);
            boolean impossible = minCost.compareTo(ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD) > 0;
            checks.add(new LocalCheck("cap_absolute_order", cost.compareTo(ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD) <= 0,
                    "coût " + plain(cost) + " ≤ plafond absolu " + plain(ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD) + " (cotation assimilée à l'USD)"
                            + (impossible ? " ; minimum de la paire (≈ " + plain(minCost) + ") > plafond absolu : aucun ordre possible sur cette paire"
                            : "")));
        }
        boolean valid = pxRounded != null && pxRounded.signum() > 0 && sz.signum() > 0;
        NormalizedRequest view = new NormalizedRequest(i.getInstId(), request.side().name(), StepOrderType.LIMIT_IOC.name(), sz,
                pxRounded, cost);
        return new Normalized(view, sz, pxRounded, valid);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString();
    }

    private static void requireWellFormed(OrderTestRequest r) {
        if (r == null || r.userId() == null || r.provider() == null || r.side() == null || r.instId() == null || r.instId().isBlank()) {
            throw new IllegalArgumentException("userId, provider, instId et side sont requis");
        }
        if ((r.sz() == null) == (r.quoteAmount() == null)) {
            throw new IllegalArgumentException("Fournir exactement une taille (sz) ou un montant en cotation (quoteAmount)");
        }
        if ((r.sz() != null && r.sz().signum() <= 0) || (r.quoteAmount() != null && r.quoteAmount().signum() <= 0)) {
            throw new IllegalArgumentException("sz / quoteAmount doivent être strictement positifs");
        }
    }
}
