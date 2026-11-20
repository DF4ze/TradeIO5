package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.RegimeBandPolicy;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.AssetPolicy;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.Estimate;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.FullHistory;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.Pick;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.ProfileResult;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.Rotation;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Étape C2 : à plafond de drawdown égal, une politique d'exposition par régime ({@code REGIME_BAND} : borne haute Bull, borne haute Bear,
 * multiplicateur du montant de base ; bornes basses et le reste = réglages de base de l'évaluateur, comme en C1) gagne-t-elle plus que
 * l'exposition constante maximale (le seul multiplicateur du montant de base) ? Les deux candidats sont réglés de la même façon : sur 3 des
 * 4 blocs de 24 mois (la constante : plus grand multiplicateur dont le pire drawdown de ces blocs respecte le plafond, interpolé comme en C1 ;
 * la politique : meilleur gain médian de ces blocs sous la même contrainte, sur la grille), testés sur le 4ᵉ, 4 rotations ; le bloc de test
 * n'entre jamais dans le réglage. Les plafonds sont lus dans {@link FrontierConfig#profileCaps()}. Hors ligne : rien n'est appliqué ni persisté,
 * aucun réglage du preset Rainbow n'est modifié. Déterministe : le pool ne change que la durée (résultats consommés dans l'ordre de soumission).
 */
public final class ExposurePolicyStudy {

    private static final double EPS = 1e-9;

    private ExposurePolicyStudy() {
    }

    /** Une combinaison de la politique et sa mesure sur chaque bloc (une ligne par bloc ; {@code null} = bloc non rejouable). */
    record Combo(double bullMax, double bearMax, double multiplier, Line[] lines) {
    }

    /** Multiplicateur retenu pour la constante et son statut. */
    record ConstantPick(double multiplier, String status) {
    }

    /** Combinaison retenue pour la politique, gain médian et pire drawdown sur les blocs de réglage. */
    record PolicyPick(Combo combo, double medianGain, double worstDrawdown, String status) {
    }

    private record Ctx(int index, String label, SensitivityEvaluator ev) {
    }

    /** Rejeux au pire pour un actif : toutes les combinaisons et la constante sur chaque bloc + l'historique complet, puis tests et historique. */
    public static Estimate estimate(ExposurePolicyConfig cfg) {
        ParityConfig p = cfg.parity();
        long ranges = p.blockCount() + 1L;
        long total = ranges * (cfg.combos() + cfg.constantGrid().size()) + (long) p.blockCount() * StartingProfile.values().length + 8;
        return new Estimate(total, p.replayMs(), total * p.replayMs() / 60_000.0);
    }

    // ------------------------------------------------------------------ mesures

    /** La politique {@code REGIME_BAND} à bornes hautes données ; bornes basses = celles des réglages de base. */
    static RegimeBandPolicy policy(ModulationSettings base, double bullMax, double bearMax) {
        return new RegimeBandPolicy(base.exposureMin(), bullMax, base.exposureMin(), bearMax);
    }

    /** Constante : montant de base seul, aucune politique ({@code ×1} = base du lien : chiffres de C1). */
    static Line constantLine(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, boolean full, double multiplier, double reference) {
        Parity p = ev.parity(sets, full, 0, null, ExposureFrontier.linkBase(multiplier, reference), null);
        return p == null ? null : p.preset();
    }

    static Line policyLine(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, boolean full, double multiplier, double reference, double bullMax,
                           double bearMax) {
        Parity p = ev.parity(sets, full, 0, null, ExposureFrontier.linkBase(multiplier, reference),
                policy(ev.inputs().baseSettings(), bullMax, bearMax));
        return p == null ? null : p.preset();
    }

    // ------------------------------------------------------------------ réglage (purs)

