package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Page « Exchanges » de l'utilisateur connecté : état de ses clés LECTURE / ÉCRITURE par exchange (jamais de secret), test de la
 * clé de lecture, suppression d'une clé ou de l'exchange entier. La saisie des clés reste dans {@code ReadCredentialService} et
 * {@code TradeCredentialService}. Supprimer la clé de lecture archive les liens (actifs gérés) du wallet et le désactive ;
 * supprimer la clé d'écriture coupe l'exécution réelle de ces liens.
 */
@Slf4j
@Service
public class ExchangeKeysService {

    /** En dessous de cette longueur, aucun fragment de la clé n'est montré. */
    private static final int HINT_MIN_KEY_LENGTH = 8;
    private static final int HINT_LENGTH = 4;

    /** État d'une clé : {@code hint} = 4 derniers caractères de la clé de lecture (nul pour la clé d'écriture, chiffrée). */
    public record KeyState(boolean configured, boolean enabled, String hint) {
        static final KeyState ABSENT = new KeyState(false, false, null);
    }

    public record SupportedExchange(WebProviderCode provider, boolean needsPassphrase, boolean needsSecret) {
    }

    public record ExchangeView(WebProviderCode provider, boolean needsPassphrase, KeyState read, KeyState trade, Long walletId,
                               List<String> linkedAssets) {
    }

    public record TestResult(boolean ok, String message) {
    }

    private final ApiCredentialRepository credentialRepository;
    private final ProviderRepository providerRepository;
    private final WalletRepository walletRepository;
    private final RainbowLiveBindingService bindingService;
    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers = new EnumMap<>(WebProviderCode.class);

    public ExchangeKeysService(ApiCredentialRepository credentialRepository, ProviderRepository providerRepository,
                               WalletRepository walletRepository, RainbowLiveBindingService bindingService,
                               List<ReadOnlyBalanceReader> readerList) {
        this.credentialRepository = credentialRepository;
        this.providerRepository = providerRepository;
        this.walletRepository = walletRepository;
        this.bindingService = bindingService;
        readerList.forEach(r -> readers.put(r.getProviderCode(), r));
    }

    /** Exchanges proposés à l'ajout (ceux qui disposent d'un client d'ordres). */
    public List<SupportedExchange> supported() {
        return TradeCredentialService.TRADABLE_PROVIDERS.stream().sorted(Comparator.comparing(Enum::name))
                .map(p -> new SupportedExchange(p, TradeCredentialService.PASSPHRASE_PROVIDERS.contains(p),
                        !TradeCredentialService.KEY_ONLY_PROVIDERS.contains(p))).toList();
    }

    /** Exchanges de l'utilisateur : ceux où au moins une clé (lecture ou écriture) est enregistrée. */
    @Transactional(readOnly = true)
    public List<ExchangeView> list(User user) {
        List<RainbowLiveBinding> bindings = bindingService.list(user).stream().filter(b -> !b.isArchived()).toList();
        return supported().stream().map(s -> view(user, s, bindings)).flatMap(Optional::stream).toList();
    }

    private Optional<ExchangeView> view(User user, SupportedExchange supported, List<RainbowLiveBinding> bindings) {
        Optional<WebProvider> provider = providerRepository.findByCode(supported.provider());
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        KeyState read = credentialRepository.findReadByUserAndWebProvider(user, provider.get()).map(c -> state(c, true)).orElse(KeyState.ABSENT);
        KeyState trade = credentialRepository.findByUserAndWebProviderAndScope(user, provider.get(), CredentialScope.TRADE)
                .map(c -> state(c, false)).orElse(KeyState.ABSENT);
        if (!read.configured() && !trade.configured()) {
            return Optional.empty();
        }
        Wallet wallet = walletRepository.findByUserAndName(user, supported.provider().name()).orElse(null);
        List<String> linked = wallet == null ? List.of() : bindings.stream()
                .filter(b -> b.getWallet() != null && wallet.getId().equals(b.getWallet().getId()))
                .map(RainbowLiveBinding::getAssetSymbol).distinct().sorted().toList();
        return Optional.of(new ExchangeView(supported.provider(), supported.needsPassphrase(), read, trade,
                wallet == null ? null : wallet.getId(), linked));
    }

