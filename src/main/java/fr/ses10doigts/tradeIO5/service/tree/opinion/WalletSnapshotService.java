package fr.ses10doigts.tradeIO5.service.tree.opinion;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.WalletSnapshot;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.WalletService;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import lombok.RequiredArgsConstructor;

/**
 * Construit un {@link WalletSnapshot} réel pour un utilisateur, en agrégeant tous ses wallets
 * actifs. Palier 2 (étape 3, 2026-08) : réutilise le même chemin que {@code AssetOverviewService}
 * ({@code ProviderApiService.getAllBalances}/{@code getMarketPrice}), plutôt que de redupliquer
 * cette logique d'agrégation multi-wallet.
 * <p>
 * {@code openPositions} et {@code investedValue} restent volontairement à leur valeur par défaut
 * (map vide / zéro) dans ce lot : les brancher demanderait de reproduire la logique
 * {@code TransactionService} déjà utilisée par {@code AssetOverviewService} (historique de
 * transactions), hors scope du socle multi-utilisateur tant que le Sizing (étude §4/§7) n'existe
 * pas pour en avoir besoin.
 */
@Service
@RequiredArgsConstructor
public class WalletSnapshotService {

    private final WalletService walletService;
    private final ProviderApiService providerApiService;

    public WalletSnapshot buildSnapshot(User user, String quoteCurrency) {
        List<Wallet> wallets = walletService.getWalletsByUser(user);

        Map<String, Double> balances = new HashMap<>();
        double totalValue = 0;

        for (Wallet wallet : wallets) {
            Map<String, BigDecimal> walletBalances = providerApiService.getAllBalances(wallet);

            for (Map.Entry<String, BigDecimal> entry : walletBalances.entrySet()) {
                String asset = entry.getKey();
                BigDecimal quantity = entry.getValue();

                if (quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }

                balances.merge(asset, quantity.doubleValue(), Double::sum);

                BigDecimal marketPrice = providerApiService.getMarketPrice(wallet, asset, quoteCurrency);
                if (asset.endsWith("USDC")) {
                    // Même traitement spécial que AssetOverviewService : USDC/EUR ~ 1, pour ne pas
                    // diverger de la valorisation déjà utilisée ailleurs dans l'app.
                    marketPrice = BigDecimal.ONE;
                }
                if (marketPrice != null) {
                    totalValue += marketPrice.multiply(quantity).doubleValue();
                }
            }
        }

        return WalletSnapshot.builder()
                .balances(balances)
                .openPositions(Map.of())
                .totalValue(totalValue)
                .investedValue(0)
                .build();
    }
}
