package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ChartDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.EligibilityDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.MetaDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.PhaseDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ResultLineDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationResultDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SwitchDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.WalletDto;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowReplayColumns;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static fr.ses10doigts.tradeIO5.service.expert.simulation.SimulationRegimes.day;

/**
 * Calcul d'une simulation de l'Espace Expert (fonction pure des bougies et des réglages : aucun I/O). Réutilise le cœur du
 * rejeu Java : {@link TrendMixCalculator} pour le Trend, {@link RainbowAtrReplay} pour le Rainbow (barres hors régime =
 * INACTIVES : cf. {@link RainbowAtrReplay#inactive}), {@link RainbowAtrResult#fromRows} pour les agrégats du pine. Le résultat
 * contient tout ce que le graphique affiche, aux colonnes de {@link RainbowReplayColumns} : aucun recalcul côté JS.
 */
public final class SimulationEngine {

    private static final int TREND_NONE = 0;
    private static final int TREND_UP = 1;
    private static final int TREND_DOWN = 2;
    private static final double INTEGRAL_LIMIT = 1e15;

    private SimulationEngine() {
    }

    /**
     * @param eligibility éligibilité du provider utilisé (calculée par l'appelant)
     * @param warnings    avertissements déjà connus de l'appelant (ex. substitution de provider), repris dans le résultat
     */
    public static SimulationResultDto run(SimulationInput in, SimulationInput.Provenance provenance, EligibilityDto eligibility,
                                          List<String> warnings) {
        List<MarketData> candles = in.candles();
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("Aucune bougie à simuler");
        }
        int n = candles.size();
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        TrendMixCalculator.Params tp = in.trend();
        double[] sma = ds.sma(tp.smaPeriod());
        double[] atr = ds.atr(tp.atrPeriod());
        TrendMixCalculator.Result mix = TrendMixCalculator.compute(candles, sma, atr, tp);
        TrendRegime[] raw = mix.regime();
        TrendRegime[] regime = SimulationRegimes.resolve(raw, in.rangeMapping());

        LocalDate firstDay = day(ds.time(0));
        LocalDate to = in.to() == null ? day(ds.time(n - 1)) : in.to();
        LocalDate from = in.from() == null ? to.minusMonths(ExpertSimulationSettings.DEFAULT_RANGE_MONTHS) : in.from();
        if (from.isAfter(to)) {
            throw new ExpertValidationException("from", "doit précéder la fin de plage (to)");
        }
        int start = firstIndexOnOrAfter(ds, from);
        int end = lastIndexOnOrBefore(ds, to);
        if (start < 0 || end < 0 || start > end) {
            throw new ExpertValidationException("from", "aucune bougie dans la plage demandée");
        }

        List<String> allWarnings = new ArrayList<>(warnings);
        if (from.isBefore(firstDay)) {
            allWarnings.add("Plage demandée avant la 1re bougie disponible (" + firstDay + ") : début ramené à cette date.");
        }
        if (provenance.used() != null && MarketDataProviderLimits.maxD1Candles(provenance.used()) < MarketDataProviderLimits.MAX_D1_HISTORY_CANDLES
                && n >= MarketDataProviderLimits.maxD1Candles(provenance.used())) {
            allWarnings.add("Historique plafonné par " + provenance.used() + " (" + n + " bougies D1) : l'ATH et l'automate To the moon "
                    + "sont calculés sur cette seule profondeur.");
        }
        if (regime[start] == null) {
            allWarnings.add("Trend non résolu au début de la plage : barres inactives jusqu'à la 1re phase.");
        }

        LocalDate effectiveFrom = day(ds.time(start));
        LocalDate effectiveTo = day(ds.time(end));
        List<PhaseDto> phases = SimulationRegimes.phases(regime, time(ds), start, end);
        List<SwitchDto> switches = SimulationRegimes.switches(regime, time(ds), start, end);
        var metrics = SimulationRegimes.metrics(phases, switches, regime, start, end);

