package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.portfolio.AssetPerformanceDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.AutonomyDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.BindingTradesDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioPointDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioSummaryDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioSnapshot;
import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioSnapshotAsset;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.portfolio.PortfolioSnapshotRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lecture du patrimoine et de la performance, <b>base seule</b> (instantanés quotidiens et transactions ; aucun appel exchange),
 * scopée à l'utilisateur. PnL de période = variation de valeur entre deux instantanés (dépôts/retraits non suivis) ; PnL d'actif =
 * réalisé + latent en prix moyen pondéré (cf. {@link PnlLedger}).
 */
@Service
@RequiredArgsConstructor
public class PortfolioQueryService {

    private static final double EPSILON = 1e-12;

    private final PortfolioSnapshotRepository snapshotRepository;
    private final TransactionRepository transactionRepository;
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLivePresetConfigResolver configResolver;
    private final DomainClock clock;

    @Transactional(readOnly = true)
    public PortfolioSummaryDto summary(User user, PortfolioPeriod period) {
        LocalDate today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate from = period.from(today);
        List<PortfolioPointDto> series = series(snapshotRepository.findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), from, today));
        PortfolioPointDto current = series.isEmpty() ? null : series.getLast();
        return new PortfolioSummaryDto(period.code(), from, today, current, pnl(series), series, autonomyDays(user), false);
    }

    @Transactional(readOnly = true)
    public List<AssetPerformanceDto> assets(User user) {
        Map<String, double[]> held = new TreeMap<>();   // symbole -> {quantité, valeur, prix, valeurConnue(0/1)}
        for (PortfolioSnapshot snapshot : snapshotRepository.latestByUser(user.getId())) {
            for (PortfolioSnapshotAsset a : snapshot.getAssets()) {
                double[] h = held.computeIfAbsent(a.getAssetSymbol(), k -> new double[]{0, 0, Double.NaN, 1});
                h[0] += a.getQuantity();
                if (a.getValue() == null && a.getQuantity() > EPSILON) {
                    h[3] = 0;
                } else if (a.getValue() != null) {
                    h[1] += a.getValue();
                }
                if (a.getPrice() != null) {
                    h[2] = a.getPrice();
                }
            }
        }
        List<AssetPerformanceDto> out = new ArrayList<>();
        held.forEach((symbol, h) -> out.add(performance(symbol, h, transactionRepository.findByUserAndAssetOrderByTimestampAsc(user, symbol))));
        return out;
    }

    /** Achats/ventes réels d'un lien de l'utilisateur ; lien inconnu ou d'un autre utilisateur ⇒ 404. */
    @Transactional(readOnly = true)
    public BindingTradesDto trades(User user, Long bindingId) {
        RainbowLiveBinding binding = bindingRepository.findById(bindingId)
                .filter(b -> b.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.binding(bindingId));
        List<Transaction> txs = transactionRepository.findByWalletAndAssetOrderByTimestampAsc(binding.getWallet(), binding.getAssetSymbol());
        List<BindingTradesDto.Trade> trades = txs.stream().map(t -> new BindingTradesDto.Trade(t.getId(), t.getTimestamp(),
                t.getSide().name(), t.getQuantity(), t.getPrice(), t.getFee(), t.getFeeCurrency())).toList();
        return new BindingTradesDto(binding.getId(), binding.getAssetSymbol(), avgBuyPrice(txs), trades);
    }

    private static AssetPerformanceDto performance(String symbol, double[] h, List<Transaction> txs) {
        double invested = 0;
        for (Transaction t : txs) {
            double notional = t.getQuantity().multiply(t.getPrice()).doubleValue();
            invested += t.getSide() == TradeSide.BUY ? notional : -notional;
        }
        PnlLedger.Result ledger = ledger(txs);
        boolean priced = h[3] == 1 || h[0] <= EPSILON;
        Double value = priced ? h[1] : null;
        Double price = Double.isNaN(h[2]) ? null : h[2];
        Double avg = ledger.avgPrice();
        boolean known = priced && avg != null && avg > EPSILON && price != null;
        Double pnl = known ? h[0] * (price - avg) : null;
        Double pct = known && h[0] * avg > EPSILON ? pnl / (h[0] * avg) * 100.0 : null;
        Double realizedPct = ledger.soldCost() > EPSILON ? ledger.realized() / ledger.soldCost() * 100.0 : null;
        Double total = pnl == null && ledger.sells() == 0 ? null : ledger.realized() + (pnl == null ? 0 : pnl);
        return new AssetPerformanceDto(symbol, h[0], price, value, invested, pnl, pct, avg, ledger.realized(), realizedPct, total,
                ledger.fees(), ledger.volume(), ledger.bought(), ledger.sells(), ledger.incomplete(), List.of());
    }

    /** Rejeu des trades en PRU (devise de cotation supposée dollar ; frais pris seulement en stable ou dans l'actif). */
    private static PnlLedger.Result ledger(List<Transaction> txs) {
        return PnlLedger.replay(txs.stream().map(t -> PnlLedger.fill(t, t.getAsset(), 1.0,
                t.getFeeCurrency() != null && PnlLedger.STABLES.contains(t.getFeeCurrency().toUpperCase()) ? 1.0 : null)).toList());
    }

    /** Prix moyen pondéré (PRU) de la quantité restante après rejeu des trades ; nul sans position. */
    static Double avgBuyPrice(List<Transaction> txs) {
        return ledger(txs).avgPrice();
    }

    /** Un point par jour, wallets additionnés ; valeur nulle si un wallet du jour n'est pas exploitable (jamais une somme partielle). */
    private static List<PortfolioPointDto> series(List<PortfolioSnapshot> snapshots) {
        Map<LocalDate, List<PortfolioSnapshot>> byDay = new TreeMap<>();
        snapshots.forEach(s -> byDay.computeIfAbsent(s.getDay(), d -> new ArrayList<>()).add(s));
        List<PortfolioPointDto> out = new ArrayList<>();
        byDay.forEach((day, list) -> {
            boolean usable = list.stream().allMatch(s -> s.getStatus() == PortfolioStatus.OK && s.getTotalValue() != null);
            if (!usable) {
                out.add(new PortfolioPointDto(day, null, null, null, null));
                return;
            }
            double total = list.stream().mapToDouble(PortfolioSnapshot::getTotalValue).sum();
            double stable = list.stream().mapToDouble(PortfolioSnapshot::getCashUsd).sum();
            double assets = list.stream().mapToDouble(PortfolioSnapshot::getAssetsValue).sum();
            out.add(new PortfolioPointDto(day, total, stable, assets, total > 0 ? assets / total * 100.0 : null));
        });
        return out;
    }

    private static PortfolioSummaryDto.Pnl pnl(List<PortfolioPointDto> series) {
        List<PortfolioPointDto> usable = series.stream().filter(p -> p.total() != null).toList();
        if (usable.size() < 2) {
            return null;
        }
        PortfolioPointDto first = usable.getFirst();
        double amount = usable.getLast().total() - first.total();
        return new PortfolioSummaryDto.Pnl(amount, first.total() > 0 ? amount / first.total() * 100.0 : null, first.day());
    }

    /** Stable actuel ÷ dépense quotidienne moyenne (montant effectif x multiplicateur moyen) des liens ; nul si indéterminable. */
    private Double autonomyDays(User user) {
        List<PortfolioSnapshot> latest = snapshotRepository.latestByUser(user.getId()).stream()
                .filter(s -> s.getStatus() == PortfolioStatus.OK && s.getCashUsd() != null).toList();
        if (latest.isEmpty()) {
            return null;
        }
        double cash = latest.stream().mapToDouble(PortfolioSnapshot::getCashUsd).sum();
        double dailySpend = dailySpend(user, null);
        return dailySpend > EPSILON ? cash / dailySpend : null;
    }

    /** Autonomie à partir du cash <b>live</b> fourni (un wallet ou tous) : jours d'achats de base couverts ; nulle sans lien actif. */
    @Transactional(readOnly = true)
    public AutonomyDto autonomy(User user, double cash, Long walletId) {
        double dailySpend = dailySpend(user, walletId);
        return new AutonomyDto(cash, dailySpend > EPSILON ? cash / dailySpend : null, dailySpend > EPSILON ? dailySpend : null);
    }

    /** Dépense quotidienne moyenne des liens (tous, ou ceux du wallet donné). */
    private double dailySpend(User user, Long walletId) {
        double dailySpend = 0;
        for (RainbowLiveBinding b : bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user)) {
            if (walletId != null && !b.getWallet().getId().equals(walletId)) {
                continue;
            }
            var config = configResolver.config(b.getPreset());
            double base = b.getBaseAmount() != null ? b.getBaseAmount() : config.getBaseAmount();
            dailySpend += base * RainbowLiveOnboardingService.averageMultiplier(config);
        }
        return dailySpend;
    }
}