    /**
     * Plus grand multiplicateur dont le pire drawdown des blocs de réglage respecte {@code cap} : premier franchissement en montant la grille,
     * interpolé linéairement (en pire drawdown) entre les deux points qui l'encadrent, comme en C1. {@code SOUS_LE_MINIMUM} : le plus bas niveau
     * dépasse déjà le plafond (plus bas niveau retenu) ; {@code PLAFOND_NON_ATTEINT} : jamais franchi (plus haut niveau retenu, sans extrapolation).
     *
     * @param multipliers croissants ; {@code worstDrawdown} aligné (≤ 0)
     */
    static ConstantPick constantMultiplier(List<Double> multipliers, double[] worstDrawdown, double cap) {
        int k = -1;
        for (int i = 0; i < multipliers.size(); i++) {
            if (worstDrawdown[i] < cap) {
                k = i;
                break;
            }
        }
        if (k == 0) {
            return new ConstantPick(multipliers.getFirst(), "SOUS_LE_MINIMUM");
        }
        if (k < 0) {
            return new ConstantPick(multipliers.getLast(), "PLAFOND_NON_ATTEINT");
        }
        double lo = worstDrawdown[k - 1];
        double hi = worstDrawdown[k];
        double w = (cap - lo) / (hi - lo);
        return new ConstantPick(multipliers.get(k - 1) + (multipliers.get(k) - multipliers.get(k - 1)) * w, "INTERPOLE");
    }

    static double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (a.length == 0) {
            return 0.0;
        }
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    /**
     * Combinaison qui maximise le gain médian sur les blocs de réglage {@code tune} sous la contrainte « pire drawdown de ces blocs ≥ plafond »
     * (premier de la grille en cas d'égalité). Aucune combinaison ne tient le plafond : celle de plus faible drawdown ({@code PLAFOND_NON_TENU_REGLAGE}).
     * Ne lit jamais d'autre bloc que {@code tune}.
     */
    static PolicyPick selectPolicy(List<Combo> combos, List<Integer> tune, double cap) {
        PolicyPick best = null;
        PolicyPick safest = null;
        for (Combo c : combos) {
            List<Double> gains = new ArrayList<>();
            double worst = 0.0;
            for (int b : tune) {
                gains.add(c.lines()[b].pnlPct());
                worst = Math.min(worst, c.lines()[b].maxDrawdownPct());
            }
            double med = median(gains);
            if (worst >= cap - EPS && (best == null || med > best.medianGain() + EPS)) {
                best = new PolicyPick(c, med, worst, "REGLE");
            }
            if (safest == null || worst > safest.worstDrawdown() + EPS) {
                safest = new PolicyPick(c, med, worst, "PLAFOND_NON_TENU_REGLAGE");
            }
        }
        return best != null ? best : safest;
    }

    static boolean holds(Line l, double cap) {
        return l.maxDrawdownPct() >= cap - EPS;
    }

    // ------------------------------------------------------------------ verdict

    /**
     * Règle fixée avant lecture : {@code POLITIQUE_UTILE} si la politique bat la constante en gain dans au moins {@code minWins} rotations,
     * médiane des Δ gain &gt; 0, et plafond au moins aussi bien tenu (aucune rotation où la politique dépasse le plafond alors que la constante
     * ne le dépasse pas). Sinon {@code NUL}, raison : gain, plafond (et instabilité des réglages, diagnostic rapporté, hors règle).
     */
    static ProfileResult verdict(String profile, double cap, List<Rotation> rotations, FullHistory full, ExposurePolicyConfig cfg) {
        int wins = (int) rotations.stream().filter(r -> r.deltaGain() > EPS).count();
        double med = median(rotations.stream().map(Rotation::deltaGain).toList());
        boolean capFail = rotations.stream().anyMatch(r -> !r.policy().holds() && r.constant().holds());
        int polBreach = (int) rotations.stream().filter(r -> !r.policy().holds()).count();
        int constBreach = (int) rotations.stream().filter(r -> !r.constant().holds()).count();
        Map<String, Integer> freq = new HashMap<>();
        String modal = "n/d";
        int modalCount = 0;
        for (Rotation r : rotations) {
            String key = String.format(Locale.ROOT, "Bull≤%.0f/Bear≤%.0f", r.policy().bullMax(), r.policy().bearMax());
            int n = freq.merge(key, 1, Integer::sum);
            if (n > modalCount) {
                modalCount = n;
                modal = key;
            }
        }
        boolean stable = modalCount >= cfg.minStable();
        boolean gainOk = wins >= cfg.minWins() && med > 0;
        boolean useful = rotations.size() == cfg.parity().blockCount() && gainOk && !capFail;
        String reason;
        if (rotations.size() < cfg.parity().blockCount()) {
            reason = "moins de 4 rotations évaluables";
        } else {
            List<String> r = new ArrayList<>();
            if (!gainOk) {
                r.add(String.format(Locale.ROOT, "gain (%d/%d rotations gagnées, médiane Δ %.2f pt)", wins, rotations.size(), med));
            }
            if (capFail) {
                r.add("plafond (la politique dépasse le plafond là où la constante le tient)");
            }
            if (!stable) {
                r.add("instabilité des réglages (bornes modales " + modal + " : " + modalCount + "/" + rotations.size() + ")");
            }
            reason = useful ? (stable ? "règle satisfaite" : "règle satisfaite, MAIS " + r.getLast()) : String.join(" ; ", r);
        }
        String v = rotations.size() < cfg.parity().blockCount() ? "NON_EVALUABLE" : useful ? "POLITIQUE_UTILE" : "NUL";
        return new ProfileResult(profile, cap, rotations, full, v, wins, med, capFail, polBreach, constBreach, stable, modal, modalCount, reason);
    }