        ChartDto chart;
        List<ResultLineDto> lines = List.of();
        String rainbowLabel = null;
        if (in.type() == SimulationType.TREND) {
            chart = trendChart(ds, regime, mix, sma, atr, tp.atrMultiplier(), tp, start, end);
        } else {
            RainbowAtrParamSet[] sets;
            int[] setOfBar = new int[n];
            if (in.type() == SimulationType.AFFECTATION) {
                sets = new RainbowAtrParamSet[]{in.down(), in.up()};
                for (int i = 0; i < n; i++) {
                    setOfBar[i] = regime[i] == TrendRegime.UP ? RainbowAtrPresets.BULL
                            : regime[i] == TrendRegime.DOWN ? RainbowAtrPresets.BEAR : RainbowAtrReplay.inactive(RainbowAtrPresets.BEAR);
                }
                rainbowLabel = "UP " + in.up().name() + " / DOWN " + in.down().name();
            } else {
                RainbowAtrParamSet chosen = in.signal() == TrendRegime.UP ? in.up() : in.down();
                sets = new RainbowAtrParamSet[]{chosen};
                for (int i = 0; i < n; i++) {
                    setOfBar[i] = regime[i] == in.signal() ? 0 : RainbowAtrReplay.inactive(0);
                }
                rainbowLabel = chosen.name() + " sur " + in.signal();
            }
            List<DayRow> rows = RainbowAtrReplay.run(ds, sets, setOfBar, start, end);
            if (rows.isEmpty()) {
                throw new ExpertValidationException("from", "aucune bougie rejouable dans la plage (SMA / ATR pas encore définis)");
            }
            chart = rainbowChart(rows, regime, sets);
            lines = lines(in, rows, sets, rainbowLabel, effectiveFrom, effectiveTo);
        }

