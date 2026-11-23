package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AssetCut;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.CapResult;

import java.util.List;
import java.util.Locale;

/** Rapport de l'étape E2 : robustesse de la coupure au switch (taille de ré-entrée, bande de bruit, portefeuille BTC + ETH). */
public record SwitchRobustnessReport(String definition, SwitchRobustnessConfig config, List<AssetRobustness> assets, WalletResult wallet,
                                     Verdicts verdicts, List<String> drift, List<String> notes, Long durationMs) {

    public static final String DEFINITION = "E2 : robustesse de la coupure au switch (mécanisme d'E1 inchangé) : taille de ré-entrée (0, 5, 10, 20 %), bande de bruit "
            + "(20 décalages de départ), portefeuille BTC + ETH à cash commun ; timing pur (modulation ATH désactivée sur toutes les lignes).";

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    // ----------------------------------------------------------------- règle 1 : ablations

    /** Un plafond, un bloc retiré : le recalcul sans ce bloc (plafond ré-interpolé sur les blocs restants). */
    public record Ablation(String removedBlock, String status, Double medianDelta, boolean positive) {
    }

    public record CapAblation(String profile, double capPct, Double fullMedianDelta, List<Ablation> ablations, boolean allPositive) {
    }

    /** {@code verdict} : ROBUSTE / FRAGILE / NON_CONCLU ; {@code flippingBlock} : bloc dont le retrait renverse le plus souvent le signe (FRAGILE). */
    public record Rule1(String verdict, int capsAllPositive, List<CapAblation> caps, String flippingBlock) {
    }

    /** Une taille de ré-entrée pour un actif : le run E1 (cut complet : lignes, plafonds) et la règle 1. */
    public record SizeResult(double reentryPct, boolean reconstituted, AssetCut cut, Rule1 rule1) {
    }

    // ----------------------------------------------------------------- règle 2 : taille

    public record TrendCap(String profile, double capPct, Double pureGain, List<Double> cutGainBySize) {
    }

    /** {@code growth} : CROIT / NE_CROIT_PAS / NON_EVALUABLE ; {@code bestSize} : taille la plus souvent meilleure (null si ex aequo / non évaluable). */
    public record SizeTrend(List<Double> sizes, List<TrendCap> caps, int capsEvaluated, int capsIncreasing, String growth, Double bestSize, List<Integer> bestCounts) {
    }

    // ----------------------------------------------------------------- règle 3 : bruit

    public record CapNoise(String profile, double capPct, int shifts, int concluded, int beats, double beatShare, Double medianDelta, Double p10, Double p90,
                           boolean pass) {
    }

    /** {@code verdict} : STABLE / INSTABLE. */
    public record NoiseResult(double reentryPct, List<CapNoise> caps, int capsPass, String verdict) {
    }

    public record AssetRobustness(String asset, double cash0, double referenceBase, String fingerprint, List<String> blockLabels, List<SizeResult> sizes,
                                  SizeTrend trend, List<NoiseResult> noise, long replays) {
    }

    // ----------------------------------------------------------------- règle 4 : portefeuille

    /** Un plafond du wallet : multiplicateurs interpolés, Δ gain médian, plafond respecté après rejeu au multiplicateur interpolé. */
    public record WalletCap(String profile, double capPct, CapResult result, Double verifiedWorstDdPure, boolean pureHolds, Double verifiedWorstDdCut,
                            boolean cutHolds, boolean ok) {
    }

    /** Réduction de drawdown à multiplicateur égal : pire drawdown des blocs, pur contre coupure. */
    public record DdAtMultiplier(double multiplier, Double pureWorstDd, Double cutWorstDd, Double reductionPts) {
    }

    public record WalletLevel(double multiplier, double pureWorstDd, double pureMedianGain, double cutWorstDd, double cutMedianGain, int cutSells, int cutBuys) {
    }

    public record WalletSize(double reentryPct, double enginePct, List<WalletCap> caps, int capsOk, String verdict, List<DdAtMultiplier> ddAtMultiplier,
                             List<WalletLevel> levels) {
    }

    public record WalletResult(double cash, List<String> blockLabels, double refBtc, double refEth, String fingerprintBtc, String fingerprintEth,
                               List<WalletSize> sizes, long replays) {
    }

    // ----------------------------------------------------------------- verdict global

    public record SizeVerdict(double reentryPct, boolean rule1, boolean rule3, boolean rule4, boolean robust, String causes) {
    }

    public record Verdicts(String rule1, String rule2, String rule3, String rule4, List<SizeVerdict> bySize, String global, String cause) {
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport E2 non sérialisable", e);
        }
    }

    /** JSON sans les durées : identique à l'octet pour mêmes entrées. */
    public String toJsonWithoutTimings() {
        return new SwitchRobustnessReport(definition, config, assets, wallet, verdicts, drift, notes, null).toJson().replaceAll("\"durationMs\" : (\\d+|null)",
                "\"durationMs\" : 0");
    }

    static String f(Double v) {
        return SwitchCutReport.f(v);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# Étape E2 — robustesse de la coupure au switch\n\n").append(definition).append("\n\n");
        sb.append("## Verdicts\n\n");
        sb.append("- Règle 1 (hors cycle dominant) : ").append(verdicts.rule1()).append('\n');
        sb.append("- Règle 2 (taille) : ").append(verdicts.rule2()).append('\n');
        sb.append("- Règle 3 (bruit) : ").append(verdicts.rule3()).append('\n');
        sb.append("- Règle 4 (portefeuille) : ").append(verdicts.rule4()).append('\n');
        sb.append("- Global : **").append(verdicts.global()).append("** — ").append(verdicts.cause()).append("\n\n");
        sb.append("| Taille | Règle 1 (BTC et ETH) | Règle 3 (BTC et ETH) | Règle 4 | Robuste | Causes |\n|---|---|---|---|---|---|\n");
        for (SizeVerdict v : verdicts.bySize()) {
            sb.append(String.format(Locale.ROOT, "| %.0f %% | %s | %s | %s | %s | %s |\n", v.reentryPct(), v.rule1(), v.rule3(), v.rule4(), v.robust(), v.causes()));
        }
        sb.append('\n');
        for (AssetRobustness a : assets) {
            asset(sb, a);
        }
        wallet(sb);
        sb.append("## Dérive contre E1 publié\n\n");
        if (drift.isEmpty()) {
            sb.append("Aucune.\n\n");
        } else {
            drift.forEach(d -> sb.append("- ").append(d).append('\n'));
            sb.append('\n');
        }
        sb.append("## Notes\n\n");
        notes.forEach(n -> sb.append("- ").append(n).append('\n'));
        return sb.toString();
    }

    private void asset(StringBuilder sb, AssetRobustness a) {
        sb.append("## ").append(a.asset()).append("\n\nBlocs : ").append(a.blockLabels()).append(" ; empreinte ").append(a.fingerprint()).append(" ; base de référence ")
                .append(String.format(Locale.ROOT, "%.4f", a.referenceBase())).append(" ; ").append(a.replays()).append(" rejeux.\n\n");
        sb.append("### Gain médian des blocs à plafond égal (règle 2)\n\n| Profil | Plafond | Pur |");
        for (Double s : a.trend().sizes()) {
            sb.append(String.format(Locale.ROOT, " Coupure %.0f %% |", s));
        }
        sb.append("\n|---|---|---|");
        a.trend().sizes().forEach(s -> sb.append("---|"));
        sb.append('\n');
        for (TrendCap c : a.trend().caps()) {
            sb.append(String.format(Locale.ROOT, "| %s | %.0f | %s |", c.profile(), c.capPct(), f(c.pureGain())));
            c.cutGainBySize().forEach(g -> sb.append(' ').append(f(g)).append(" |"));
            sb.append('\n');
        }
        sb.append(String.format(Locale.ROOT, "\nCroissance : %s (%d/%d plafonds strictement croissants) ; meilleure taille : %s (décompte par taille %s).\n\n", a.trend().growth(),
                a.trend().capsIncreasing(), a.trend().capsEvaluated(), a.trend().bestSize() == null ? "n/d" : String.format(Locale.ROOT, "%.0f %%", a.trend().bestSize()),
                a.trend().bestCounts()));
        sb.append("### Ablations d'un bloc (règle 1)\n\n");
        for (SizeResult s : a.sizes()) {
            sb.append(String.format(Locale.ROOT, "**Ré-entrée %.0f %%%s** — verdict de run E1 : %s ; règle 1 : %s (%d/4 plafonds positifs dans toutes les ablations)%s\n\n", s.reentryPct(),
                    s.reconstituted() ? " (E1 reconstitué)" : "", s.cut().verdict(), s.rule1().verdict(), s.rule1().capsAllPositive(),
                    s.rule1().flippingBlock() == null ? "" : " ; bloc qui renverse le signe : " + s.rule1().flippingBlock()));
            sb.append("| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |\n|---|---|---|---|---|\n");
            for (CapAblation c : s.rule1().caps()) {
                sb.append(String.format(Locale.ROOT, "| %s | %.0f | %s | %s | %s |\n", c.profile(), c.capPct(), f(c.fullMedianDelta()),
                        c.ablations().stream().map(x -> x.removedBlock().substring(0, 4) + " : " + (x.medianDelta() == null ? x.status() : f(x.medianDelta()))).toList(),
                        c.allPositive()));
            }
            sb.append('\n');
        }
        sb.append("### Bande de bruit (règle 3) : 20 décalages de départ\n\n| Ré-entrée | Profil | Plafond | Conclus | Bat le pur | Part | Δ médian | P10 | P90 | Plafond stable |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (NoiseResult n : a.noise()) {
            for (CapNoise c : n.caps()) {
                sb.append(String.format(Locale.ROOT, "| %.0f %% | %s | %.0f | %d/%d | %d | %.0f %% | %s | %s | %s | %s |\n", n.reentryPct(), c.profile(), c.capPct(), c.concluded(), c.shifts(),
                        c.beats(), 100.0 * c.beatShare(), f(c.medianDelta()), f(c.p10()), f(c.p90()), c.pass()));
            }
            sb.append(String.format(Locale.ROOT, "| %.0f %% | **%s** | | | | | | | | %d/4 |\n", n.reentryPct(), n.verdict(), n.capsPass()));
        }
        sb.append('\n');
    }

    private void wallet(StringBuilder sb) {
        sb.append("## Portefeuille BTC + ETH à cash commun (règle 4)\n\nCash commun ").append(String.format(Locale.ROOT, "%.0f", wallet.cash())).append(" USDC ; blocs ")
                .append(wallet.blockLabels()).append(" ; bases de référence BTC ").append(String.format(Locale.ROOT, "%.4f", wallet.refBtc())).append(", ETH ")
                .append(String.format(Locale.ROOT, "%.4f", wallet.refEth())).append(" ; ").append(wallet.replays()).append(" rejeux.\n\n");
        for (WalletSize s : wallet.sizes()) {
            sb.append(String.format(Locale.ROOT, "**Coupure %.0f %% du capital du lien (moteur : %.1f %% du cash commun)** — verdict : %s (%d/4 plafonds OK)\n\n", s.reentryPct(), s.enginePct(),
                    s.verdict(), s.capsOk()));
            sb.append("| Profil | Plafond wallet | Pur : × / gain méd. | Coupure : × / gain méd. | Δ par bloc | Δ médian | Pur respecte | Coupure respecte (pire DD rejoué) | OK |\n|---|---|---|---|---|---|---|---|---|\n");
            for (WalletCap c : s.caps()) {
                sb.append(String.format(Locale.ROOT, "| %s | %.0f | %s | %s | %s | %s | %s (%s) | %s (%s) | %s |\n", c.profile(), c.capPct(), cell(c.result().pure()), cell(c.result().cut()),
                        c.result().deltaGains().stream().map(SwitchRobustnessReport::f).toList(), f(c.result().medianDelta()), c.pureHolds(), f(c.verifiedWorstDdPure()),
                        c.cutHolds(), f(c.verifiedWorstDdCut()), c.ok()));
            }
            sb.append("\nRéduction de drawdown à multiplicateur égal (pire DD des blocs, points) :\n\n| × | Pur | Coupure | Réduction |\n|---|---|---|---|\n");
            for (DdAtMultiplier d : s.ddAtMultiplier()) {
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |\n", d.multiplier(), f(d.pureWorstDd()), f(d.cutWorstDd()), f(d.reductionPts())));
            }
            sb.append("\nGrille complète :\n\n| × | Pur : pire DD | Pur : gain méd. | Coupure : pire DD | Coupure : gain méd. | Ventes | Rachats |\n|---|---|---|---|---|---|---|\n");
            for (WalletLevel l : s.levels()) {
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %d | %d |\n", l.multiplier(), f(l.pureWorstDd()), f(l.pureMedianGain()), f(l.cutWorstDd()),
                        f(l.cutMedianGain()), l.cutSells(), l.cutBuys()));
            }
            sb.append('\n');
        }
    }

    private static String cell(SwitchCutReport.AtCap c) {
        return c.multiplier() == null ? c.status() : String.format(Locale.ROOT, "×%.2f / %s", c.multiplier(), f(c.medianGain()));
    }
}
