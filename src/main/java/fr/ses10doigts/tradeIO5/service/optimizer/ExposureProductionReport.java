package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape C4 (calage exploitable) : JSON complet et table de calage de production en Markdown. */
public record ExposureProductionReport(String definition, ExposureProductionConfig config, List<AssetProduction> assets,
                                       WalletProduction wallet, List<String> notes) {

    public static final String DEFINITION = "C4 : niveau = taux de base (base par jour / cash alloué) ; autonomie équivalente = 1 / (taux × multiplicateur moyen) ; "
            + "drawdown de calage = pire des 78 fenêtres de 24 mois et de l'historique complet (même calcul que C3) ; exposition moyenne et gain médian = "
            + "4 blocs de 24 mois de C1 ; bande = min / max du StartingProfile, puissance ×1, biais 0, marge admin.";

    /** Mesure d'une configuration : pire cas (fenêtres + historique), historique seul, exposition et gain médian des blocs. */
    public record Measure(double worstDdPct, String worstRange, double fullDdPct, double blocksExposurePct, double fullExposurePct,
                          double medianGainPct, int ranges) {
    }

    /** Niveau recommandé : multiplicateur du ×1 de C1 (1,00 USDC), taux de base et autonomie équivalente. */
    public record Level(double multiplier, String status, double baseRate, double autonomyDays, boolean autonomyInBounds) {
    }

    public record CapitalRun(double capital, double base, Measure measure) {
    }

    public record Invariance(List<CapitalRun> runs, double maxDdGapPts, double maxGainGapPts, boolean ok, String reading) {
    }

    /** {@code gapPts} = pire drawdown − plafond (points) : ≥ 0 plafond tenu, < 0 dépassement. */
    public record ConfigRun(String name, double base, Measure measure, boolean holds, double gapPts) {
    }

    public record ProfileProduction(String profile, double capPct, Level levelRef, Level level, Invariance invariance, ConfigRun a, ConfigRun b,
                                    ConfigRun c, boolean bandMatters) {
    }

    public record Multiplicity(int levels, int windows, int ranges, int profiles, long replays) {
    }

    public record AssetProduction(String asset, String source, double cash0, double referenceBase, CandleFingerprint fingerprint, int windows,
                                  double meanMultiplier, double onboardingBase, List<ProfileProduction> profiles,
                                  Multiplicity multiplicity, Long totalMs) {
    }

    /** Un wallet sous une bande : {@code f} = facteur de réduction (1 = déjà conforme) appliqué aux deux niveaux. */
    public record WalletBand(String band, boolean holdsAtOne, Measure atOne, double f, Measure atF, boolean holds, int iterations,
                             double baseBtc, double baseEth) {
    }

    public record WalletProfile(String profile, double capPct, double rateBtc, double rateEth, WalletBand neutral, WalletBand profileBand) {
    }

    public record WalletProduction(double cash, double share, int windows, List<WalletProfile> profiles, long replays, Long totalMs) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport C4 non sérialisable", e);
        }
    }

    /** Même rapport sans durées (comparaison de reproductibilité). */
    public String toJsonWithoutTimings() {
        List<AssetProduction> a = assets.stream().map(x -> new AssetProduction(x.asset, x.source, x.cash0, x.referenceBase, x.fingerprint, x.windows,
                x.meanMultiplier, x.onboardingBase, x.profiles, x.multiplicity, null)).toList();
        WalletProduction w = wallet == null ? null : new WalletProduction(wallet.cash, wallet.share, wallet.windows, wallet.profiles, wallet.replays, null);
        return new ExposureProductionReport(definition, config, a, w, notes).toJson();
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "n/d" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String sg(double v) {
        return Double.isNaN(v) ? "n/d" : String.format(Locale.ROOT, "%+.2f", v);
    }

    private static String rate(double v) {
        return String.format(Locale.ROOT, "%.3e", v);
    }

    private static String days(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String yn(boolean b) {
        return b ? "oui" : "NON";
    }

    public String toMarkdown() {
        List<String> out = new ArrayList<>();
        out.add("# C4 — calage exploitable (invariance au capital, bandes du live, wallet BTC + ETH)");
        out.add("");
        out.add(definition);
        out.add("");
        out.add("## 1. Invariance au capital (niveaux k = " + config.k() + ", bande neutre)");
        out.add("| Actif | Profil | Niveau ×1 (k=1 / k=" + config.k() + ") | Taux base/j/cash | Autonomie éq. (j) | Capital | Base/j (USDC) | DD pire cas % | DD hist. % | Gain méd. % | Expo % | Écart DD (pt) | Écart gain (pt) | OK |");
        out.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (AssetProduction a : assets) {
            for (ProfileProduction p : a.profiles()) {
                CapitalRun ref = p.invariance().runs().getFirst();
                for (CapitalRun r : p.invariance().runs()) {
                    boolean first = r == ref;
                    out.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %.0f | %.4f | %s | %s | %s | %s | %s | %s | %s |", a.asset(), p.profile(),
                            first ? "×" + f(p.levelRef().multiplier()) + " / ×" + f(p.level().multiplier()) : "", first ? rate(p.level().baseRate()) : "",
                            first ? days(p.level().autonomyDays()) : "", r.capital(), r.base(), f(r.measure().worstDdPct()), f(r.measure().fullDdPct()),
                            f(r.measure().medianGainPct()), f(r.measure().blocksExposurePct()),
                            first ? "—" : sg(r.measure().worstDdPct() - ref.measure().worstDdPct()),
                            first ? "—" : sg(r.measure().medianGainPct() - ref.measure().medianGainPct()),
                            first ? (p.invariance().ok() ? "invariance OK" : "invariance NON") : ""));
                }
            }
        }
        out.add("");
        for (AssetProduction a : assets) {
            for (ProfileProduction p : a.profiles()) {
                out.add("- " + a.asset() + " " + p.profile() + " : " + p.invariance().reading());
            }
        }
        out.add("");
        out.add("## 2. Bandes d'exposition du live et onboarding actuel");
        out.add("A = base recommandée, bande neutre ; B = base recommandée + bande du profil ; C = onboarding (cash / (" + config.autonomyDays()
                + " j × mult. moyen)) + bande du profil.");
        out.add("| Actif | Profil | Plafond % | Config | Base/j (USDC) | DD pire cas % | DD hist. % | Expo % (blocs) | Gain méd. % | Plafond tenu | Écart (pt) |");
        out.add("|---|---|---|---|---|---|---|---|---|---|---|");
        for (AssetProduction a : assets) {
            for (ProfileProduction p : a.profiles()) {
                for (ConfigRun c : List.of(p.a(), p.b(), p.c())) {
                    out.add(String.format(Locale.ROOT, "| %s | %s | %.0f | %s | %.2f | %s | %s | %s | %s | %s | %s |", a.asset(), p.profile(), p.capPct(),
                            c.name(), c.base(), f(c.measure().worstDdPct()), f(c.measure().fullDdPct()), f(c.measure().blocksExposurePct()),
                            f(c.measure().medianGainPct()), yn(c.holds()), sg(c.gapPts())));
                }
            }
        }
        out.add("");
        for (AssetProduction a : assets) {
            for (ProfileProduction p : a.profiles()) {
                out.add(String.format(Locale.ROOT, "- %s %s : onboarding actuel + bande du profil %s le plafond (%s pt) ; la bande %s.", a.asset(), p.profile(),
                        p.c().holds() ? "RESPECTE" : "NE RESPECTE PAS", sg(p.c().gapPts()), p.bandMatters() ? "COMPTE (B ≠ A)" : "ne compte pas (B ≈ A)"));
            }
        }
        if (wallet != null) {
            out.add("");
            out.add("## 3. Wallet BTC + ETH à cash commun (capital " + f(wallet.cash()) + " USDC, partage " + f(100.0 * wallet.share()) + " % par lien, "
                    + wallet.windows() + " fenêtres)");
            out.add("| Profil | Plafond % | Bande | DD pire cas % (f=1) | Conforme à f=1 | f | DD pire cas % (f) | DD hist. % (f) | Expo % (blocs, f) | Gain méd. % (f) | Base BTC/j | Base ETH/j |");
            out.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (WalletProfile p : wallet.profiles()) {
                for (WalletBand b : List.of(p.neutral(), p.profileBand())) {
                    Measure m = b.atF();
                    out.add(String.format(Locale.ROOT, "| %s | %.0f | %s | %s | %s | %.3f | %s | %s | %s | %s | %.4f | %.4f |", p.profile(), p.capPct(), b.band(),
                            f(b.atOne().worstDdPct()), yn(b.holdsAtOne()), b.f(), m == null ? "n/d" : f(m.worstDdPct()), m == null ? "n/d" : f(m.fullDdPct()),
                            m == null ? "n/d" : f(m.blocksExposurePct()), m == null ? "n/d" : f(m.medianGainPct()), b.baseBtc(), b.baseEth()));
                }
            }
            out.add("");
            out.add("PAXG exclu (historique insuffisant).");
        }
        out.add("");
        out.add("## 4. Table de calage de production (par profil)");
        out.add("Bornes de l'autonomie du live : " + fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults.limit(
                fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults.AUTONOMY_DAYS).min().toPlainString() + " à "
                + fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults.limit(fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults.AUTONOMY_DAYS)
                .max().toPlainString() + " j.");
        for (int pi = 0; pi < (assets.isEmpty() ? 0 : assets.getFirst().profiles().size()); pi++) {
            String name = assets.getFirst().profiles().get(pi).profile();
            out.add("");
            out.add("### " + name + " (plafond " + f(assets.getFirst().profiles().get(pi).capPct()) + " %)");
            out.add("| Ligne | Taux base/j/cash | Autonomie éq. (j) | Dans les bornes | Bande conseillée | DD de calage % | Plafond % | Marge (pt) | Gain médian attendu % |");
            out.add("|---|---|---|---|---|---|---|---|---|");
            for (AssetProduction a : assets) {
                ProfileProduction p = a.profiles().get(pi);
                out.add(String.format(Locale.ROOT, "| %s seul | %s | %s | %s | %s | %s | %.0f | %s | %s |", a.asset(), rate(p.level().baseRate()),
                        days(p.level().autonomyDays()), yn(p.level().autonomyInBounds()), p.bandMatters() ? "neutre (la bande du profil change le résultat)" : "profil ou neutre (équivalent)",
                        f(p.a().measure().worstDdPct()), p.capPct(), sg(p.a().gapPts()), f(p.a().measure().medianGainPct())));
            }
            if (wallet != null) {
                WalletProfile w = wallet.profiles().get(pi);
                for (WalletBand b : List.of(w.neutral(), w.profileBand())) {
                    Measure m = b.atF();
                    double rb = rateOf(w.rateBtc(), b.f());
                    double re = rateOf(w.rateEth(), b.f());
                    out.add(String.format(Locale.ROOT, "| Wallet BTC+ETH (bande %s), f = %.3f | BTC %s / ETH %s | BTC %s / ETH %s | | %s | %s | %.0f | %s | %s |", b.band(), b.f(),
                            rate(rb), rate(re), autonomyText(rb, assets, 0), autonomyText(re, assets, 1), b.band(), m == null ? "n/d" : f(m.worstDdPct()), w.capPct(),
                            m == null ? "n/d" : sg(m.worstDdPct() - w.capPct()), m == null ? "n/d" : f(m.medianGainPct())));
                }
            }
        }
        out.add("");
        out.add("Avertissement à afficher : « cible de risque calibrée sur l'historique, pas garantie ».");
        out.add("");
        out.add("## Multiplicité");
        for (AssetProduction a : assets) {
            Multiplicity m = a.multiplicity();
            out.add("- " + a.asset() + " : " + m.levels() + " niveaux × " + m.ranges() + " plages (" + m.windows() + " fenêtres + historique + blocs) ; " + m.profiles()
                    + " profils ; " + m.replays() + " rejeux ; " + (a.totalMs() == null ? "n/d" : a.totalMs() + " ms") + " ; empreinte " + a.fingerprint().checksum() + ".");
        }
        if (wallet != null) {
            out.add("- Wallet : " + wallet.replays() + " rejeux à 2 liens ; " + (wallet.totalMs() == null ? "n/d" : wallet.totalMs() + " ms") + ".");
        }
        out.add("");
        out.add("## Limites");
        notes.forEach(n -> out.add("- " + n));
        return String.join("\n", out) + "\n";
    }

    private static double rateOf(double rate, double f) {
        return rate * f;
    }

    private static String autonomyText(double rate, List<AssetProduction> assets, int idx) {
        double mean = assets.get(idx).meanMultiplier();
        return rate <= 0 ? "n/d" : days(1.0 / (rate * mean));
    }
}