    // ------------------------------------------------------------------ exécution

    static <T> List<T> ordered(List<Callable<T>> tasks, ExecutorService pool) {
        List<T> out = new ArrayList<>();
        if (pool == null) {
            for (Callable<T> t : tasks) {
                try {
                    out.add(t.call());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
        List<Future<T>> fs = new ArrayList<>();
        for (Callable<T> t : tasks) {
            fs.add(pool.submit(t));
        }
        try {
            for (Future<T> f : fs) {
                out.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
        return out;
    }

    /** Mesure un actif : tables (constante et politique) sur les 4 blocs, puis 4 rotations par profil, puis historique complet en lecture seule. */
    public static AssetPolicy run(ScorerInputs in, ExposurePolicyConfig cfg, ExecutorService pool) {
        long t0 = System.nanoTime();
        ParityConfig pc = cfg.parity();
        List<Block> specs = TuneBlocks.build(pc.lastEnd(), pc.blockMonths(), pc.blockCount());
        List<Ctx> blocks = new ArrayList<>();
        RainbowAtrParamSet[] sets = null;
        SensitivityEvaluator head = null;
        for (Block b : specs) {
            SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(b.to()), pc.blockMonths(), b.from());
            if (sets == null) {
                sets = ev.template().sets();
                head = ev;
            }
            blocks.add(new Ctx(b.index(), b.label(), ev));
        }
        SensitivityEvaluator fullEv = new SensitivityEvaluator(in.withEndDate(pc.lastEnd()), pc.blockMonths());
        final RainbowAtrParamSet[] fs = sets;
        final double ref = head.referenceBase(fs);
        final int n = blocks.size();

        List<Double> grid = cfg.constantGrid();
        List<Callable<Line[]>> constTasks = new ArrayList<>();
        for (double m : grid) {
            constTasks.add(() -> {
                Line[] l = new Line[n];
                for (int i = 0; i < n; i++) {
                    l[i] = constantLine(blocks.get(i).ev(), fs, false, m, ref);
                }
                return l;
            });
        }
        List<Line[]> constTable = ordered(constTasks, pool);
        List<Callable<Combo>> comboTasks = new ArrayList<>();
        for (double bull : cfg.bounds()) {
            for (double bear : cfg.bounds()) {
                for (double m : cfg.multipliers()) {
                    comboTasks.add(() -> {
                        Line[] l = new Line[n];
                        for (int i = 0; i < n; i++) {
                            l[i] = policyLine(blocks.get(i).ev(), fs, false, m, ref, bull, bear);
                        }
                        return new Combo(bull, bear, m, l);
                    });
                }
            }
        }
        List<Combo> combos = ordered(comboTasks, pool);

        // un bloc non rejouable l'est pour tous les niveaux (plage / préchauffage) : lu sur le ×1
        int oneIdx = grid.indexOf(1.0);
        List<Integer> valid = new ArrayList<>();
        List<String> blockLabels = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean ok = constTable.get(oneIdx)[i] != null;
            if (ok) {
                valid.add(i);
            }
            blockLabels.add(blocks.get(i).label() + (ok ? "" : " (écarté : non rejouable)"));
        }

        Map<String, Line> testCache = new HashMap<>();
        List<ProfileResult> results = new ArrayList<>();
        long selections = 0;
        for (Map.Entry<StartingProfile, Double> e : FrontierConfig.profileCaps().entrySet()) {
            double cap = e.getValue();
            List<Rotation> rotations = new ArrayList<>();
            if (valid.size() >= pc.blockCount() - 1) {
                for (int t : valid) {
                    List<Integer> tune = valid.stream().filter(i -> i != t).toList();
                    if (tune.size() < pc.blockCount() - 1) {
                        continue;
                    }
                    rotations.add(rotation(t, tune, blocks, grid, constTable, combos, cap, fs, ref, testCache));
                    selections += grid.size() + combos.size();
                }
            }
            FullHistory full = null;
            if (!valid.isEmpty()) {
                ConstantPick cp = constantPick(grid, constTable, valid, cap);
                PolicyPick pp = selectPolicy(combos, valid, cap);
                selections += grid.size() + combos.size();
                Line cl = constantLine(fullEv, fs, true, cp.multiplier(), ref);
                Line pl = policyLine(fullEv, fs, true, pp.combo().multiplier(), ref, pp.combo().bullMax(), pp.combo().bearMax());
                if (cl != null && pl != null) {
                    full = new FullHistory(new Pick(null, null, cp.multiplier(), cp.status(), null, null, cl, holds(cl, cap)),
                            new Pick(pp.combo().bullMax(), pp.combo().bearMax(), pp.combo().multiplier(), pp.status(), pp.medianGain(),
                                    pp.worstDrawdown(), pl, holds(pl, cap)));
                }
            }
            results.add(verdict(e.getKey().name(), cap, rotations, full, cfg));
        }
        long replays = fullEv.replays();
        for (Ctx c : blocks) {
            replays += c.ev().replays();
        }
        Multiplicity mult = new Multiplicity(grid.size(), combos.size(), valid.size(), results.size(), selections, replays);
        return new AssetPolicy(head.asset(), in.assets().getFirst().source(), in.cash0(), ref, CandleFingerprint.of(head.asset(),
                head.template().candles(), pc.lastEnd()), blockLabels, results, mult, (System.nanoTime() - t0) / 1_000_000);
    }

    /** Multiplicateur de la constante réglé sur les seuls blocs {@code tune}. */
    private static ConstantPick constantPick(List<Double> grid, List<Line[]> constTable, List<Integer> tune, double cap) {
        double[] worst = new double[grid.size()];
        for (int g = 0; g < grid.size(); g++) {
            double w = 0.0;
            for (int b : tune) {
                w = Math.min(w, constTable.get(g)[b].maxDrawdownPct());
            }
            worst[g] = w;
        }
        return constantMultiplier(grid, worst, cap);
    }

    private static Rotation rotation(int test, List<Integer> tune, List<Ctx> blocks, List<Double> grid, List<Line[]> constTable, List<Combo> combos,
                                     double cap, RainbowAtrParamSet[] sets, double ref, Map<String, Line> cache) {
        ConstantPick cp = constantPick(grid, constTable, tune, cap);
        PolicyPick pp = selectPolicy(combos, tune, cap);
        // le bloc de test n'est rejoué qu'après le réglage, au multiplicateur retenu (interpolé : pas dans la table)
        Line cl = cache.computeIfAbsent(test + "@" + cp.multiplier(), k -> constantLine(blocks.get(test).ev(), sets, false, cp.multiplier(), ref));
        Line pl = pp.combo().lines()[test];
        Pick cpick = new Pick(null, null, cp.multiplier(), cp.status(), null, null, cl, holds(cl, cap));
        Pick ppick = new Pick(pp.combo().bullMax(), pp.combo().bearMax(), pp.combo().multiplier(), pp.status(), pp.medianGain(), pp.worstDrawdown(), pl,
                holds(pl, cap));
        return new Rotation(blocks.get(test).index(), blocks.get(test).label(), tune.stream().map(i -> blocks.get(i).index()).toList(), cpick, ppick,
                pl.pnlPct() - cl.pnlPct());
    }
}