    private static KeyState state(ApiCredential credential, boolean withHint) {
        String key = credential.getApiKey();
        String hint = withHint && key != null && key.length() >= HINT_MIN_KEY_LENGTH ? key.substring(key.length() - HINT_LENGTH) : null;
        return new KeyState(true, credential.isEnabled(), hint);
    }

    /** Une clé d'écriture active est-elle enregistrée pour cet exchange ? Sans elle, aucun ordre ne peut être passé. */
    @Transactional(readOnly = true)
    public boolean hasTradeKey(User user, WebProviderCode provider) {
        return provider != null && providerRepository.findByCode(provider)
                .flatMap(p -> credentialRepository.findByUserAndWebProviderAndScope(user, p, CredentialScope.TRADE))
                .map(ApiCredential::isEnabled).orElse(false);
    }

    /** Teste la clé de lecture (lecture réseau directe des soldes). Aucun ordre. */
    @Transactional(readOnly = true)
    public TestResult testRead(User user, WebProviderCode provider) {
        requireSupported(provider);
        Wallet wallet = walletRepository.findByUserAndName(user, provider.name()).orElse(null);
        ApiCredential credential = wallet == null ? null : wallet.getCredential();
        ReadOnlyBalanceReader reader = readers.get(provider);
        if (credential == null || !credential.isEnabled()) {
            return new TestResult(false, "Aucune clé de lecture active");
        }
        if (reader == null) {
            return new TestResult(false, "Exchange non pris en charge : " + provider);
        }
        try {
            reader.fetchAvailableBalances(credential);
            return new TestResult(true, "Connexion OK");
        } catch (BalanceUnavailableException e) {
            return new TestResult(false, e.getMessage() == null ? "Lecture impossible" : e.getMessage());
        }
    }

    /** Supprime une clé (idempotent). Lecture : liens archivés, wallet désactivé. Écriture : exécution réelle coupée. */
    @Transactional
    public void deleteKey(User user, WebProviderCode provider, CredentialScope scope) {
        requireSupported(provider);
        WebProvider webProvider = providerRepository.findByCode(provider).orElse(null);
        if (webProvider == null) {
            return;
        }
        ApiCredential credential = credentialRepository.findByUserAndWebProviderAndScope(user, webProvider, scope).orElse(null);
        Wallet wallet = walletRepository.findByUserAndName(user, provider.name()).orElse(null);
        if (scope == CredentialScope.READ) {
            if (wallet != null) {
                activeBindings(user, wallet).forEach(b -> bindingService.delete(user, b.getId()));
                wallet.setCredential(null);
                wallet.setEnabled(false);
                walletRepository.save(wallet);
            }
        } else if (wallet != null) {
            activeBindings(user, wallet).stream().filter(RainbowLiveBinding::isExecutionEnabled)
                    .forEach(b -> bindingService.setExecution(user, b.getId(), false));
        }
        if (credential != null) {
            credentialRepository.delete(credential);
            log.info("Clé {} supprimée user={} provider={}", scope, user.getId(), provider);
        }
    }

    /** Supprime l'exchange : les deux clés, les liens sont archivés. */
    @Transactional
    public void deleteExchange(User user, WebProviderCode provider) {
        deleteKey(user, provider, CredentialScope.TRADE);
        deleteKey(user, provider, CredentialScope.READ);
    }

    private List<RainbowLiveBinding> activeBindings(User user, Wallet wallet) {
        return bindingService.list(user).stream()
                .filter(b -> !b.isArchived() && b.getWallet() != null && wallet.getId().equals(b.getWallet().getId())).toList();
    }

    private static void requireSupported(WebProviderCode provider) {
        if (provider == null || !TradeCredentialService.TRADABLE_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Exchange non géré : " + provider);
        }
    }
}