        MetaDto meta = new MetaDto(in.type().name(), in.assetSymbol(), provenance.requestedProvider(),
                provenance.used() == null ? null : provenance.used().name(), provenance.providerSymbol(), provenance.substituted(),
                Instant.ofEpochMilli(ds.time(0)), Instant.ofEpochMilli(ds.time(n - 1)), n, effectiveFrom,
                effectiveTo, in.trendLabel(), rainbowLabel, List.copyOf(allWarnings));
        return new SimulationResultDto(meta, eligibility, phases, switches, metrics, chart, lines);
    }

    // ---------------------------------------------------------------- graphique

    /**
     * Graphique du Trend seul : bougies, SMA et bandes SMA ± k × ATR ({@code tSma}, {@code tUp}, {@code tDn}, null avant le
     * warmup) et, par bougie, l'état de la régression ({@code tReg}) et de la SMA ({@code tSmaReg}) : 0 non défini (warmup),
     * 1 UP, 2 DOWN, 3 RANGE (régression indécise, ou SMA sans bande encore franchie).
     */
    private static ChartDto trendChart(RainbowAtrDataset ds, TrendRegime[] regime, TrendMixCalculator.Result mix, double[] sma,
                                       double[] atr, double k, TrendMixCalculator.Params tp, int start, int end) {
        List<String> d = new ArrayList<>();
        List<Integer> trend = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        Map<String, List<Number>> cols = new LinkedHashMap<>();
        for (String c : new String[]{"o", "h", "l", "c", "tSma", "tUp", "tDn", "tReg", "tSmaReg", "tRaw", "tScore", "tSub0", "tSub1", "tSub2",
                "tOlsS0", "tOlsE0", "tOlsS1", "tOlsE1", "tOlsS2", "tOlsE2"}) {
            cols.put(c, new ArrayList<>());
        }
        for (int i = start; i <= end; i++) {
            d.add(day(ds.time(i)).toString());
            trend.add(trendCode(regime[i]));
            active.add(regime[i] == null ? 0 : 1);
            cols.get("o").add(number(ds.open(i)));
            cols.get("h").add(number(ds.high(i)));
            cols.get("l").add(number(ds.low(i)));
            cols.get("c").add(number(ds.close(i)));
            boolean banded = Double.isFinite(sma[i]) && Double.isFinite(atr[i]);
            cols.get("tSma").add(banded ? number(sma[i]) : null);
            cols.get("tUp").add(banded ? number(sma[i] + k * atr[i]) : null);
            cols.get("tDn").add(banded ? number(sma[i] - k * atr[i]) : null);
            cols.get("tReg").add(stateCode(mix.regression()[i]));
            cols.get("tSmaReg").add(stateCode(mix.sma()[i]));
            cols.get("tRaw").add(stateCode(mix.rawRegression()[i]));
            cols.get("tScore").add(number(mix.score()[i]));
            int[] windows = {tp.shortWindow(), tp.mediumWindow(), tp.longWindow()};
            for (int w = 0; w < 3; w++) {
                cols.get("tSub" + w).add(number(mix.windowScores()[w][i]));
                double[] ends = olsEnds(ds, i, windows[w]);
                cols.get("tOlsS" + w).add(ends == null ? null : number(ends[0]));
                cols.get("tOlsE" + w).add(ends == null ? null : number(ends[1]));
            }
        }
        return new ChartDto(d, trend, active, cols, null, List.of(), Map.<String, Number>of("shortWindow", tp.shortWindow(), "mediumWindow", tp.mediumWindow(),
                "longWindow", tp.longWindow(), "enter", tp.enter(), "exit", tp.exit(), "confirm", tp.confirm(),
                "atrMultiplier", tp.atrMultiplier()));
    }

    private static ChartDto rainbowChart(List<DayRow> rows, TrendRegime[] regime, RainbowAtrParamSet[] sets) {
        List<String> d = new ArrayList<>();
        List<Integer> trend = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        for (DayRow r : rows) {
            d.add(day(r.timeMillis()).toString());
            trend.add(trendCode(regime[r.index()]));
            active.add(r.active() ? 1 : 0);
        }
        Map<String, List<Number>> cols = new LinkedHashMap<>();
        for (Map.Entry<String, Function<DayRow, Object>> e : RainbowReplayColumns.COLS.entrySet()) {
            List<Number> values = new ArrayList<>(rows.size());
            for (DayRow r : rows) {
                values.add(number(e.getValue().apply(r)));
            }
            cols.put(e.getKey(), values);
        }
        List<SetDto> setDtos = new ArrayList<>();
        for (RainbowAtrParamSet s : sets) {
            setDtos.add(new SetDto(s.name(), s.tuning(), s.globals()));
        }
        return new ChartDto(d, trend, active, cols, wallet(rows), setDtos, Map.of());
    }

    /** Wallet fermé, comme le gabarit : capital = max(investi − vendu) ; stable = capital − investi + vendu ; actif = position × prix de valorisation (gelé hors régime). */
    private static WalletDto wallet(List<DayRow> rows) {
        double capital = 0;
        for (DayRow r : rows) {
            capital = Math.max(capital, r.invested() - r.saleProceeds());
        }
        List<Number> asset = new ArrayList<>(rows.size());
        List<Number> stable = new ArrayList<>(rows.size());
        List<Number> total = new ArrayList<>(rows.size());
        for (DayRow r : rows) {
            double a = r.position() * r.valuationPrice();
            double s = capital - r.invested() + r.saleProceeds();
            asset.add(number(a));
            stable.add(number(s));
            total.add(number(a + s));
        }
        return new WalletDto(capital, asset, stable, total);
    }

    // ---------------------------------------------------------------- fenêtre de résultats (tableau du pine)

    private static List<ResultLineDto> lines(SimulationInput in, List<DayRow> rows, RainbowAtrParamSet[] sets, String rainbowLabel,
                                             LocalDate from, LocalDate to) {
        RainbowAtrResult res = RainbowAtrResult.fromRows(rows, sets[0].globals().baseAmount());
        RainbowAtrGlobals g = sets[rows.getLast().set()].globals();
        long activeBars = rows.stream().filter(DayRow::active).count();
        List<ResultLineDto> l = new ArrayList<>();
        l.add(text(0, "setup", "Paramétrage", in.assetSymbol() + " · Trend " + in.trendLabel() + " · Rainbow " + rainbowLabel));
        l.add(text(1, "range", "Plage visible", from + " → " + to + " (" + rows.size() + " barres, " + activeBars + " actives)"));
        l.add(value(2, "invested", "Total $ investi", res.invested(), "$"));
        l.add(value(3, "saleProceeds", "Total $ vendu", res.saleProceeds(), "$"));
        l.add(value(4, "currentValue", "Total $ restant", res.currentValue(), "$"));
        l.add(value(5, "totalGain", "Gain total", res.totalGain(), "$"));
        l.add(value(6, "realizedGain", "Somme gains réalisés", res.realizedGain(), "$"));
        l.add(value(7, "realizedPercent", "PnL % réel", res.realizedPercent(), "%"));
        l.add(value(8, "potentialPercent", "PnL % potentiel", res.potentialPercent(), "%"));
        l.add(value(9, "fixedPnlPercent", "PnL % potentiel DCA fixe", res.fixedPnlPercent(), "%"));
        l.add(new ResultLineDto(10, "athModulation", "Modulation ATH (distance fin de plage)", res.lastAthDistance() * 100,
                g.athOn() ? String.format(Locale.ROOT, "achat ×%.2f→×%.2f, vente ×%.2f→×%.2f", g.athBuyMin(), g.athBuyMax(),
                        g.athSellMax(), g.athSellMin()) : "désactivée", "%"));
        l.add(text(11, "moon", "To the moon", res.moonModeLast() ? "Oui" : "Non"));
        l.add(value(12, "protectedBag", "Bag protégé", res.moonReserveQty() * res.lastPrice(), "$"));
        l.add(text(13, "moonReserve", "Réserve moon", !g.moonOn() ? "moon désactivé"
                : (g.moonReserveRatchet() ? "cliquet " : "figée ") + String.format(Locale.ROOT, "%.0f %%", g.moonReservePct())));
        return l;
    }

    private static ResultLineDto value(int index, String key, String label, double v, String unit) {
        return new ResultLineDto(index, key, label, Double.isFinite(v) ? v : null, null, unit);
    }

    private static ResultLineDto text(int index, String key, String label, String text) {
        return new ResultLineDto(index, key, label, null, text, null);
    }

    // ---------------------------------------------------------------- utilitaires

    /** Droite des moindres carrés des clôtures sur les {@code w} bougies finissant en {@code i} : {valeur au début, valeur à la fin}. */
    private static double[] olsEnds(RainbowAtrDataset ds, int i, int w) {
        if (i < w - 1 || w < 2) {
            return null;
        }
        double sy = 0, sxy = 0;
        for (int k = 0; k < w; k++) {
            double y = ds.close(i - w + 1 + k);
            sy += y;
            sxy += k * y;
        }
        double sx = w * (w - 1) / 2.0, sxx = (w - 1.0) * w * (2.0 * w - 1) / 6.0;
        double slope = (w * sxy - sx * sy) / (w * sxx - sx * sx);
        double intercept = (sy - slope * sx) / w;
        return new double[]{intercept, intercept + slope * (w - 1)};
    }

    private static int stateCode(TrendRegime r) {
        return r == null ? 0 : r == TrendRegime.UP ? TREND_UP : r == TrendRegime.DOWN ? TREND_DOWN : 3;
    }

    private static int trendCode(TrendRegime r) {
        return r == TrendRegime.UP ? TREND_UP : r == TrendRegime.DOWN ? TREND_DOWN : TREND_NONE;
    }

    /** Valeur JSON : entier si la valeur l'est, {@code null} si non finie (jamais de NaN dans la réponse). */
    static Number number(Object v) {
        if (v instanceof Integer || v instanceof Long) {
            return (Number) v;
        }
        double d = ((Number) v).doubleValue();
        if (!Double.isFinite(d)) {
            return null;
        }
        return d == Math.rint(d) && Math.abs(d) < INTEGRAL_LIMIT ? (Number) (long) d : (Number) d;
    }

    private static long[] time(RainbowAtrDataset ds) {
        long[] t = new long[ds.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ds.time(i);
        }
        return t;
    }

    private static int firstIndexOnOrAfter(RainbowAtrDataset ds, LocalDate d) {
        for (int i = 0; i < ds.size(); i++) {
            if (!day(ds.time(i)).isBefore(d)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOnOrBefore(RainbowAtrDataset ds, LocalDate d) {
        for (int i = ds.size() - 1; i >= 0; i--) {
            if (!day(ds.time(i)).isAfter(d)) {
                return i;
            }
        }
        return -1;
    }
}
