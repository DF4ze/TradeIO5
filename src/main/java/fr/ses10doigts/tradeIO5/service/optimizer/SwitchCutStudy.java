package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AssetCut;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AtCap;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.CapResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Point;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Étape E1 : la coupure du bag au switch Bull → Bear (ré-entrée au switch Bear → Bull) améliore-t-elle le gain à plafond de drawdown égal ?
 * Deux lignes sur les mêmes blocs et la même grille de multiplicateurs : « preset pur » (modulation ATH du preset désactivée : timing pur) et
 * « pur + coupure ». À chaque plafond de profil, chaque ligne est interpolée au multiplicateur où son pire drawdown des blocs atteint le
 * plafond ; on compare le gain bloc par bloc. Hors ligne : rien n'est appliqué ni persisté. Déterministe.
 */
public final class SwitchCutStudy {

    private static final double EPS = 1e-9;

    private SwitchCutStudy() {
    }

    /** Jeux de paramètres sans modulation ATH (facteurs ATH à 1) : le preset reste identique pour le reste. */
    static RainbowAtrParamSet[] withoutAth(RainbowAtrParamSet[] sets) {
        RainbowAtrParamSet[] out = new RainbowAtrParamSet[sets.length];
        for (int i = 0; i < sets.length; i++) {
            RainbowAtrGlobals g = sets[i].globals();
            out[i] = new RainbowAtrParamSet(sets[i].name(), sets[i].tuning(), new RainbowAtrGlobals(false, g.athRefDdBuyPct(), g.athRefDdSellPct(),
                    g.athBuyMin(), g.athBuyMax(), g.athSellMax(), g.athSellMin(), g.moonOn(), g.moonReservePct(), g.moonReserveRatchet(),
                    g.moonTrailingStopPct(), g.moonStopSellPct(), g.multX2(), g.multX1(), g.multX0_5(), g.multTriggered(), g.baseAmount()));
        }
        return out;
    }

    static Point point(SensitivityEvaluator.Line l) {
        return new Point(l.pnlPct(), l.maxDrawdownPct(), l.avgExposurePct());
    }

