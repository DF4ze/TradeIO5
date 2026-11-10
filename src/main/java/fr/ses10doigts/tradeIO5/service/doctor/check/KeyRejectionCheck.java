package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.keypair.ProviderKeyPairSupport;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clés à paire générée (moteur {@code ProviderKeyPairService}, aujourd'hui Revolut X) refusées par l'exchange : clé révoquée,
 * restriction d'IP, droits retirés. Chaque credential active est sondée pour de vrai ({@link ProviderKeyPairSupport#verify}), au plus
 * une fois par {@link DoctorDefaults#KEY_PROBE_INTERVAL_MINUTES} min ; entre deux sondes le dernier verdict est resservi. Clé refusée =
 * CRIT ; exchange injoignable = verdict précédent conservé (inconnu si aucun) ; une credential disparue ou désactivée résout son alarme.
 */
@Component
public class KeyRejectionCheck implements DoctorCheck {

    private record Verdict(CheckResult result, Instant probedAt) {
    }

    private final ApiCredentialRepository credentials;
    private final TradeCredentialResolver tradeResolver;
    private final DomainClock clock;
    private final Map<WebProviderCode, ProviderKeyPairSupport> supports = new java.util.EnumMap<>(WebProviderCode.class);
    private final Map<Long, Verdict> verdicts = new ConcurrentHashMap<>();

    public KeyRejectionCheck(ApiCredentialRepository credentials, TradeCredentialResolver tradeResolver, DomainClock clock,
                             List<ProviderKeyPairSupport> supportList) {
        this.credentials = credentials;
        this.tradeResolver = tradeResolver;
        this.clock = clock;
        supportList.forEach(s -> supports.put(s.provider(), s));
    }

    @Override
    public String id() {
        return "KEY_REJECTED";
    }

    @Override
    public String label() {
        return "Clés d'exchange à paire générée refusées (révoquées, IP, droits)";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.EXECUTION;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    public List<CheckResult> run() {
        if (supports.isEmpty()) {
            return List.of();
        }
        Instant now = clock.now();
        List<CheckResult> results = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (ApiCredential stored : credentials.findByEnabledTrueAndWebProvider_CodeInAndWebProvider_EnabledTrue(
                EnumSet.copyOf(supports.keySet()))) {
            seen.add(stored.getId());
            Verdict last = verdicts.get(stored.getId());
            if (last == null || !now.isBefore(last.probedAt().plus(Duration.ofMinutes(DoctorDefaults.KEY_PROBE_INTERVAL_MINUTES)))) {
                last = probe(stored, last, now);
                verdicts.put(stored.getId(), last);
            }
            results.add(last.result());
        }
        verdicts.keySet().retainAll(seen);
        return results;
    }

    private Verdict probe(ApiCredential stored, Verdict previous, Instant now) {
        WebProviderCode code = stored.getWebProvider().getCode();
        String subject = code + "/" + stored.getScope() + " user#" + stored.getUser().getId();
        Optional<ApiCredential> usable = stored.getScope() == CredentialScope.TRADE
                ? tradeResolver.resolve(stored.getUser(), code) : Optional.of(stored);
        if (usable.isEmpty()) {
            return new Verdict(CheckResult.crit(subject, "Clé illisible (clé maître absente ou valeur altérée) : sonde impossible"), now);
        }
        try {
            supports.get(code).verify(usable.get());
            return new Verdict(CheckResult.ok(subject, "Clé acceptée par " + code), now);
        } catch (CredentialRejectedException e) {
            return new Verdict(CheckResult.crit(subject, "Clé refusée par " + code + " : " + e.getMessage()
                    + " (régénérer la clé depuis la page Exchanges)"), now);
        } catch (BalanceUnavailableException e) {
            CheckResult kept = previous != null ? previous.result() : CheckResult.unknown(subject, "Sonde impossible : " + e.getMessage());
            return new Verdict(kept, now);
        }
    }
}
