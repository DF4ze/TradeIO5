package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos;
import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.AssetGainDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.EpisodeAggregateDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.EpisodeDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.Gain;
import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.GainBreakdownDto;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.TradeDay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Timeline des épisodes et répartition du gain d'un rejeu, fonction PURE (aucun I/O, aucun Spring), rappelable sur une sous-plage
 * à partir de la série déjà calculée (pas de rejeu).
 * <p>
 * Épisode : suite de jours d'un actif à même (régime, zone) ; nouvel épisode à chaque changement de zone et à chaque bascule
 * Bull/Bear ; barres inactives = épisodes « INACTIF ». Plage {@code [from, to]} : épisodes tronqués aux bords.
 * <p>
 * PnL (prix moyen pondéré, frais inclus) : prix de revient de la position d'ouverture = cours du 1er jour de la plage ; achat =
 * cash réellement payé, vente au prix de revient moyen courant. PnL d'un épisode = variation de (réalisé cumulé + latent) :
 * zone neutre ⇒ variation du latent seule. Σ PnL de tous les épisodes = variation de valeur (cash + actifs) du wallet sur la plage,
 * valorisée au cours du jour (1er jour : cours d'ouverture = cours du 1er jour), actifs hors liens exclus.
 * <p>
 * Répartition du gain (écart stratégie − DCA fixe, par jour donc par épisode, exactement additive). Avec {@code q} quantité
 * détenue en fin de jour, {@code d = qS − qF}, {@code P} cours du jour, {@code trade = cash + Δq·P} :
 * exposition = {@code d̄ · ΔP} ; timing = {@code (d_{t−1} − d̄) · ΔP} + écart d'exécution ({@code trade + coût} stratégie − référence) ;
 * frais = −(coût stratégie − coût référence). {@code d̄} = moyenne de {@code d_{t−1}} sur la plage : sur deux sous-plages, PnL et
 * frais s'additionnent mais le partage exposition / timing dépend de chaque {@code d̄}.
 */
public final class EpisodeTimeline {

    private static final String[] ZONE_LABELS = {"extrême bas", "X2", "X1", "X0,5", "pas d'achat", "extrême haut"};

    private EpisodeTimeline() {
    }

    /** Résultat : épisodes chronologiques, agrégat par (régime, zone), répartition du gain, PnL total. */
    public record Report(List<EpisodeDto> episodes, List<EpisodeAggregateDto> aggregates, GainBreakdownDto gain, double pnlTotal) {
    }

    /** Série d'un actif : jours chronologiques, position et référence initiales (avant le 1er jour de la série). */
    public record AssetSeries(String asset, List<TradeDay> days, double qty0, Map<LocalDate, Double> exposurePct) {
    }

    public static String zoneLabel(int zone) {
        return zone >= RainbowAtrEngine.EXTREME_BAS && zone <= RainbowAtrEngine.EXTREME_HAUT ? ZONE_LABELS[zone] : "hors bornes";
    }

    /** Timeline de plusieurs actifs sur {@code [from, to]} (bornes nulles = série entière). */
    public static Report compute(List<AssetSeries> series, LocalDate from, LocalDate to) {
        List<EpisodeDto> episodes = new ArrayList<>();
        List<AssetGainDto> perAsset = new ArrayList<>();
        Gain total = Gain.ZERO;
        for (AssetSeries s : series) {
            AssetResult r = computeAsset(s, from, to);
            episodes.addAll(r.episodes());
            perAsset.add(new AssetGainDto(s.asset(), r.meanDiff(), r.gain()));
            total = total.plus(r.gain());
        }
        episodes.sort(java.util.Comparator.comparing(EpisodeDto::from).thenComparing(EpisodeDto::asset));
        double pnl = episodes.stream().mapToDouble(EpisodeDto::pnlTotal).sum();
        return new Report(episodes, aggregate(episodes), new GainBreakdownDto(total, perAsset), pnl);
    }

    private record AssetResult(List<EpisodeDto> episodes, double meanDiff, Gain gain) {
    }

    /** Accumulateur d'un épisode en cours. */
    private static final class Acc {
        String regime;
        int zone;
        LocalDate from;
        LocalDate to;
        int days;
        double boughtUsdc;
        double boughtQty;
        double soldUsdc;
        double soldQty;
        double realized0;
        double latent0;
        double expo0;
        boolean expo0Known;
        double expo;
        boolean expoKnown;
        double gExposure;
        double gTiming;
        double gFees;
    }

    private static AssetResult computeAsset(AssetSeries s, LocalDate from, LocalDate to) {
        List<TradeDay> all = s.days();
        double qS = s.qty0();
        double qF = s.qty0();
        List<TradeDay> range = new ArrayList<>();
        for (TradeDay t : all) {
            if (from != null && t.day().isBefore(from)) {
                qS += t.qtyDelta(); // état d'ouverture de la plage
                qF += t.fixedQtyDelta();
            } else if (to == null || !t.day().isAfter(to)) {
                range.add(t);
            }
        }
        if (range.isEmpty()) {
            return new AssetResult(List.of(), 0.0, Gain.ZERO);
        }
        // d̄ : moyenne de la différence de quantité détenue à l'ouverture de chaque jour
        double sumD = 0.0;
        double sQ = qS;
        double fQ = qF;
        for (TradeDay t : range) {
            sumD += sQ - fQ;
            sQ += t.qtyDelta();
            fQ += t.fixedQtyDelta();
        }
        double meanD = sumD / range.size();

        double price0 = range.getFirst().price();
        double qty = qS; // position stratégie
        double basis = qty * price0; // prix de revient total
        double realized = 0.0; // PnL réalisé cumulé
        double qRef = qF; // position référence
        double prevPrice = price0;
        List<EpisodeDto> out = new ArrayList<>();
        Acc a = null;
        Double prevExpo = null;
        double totExposure = 0.0;
        double totTiming = 0.0;
        double totFees = 0.0;
        for (TradeDay t : range) {
            if (a == null || !Objects.equals(a.regime, regimeOf(t)) || a.zone != t.zone()) {
                if (a != null) {
                    out.add(close(s.asset(), a, qty, basis, realized, prevPrice));
                }
                a = new Acc();
                a.regime = regimeOf(t);
                a.zone = t.zone();
                a.from = t.day();
                a.realized0 = realized;
                a.latent0 = qty * prevPrice - basis;
                a.expo0Known = prevExpo != null;
                a.expo0 = prevExpo == null ? 0.0 : prevExpo;
            }
            double price = t.price();
            double dP = price - prevPrice;
            double dPrev = qty - qRef; // d_{t−1}
            // PnL : prix moyen pondéré
            if (t.qtyDelta() > 0) {
                basis += -t.cashDelta();
                qty += t.qtyDelta();
                a.boughtUsdc += -t.cashDelta();
                a.boughtQty += t.qtyDelta();
            } else if (t.qtyDelta() < 0) {
                double sold = -t.qtyDelta();
                double avg = qty > 0 ? basis / qty : 0.0;
                realized += t.cashDelta() - avg * sold;
                basis -= avg * sold;
                qty = Math.max(0.0, qty - sold);
                a.soldUsdc += t.cashDelta();
                a.soldQty += sold;
            }
            qRef += t.fixedQtyDelta();
            // répartition du gain (par jour)
            double gapS = t.cashDelta() + t.qtyDelta() * price + t.costUsdc();
            double gapF = t.fixedCashDelta() + t.fixedQtyDelta() * price + t.fixedCostUsdc();
            double exposure = meanD * dP;
            double timing = (dPrev - meanD) * dP + (gapS - gapF);
            double fees = -(t.costUsdc() - t.fixedCostUsdc());
            a.gExposure += exposure;
            a.gTiming += timing;
            a.gFees += fees;
            totExposure += exposure;
            totTiming += timing;
            totFees += fees;
            a.to = t.day();
            a.days++;
            Double e = s.exposurePct() == null ? null : s.exposurePct().get(t.day());
            a.expoKnown = e != null;
            a.expo = e == null ? 0.0 : e;
            prevExpo = e;
            prevPrice = price;
        }
        out.add(close(s.asset(), a, qty, basis, realized, prevPrice));
        Gain g = new Gain(totExposure, totTiming, totFees, totExposure + totTiming + totFees);
        return new AssetResult(out, meanD, g);
    }

    private static EpisodeDto close(String asset, Acc a, double qty, double basis, double realized, double price) {
        double latent = qty * price - basis;
        double dReal = realized - a.realized0;
        double dLat = latent - a.latent0;
        Double avg = qty > 0 ? basis / qty : null;
        return new EpisodeDto(asset, a.regime, a.zone, zoneLabel(a.zone), a.from, a.to, a.days, a.boughtUsdc, a.boughtQty,
                a.soldUsdc, a.soldQty, avg, dReal, dLat, dReal + dLat, a.expo0Known ? a.expo0 : null, a.expoKnown ? a.expo : null,
                new Gain(a.gExposure, a.gTiming, a.gFees, a.gExposure + a.gTiming + a.gFees));
    }

    private static String regimeOf(TradeDay t) {
        return t.regime() == null ? EpisodeDtos.INACTIVE : t.regime();
    }

    private static List<EpisodeAggregateDto> aggregate(List<EpisodeDto> episodes) {
        Map<String, EpisodeAggregateDto> by = new LinkedHashMap<>();
        for (EpisodeDto e : episodes) {
            String key = e.regime() + "|" + e.zone();
            EpisodeAggregateDto o = by.get(key);
            by.put(key, o == null
                    ? new EpisodeAggregateDto(e.regime(), e.zone(), e.zoneLabel(), 1, e.days(), e.boughtUsdc(), e.soldUsdc(),
                    e.pnlRealized(), e.pnlLatent(), e.pnlTotal(), e.gain())
                    : new EpisodeAggregateDto(o.regime(), o.zone(), o.zoneLabel(), o.episodes() + 1, o.days() + e.days(),
                    o.boughtUsdc() + e.boughtUsdc(), o.soldUsdc() + e.soldUsdc(), o.pnlRealized() + e.pnlRealized(),
                    o.pnlLatent() + e.pnlLatent(), o.pnlTotal() + e.pnlTotal(), o.gain().plus(e.gain())));
        }
        return new ArrayList<>(by.values());
    }
}