    public static AssetCut run(ScorerInputs in, SwitchCutConfig cfg) {
        long t0 = System.nanoTime();
        ParityConfig pc = cfg.parity();
        List<Block> specs = TuneBlocks.build(pc.lastEnd(), pc.blockMonths(), pc.blockCount());
        List<SensitivityEvaluator> all = new ArrayList<>();
        for (Block b : specs) {
            all.add(new SensitivityEvaluator(in.withEndDate(b.to()), pc.blockMonths(), b.from()));
        }
        RainbowAtrParamSet[] sets = withoutAth(all.getFirst().template().sets());
        double reference = all.getFirst().referenceBase(sets);
        // blocs rejouables (historique suffisant avant le début) : un actif récent peut en avoir moins de 4
        List<SensitivityEvaluator> evs = new ArrayList<>();
        List<Block> usedSpecs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).parityCut(sets, false, 0, null, null, null, null) != null) {
                evs.add(all.get(i));
                usedSpecs.add(specs.get(i));
            }
        }
        MultiLinkReplayEngine.SwitchCut cut = new MultiLinkReplayEngine.SwitchCut(cfg.sellFraction(), cfg.reentryPct());
        List<Level> levels = new ArrayList<>();
        for (double m : evs.isEmpty() ? List.<Double>of() : cfg.multipliers()) {
            Double lb = ExposureFrontier.linkBase(m, reference);
            List<Point> pure = new ArrayList<>();
            List<Point> cutP = new ArrayList<>();
            int sells = 0;
            int buys = 0;
            double cost = 0;
            boolean ok = true;
            for (SensitivityEvaluator ev : evs) {
                SensitivityEvaluator.CutParity a = ev.parityCut(sets, false, 0, null, lb, null, null);
                SensitivityEvaluator.CutParity b = ev.parityCut(sets, false, 0, null, lb, null, cut);
                if (a == null || b == null) {
                    ok = false;
                    break;
                }
                pure.add(point(a.parity().preset()));
                cutP.add(point(b.parity().preset()));
                sells += b.cutSells();
                buys += b.cutBuys();
                cost += b.cutCostUsdc();
            }
            if (ok) {
                levels.add(new Level(m, pure, cutP, sells, buys, cost));
            }
        }
        List<CapResult> caps = new ArrayList<>();
        for (Map.Entry<StartingProfile, Double> e : FrontierConfig.profileCaps().entrySet()) {
            AtCap pure = atCap(levels, e.getValue(), Level::pure);
            AtCap cutAt = atCap(levels, e.getValue(), Level::cut);
            caps.add(compare(e.getKey().name(), e.getValue(), pure, cutAt, pc));
        }
        int useful = (int) caps.stream().filter(c -> "UTILE".equals(c.verdict())).count();
        int unknown = (int) caps.stream().filter(c -> "NON_CONCLU".equals(c.verdict())).count();
        String verdict = useful >= cfg.minCapsPass() ? "COUPURE_UTILE" : unknown > 0 ? "NON_CONCLU" : useful == 0 ? "COUPURE_INUTILE" : "MIXTE";
        String text = String.format(Locale.ROOT, "UTILE à %d plafond(s) sur %d, non conclu à %d (règle : ≥ %d plafonds UTILES pour retenir ; NON_CONCLU si un plafond "
                + "n'est pas encadré par la grille).", useful, caps.size(), unknown, cfg.minCapsPass());
        long replays = levels.size() * 2L * evs.size();
        return new AssetCut(in.assets().getFirst().slot().assetSymbol(), in.cash0(), reference, usedSpecs.stream().map(Block::label).toList(), levels, caps,
                useful, verdict, text, replays, (System.nanoTime() - t0) / 1_000_000);
    }

    /** Premier franchissement du plafond en montant la grille, interpolé en pire drawdown ; gain de chaque bloc interpolé avec le même poids. */
    static AtCap atCap(List<Level> levels, double cap, Function<Level, List<Point>> line) {
        int k = -1;
        for (int i = 0; i < levels.size(); i++) {
            if (SwitchCutReport.worst(line.apply(levels.get(i))) < cap) {
                k = i;
                break;
            }
        }
        if (k == 0) {
            return new AtCap("SOUS_LE_MINIMUM", null, null, List.of(), null);
        }
        if (k < 0) {
            return new AtCap("PLAFOND_NON_ATTEINT", null, null, List.of(), null);
        }
        Level lo = levels.get(k - 1);
        Level hi = levels.get(k);
        double dlo = SwitchCutReport.worst(line.apply(lo));
        double dhi = SwitchCutReport.worst(line.apply(hi));
        double w = (cap - dlo) / (dhi - dlo);
        List<Point> a = line.apply(lo);
        List<Point> b = line.apply(hi);
        List<Double> gains = new ArrayList<>();
        List<Double> expo = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            gains.add(a.get(i).gainPct() + (b.get(i).gainPct() - a.get(i).gainPct()) * w);
            expo.add(a.get(i).exposurePct() + (b.get(i).exposurePct() - a.get(i).exposurePct()) * w);
        }
        return new AtCap("INTERPOLE", lo.multiplier() + (hi.multiplier() - lo.multiplier()) * w, SwitchCutReport.median(expo), gains,
                SwitchCutReport.median(gains));
    }

    /** Règle (fixée avant lecture) : UTILE si au moins {@code minBlocksPass} blocs où la coupure gagne et Δ gain médian &gt; 0. */
    static CapResult compare(String profile, double cap, AtCap pure, AtCap cut, ParityConfig pc) {
        if (!"INTERPOLE".equals(pure.status()) || !"INTERPOLE".equals(cut.status()) || pure.gains().size() < pc.minBlocksPass()) {
            return new CapResult(profile, cap, pure, cut, List.of(), 0, null, "NON_CONCLU");
        }
        List<Double> d = new ArrayList<>();
        int won = 0;
        for (int i = 0; i < pure.gains().size(); i++) {
            double x = cut.gains().get(i) - pure.gains().get(i);
            d.add(x);
            won += x > EPS ? 1 : 0;
        }
        Double med = SwitchCutReport.median(d);
        return new CapResult(profile, cap, pure, cut, d, won, med, won >= pc.minBlocksPass() && med > 0 ? "UTILE" : "NON_UTILE");
    }
}
