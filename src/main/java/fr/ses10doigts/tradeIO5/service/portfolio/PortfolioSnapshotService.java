package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioPriceBasis;
import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioSnapshot;
import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioSnapshotAsset;
import fr.ses10doigts.tradeIO5.repository.portfolio.PortfolioSnapshotRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Historisation quotidienne du patrimoine : un {@link PortfolioSnapshot} immuable par (utilisateur, wallet, jour UTC), écrit à
 * la passe 23:55 UTC avec les prix de la passe (aucun appel exchange ici). Idempotent : si l'instantané du jour existe, rien
 * n'est écrit. Pas de rattrapage des jours manquants ni de reconstruction rétroactive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioSnapshotService {

    private static final double EPSILON = 1e-12;

    private final PortfolioSnapshotRepository snapshotRepository;
    private final DomainClock clock;

    @Transactional(readOnly = true)
    public boolean exists(Long userId, Long walletId, LocalDate day) {
        return snapshotRepository.existsByUserIdAndWalletIdAndDay(userId, walletId, day);
    }

    /**
     * Enregistre l'instantané du jour du wallet ; sans effet s'il existe déjà.
     *
     * @param linkedAssets actifs liés du wallet (toujours présents dans l'instantané, même à quantité 0)
     * @param closes       clôture D1 du jour par actif (prix de valorisation)
     * @return l'instantané écrit, ou {@code null} s'il existait déjà
     */
    @Transactional
    public PortfolioSnapshot record(User user, Wallet wallet, LocalDate day, PortfolioReading reading,
                                    Collection<String> linkedAssets, Map<String, Double> closes, ModulationSettings settings) {
        if (snapshotRepository.existsByUserIdAndWalletIdAndDay(user.getId(), wallet.getId(), day)) {
            log.debug("Instantané patrimoine user={} wallet={} jour={} déjà écrit, conservé", user.getId(), wallet.getId(), day);
            return null;
        }
        PortfolioSnapshot snapshot = PortfolioSnapshot.builder()
                .user(user).wallet(wallet).day(day)
                .status(reading.status())
                .priceBasis(PortfolioPriceBasis.D1_OPEN_2355)
                .exposureMin(settings.exposureMin()).exposureMax(settings.exposureMax())
                .fetchedAt(reading.fetchedAt())
                .recordedAt(clock.now())
                .build();
        if (reading.isOk()) {
            fill(snapshot, reading, linkedAssets, closes);
        }
        PortfolioSnapshot saved = snapshotRepository.save(snapshot);
        log.info("Instantané patrimoine user={} wallet={} jour={} statut={} total={}", user.getId(), wallet.getId(), day,
                saved.getStatus(), saved.getTotalValue());
        return saved;
    }

    /** Valorise une lecture {@code OK} ; totaux et exposition restent nuls si un actif détenu n'a pas de prix (WARN). */
    private static void fill(PortfolioSnapshot snapshot, PortfolioReading reading, Collection<String> linkedAssets,
                             Map<String, Double> closes) {
        Map<String, Double> quantities = new TreeMap<>(reading.positions());
        linkedAssets.forEach(asset -> quantities.putIfAbsent(asset, 0.0));
        double assets = 0.0;
        boolean priced = true;
        for (Map.Entry<String, Double> position : quantities.entrySet()) {
            double qty = position.getValue();
            Double close = closes.get(position.getKey());
            boolean hasPrice = close != null && close > 0.0;
            if (qty > EPSILON && !hasPrice) {
                priced = false;
                log.warn("Instantané patrimoine wallet={} : prix manquant pour {}, totaux non renseignés",
                        snapshot.getWallet().getId(), position.getKey());
            }
            if (qty > EPSILON && hasPrice) {
                assets += qty * close;
            }
            if (qty <= EPSILON && !linkedAssets.contains(position.getKey())) {
                continue;
            }
            snapshot.getAssets().add(PortfolioSnapshotAsset.builder().snapshot(snapshot)
                    .assetSymbol(position.getKey()).quantity(qty)
                    .price(hasPrice ? close : null)
                    .value(hasPrice ? qty * close : null).build());
        }
        snapshot.setCashUsd(reading.cash());
        if (priced) {
            double total = assets + reading.cash();
            snapshot.setAssetsValue(assets);
            snapshot.setTotalValue(total);
            snapshot.setExposurePct(total > 0.0 ? assets / total * 100.0 : null);
        }
    }
}
