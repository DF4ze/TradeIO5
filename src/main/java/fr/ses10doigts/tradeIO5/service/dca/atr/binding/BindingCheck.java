package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Le wallet d'un binding permet-il de trader l'actif ? Credential valide, soldes lisibles (lecture seule), chemin
 * spot sans fiat vers l'actif via le groupe USD (catalogue d'instruments en base). Un solde nul est accepté et une clé absente de la map signifie
 * « solde 0 », pas « indisponible » (contrat de {@link ReadOnlyBalanceReader}). Les autres soldes ne sont ni
 * conservés ni exposés. Aucun cas métier ne lève d'exception.
 */
@Slf4j
@Component
public class BindingCheck {

    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers;
    private final InstrumentCatalog catalog;
    private final AssetGroupService groupService;

    public BindingCheck(List<ReadOnlyBalanceReader> readers, InstrumentCatalog catalog, AssetGroupService groupService) {
        this.catalog = catalog;
        this.groupService = groupService;
        this.readers = readers.stream()
                .collect(Collectors.toMap(ReadOnlyBalanceReader::getProviderCode, Function.identity()));
    }

    public BindingCheckResult check(RainbowLiveBinding binding) {
        return check(binding.getWallet(), binding.getAssetSymbol());
    }

    public BindingCheckResult check(Wallet wallet, String assetSymbol) {
        BindingCheckResult result = doCheck(wallet, assetSymbol);
        log.info("BindingCheck {} wallet={} : {}", assetSymbol, wallet.getId(), result.status());
        log.debug("BindingCheck {} wallet={} message={}", assetSymbol, wallet.getId(), result.message());
        return result;
    }

    private BindingCheckResult doCheck(Wallet wallet, String assetSymbol) {
        if (!wallet.isEnabled()) {
            return ko(BindingCheckStatus.WALLET_DISABLED, "Wallet désactivé");
        }
        ApiCredential credential = wallet.getCredential();
        if (credential == null || !credential.isEnabled()) {
            return ko(BindingCheckStatus.CREDENTIAL_INVALID, "Aucune credential active sur ce wallet");
        }
        WebProviderCode provider = wallet.getWebProviderCode();
        ReadOnlyBalanceReader reader = provider == null ? null : readers.get(provider);
        if (reader == null || !catalog.supports(provider)) {
            return ko(BindingCheckStatus.PROVIDER_UNSUPPORTED, "Exchange non pris en charge : " + provider);
        }

        try {
            reader.getAvailableBalances(credential);
        } catch (CredentialRejectedException e) {
            return ko(BindingCheckStatus.CREDENTIAL_INVALID, e.getMessage());
        } catch (BalanceUnavailableException e) {
            return ko(BindingCheckStatus.BALANCE_UNAVAILABLE, e.getMessage());
        }

        try {
            return checkPath(credential.getWebProvider(), assetSymbol, provider);
        } catch (InstrumentLookupException e) {
            return ko(BindingCheckStatus.INSTRUMENT_UNAVAILABLE, e.getMessage());
        }
    }

    /**
     * Chemin spot sans fiat vers l'actif, membres du groupe USD par ordre de préférence : paire directe avec le membre
     * préféré => OK ; sinon paire avec un autre membre ET paire de passerelle (préféré/autre) => VIA_BRIDGE ; sinon
     * NOT_TRADABLE_WITHOUT_FIAT.
     */
    private BindingCheckResult checkPath(WebProvider webProvider, String assetSymbol, WebProviderCode provider) {
        List<String> members = groupService.members(AssetGroup.USD);
        if (members.isEmpty()) {
            return ko(BindingCheckStatus.INSTRUMENT_UNAVAILABLE, "Groupe " + AssetGroup.USD + " absent");
        }
        String preferred = members.getFirst();
        if (catalog.isTradable(webProvider, assetSymbol, preferred)) {
            return new BindingCheckResult(BindingCheckStatus.OK, "OK", preferred, null);
        }
        for (String other : members.subList(1, members.size())) {
            if (catalog.isTradable(webProvider, assetSymbol, other)
                    && catalog.isTradable(webProvider, preferred, other)) {
                return new BindingCheckResult(BindingCheckStatus.TRADABLE_VIA_BRIDGE, "Via passerelle " + preferred + "/"
                        + other + " puis " + assetSymbol + "/" + other + " sur " + provider, other,
                        preferred + "/" + other);
            }
        }
        return ko(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, assetSymbol + " non tradable sur " + provider
                + " sans passer par une monnaie fiat : exécution bloquée");
    }

    private static BindingCheckResult ko(BindingCheckStatus status, String message) {
        return new BindingCheckResult(status, message);
    }
}
