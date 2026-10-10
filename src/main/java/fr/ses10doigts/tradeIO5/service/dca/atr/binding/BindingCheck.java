package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.SpotInstrumentChecker;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Le wallet d'un binding permet-il de trader l'actif ? Credential valide, soldes lisibles (lecture seule), paire
 * {@code <actif>/USDC} existante (endpoint public). Un solde nul est accepté et une clé absente de la map signifie
 * « solde 0 », pas « indisponible » (contrat de {@link ReadOnlyBalanceReader}). Les autres soldes ne sont ni
 * conservés ni exposés. Aucun cas métier ne lève d'exception.
 */
@Slf4j
@Component
public class BindingCheck {

    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers;
    private final Map<WebProviderCode, SpotInstrumentChecker> instrumentCheckers;

    public BindingCheck(List<ReadOnlyBalanceReader> readers, List<SpotInstrumentChecker> instrumentCheckers) {
        this.readers = readers.stream()
                .collect(Collectors.toMap(ReadOnlyBalanceReader::getProviderCode, Function.identity()));
        this.instrumentCheckers = instrumentCheckers.stream()
                .collect(Collectors.toMap(SpotInstrumentChecker::getProviderCode, Function.identity()));
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
        SpotInstrumentChecker instruments = provider == null ? null : instrumentCheckers.get(provider);
        if (reader == null || instruments == null) {
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
            if (!instruments.isTradable(credential.getWebProvider(), assetSymbol, RainbowLiveDefaultPresets.STABLECOIN)) {
                return ko(BindingCheckStatus.INSTRUMENT_MISSING, "Paire " + assetSymbol + "/"
                        + RainbowLiveDefaultPresets.STABLECOIN + " absente ou suspendue sur " + provider);
            }
        } catch (InstrumentLookupException e) {
            return ko(BindingCheckStatus.INSTRUMENT_UNAVAILABLE, e.getMessage());
        }
        return new BindingCheckResult(BindingCheckStatus.OK, "OK");
    }

    private static BindingCheckResult ko(BindingCheckStatus status, String message) {
        return new BindingCheckResult(status, message);
    }
}
