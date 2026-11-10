package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.keypair.ProviderKeyPairSupport;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Revolut X : paire Ed25519 générée côté serveur, clé publique enregistrée par l'utilisateur chez Revolut, clé API (64 car.) renvoyée en retour. */
@Component
public class RevolutXKeyPairSupport implements ProviderKeyPairSupport {

    private static final String ACTIVE_ORDERS_QUERY = "symbols=BTC-USDC&limit=1";

    private final RevolutXBalanceReader balances;

    public RevolutXKeyPairSupport(RevolutXBalanceReader balances) {
        this.balances = balances;
    }

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public Set<CredentialScope> scopes() {
        return Set.of(CredentialScope.READ, CredentialScope.TRADE);
    }

    @Override
    public String registrationHint() {
        return "Revolut X > Profil > Clés API : ajoutez la clé publique ci-dessus (droits lecture seule pour la clé READ, trading pour la clé TRADE, "
                + "jamais de retrait), restreignez à l'IP du serveur TradeIO, puis collez ici la clé API fournie par Revolut.";
    }

    @Override
    public void verify(ApiCredential candidate) {
        RevolutXRequests.requireComplete(candidate);
        if (candidate.getScope() == CredentialScope.TRADE) {
            RevolutXRequests.requireJson(RevolutXRequests.get(candidate, RevolutXRequests.ACTIVE_ORDERS_PATH, ACTIVE_ORDERS_QUERY),
                    "ordres actifs");
        } else {
            balances.fetchAvailableBalances(candidate);
        }
    }
}
