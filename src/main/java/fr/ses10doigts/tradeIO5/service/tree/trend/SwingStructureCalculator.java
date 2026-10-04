package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Détection de plus hauts/plus bas confirmés et classification HH/HL/LH/LL, régime
 * BULL/BEAR/WARNING_BULL_BREAK/WARNING_BEAR_BREAK/UNDEFINED.
 * <p>
 * Réécrit intégralement le 2026-09-19 à la demande de Clem : l'ancienne version (filtre adaptatif
 * {@code atrMultiplier x ATR}, cf. historique git) confirmait un pivot seulement une fois le prix
 * éloigné du candidat d'au moins ce seuil — un délai potentiellement long et non maîtrisé, jugé
 * inexploitable en prod. Cette version confirme un pivot dès la bougie qui apporte la preuve du
 * retournement (voir algorithme ci-dessous), sans notion d'ATR ni de seuil : aucun paramètre de
 * calibration, la classe n'a plus de dépendance vers ATR.
 * <p>
 * <b>Algorithme</b> (2 candidats toujours actifs, extension "au plus haut"/"au plus bas") :
 * <ul>
 *     <li>{@code candidateHigh} s'étend à chaque nouveau plus haut ({@code candle.high > candidateHigh}) ;
 *     {@code candidateLow} s'étend à chaque nouveau plus bas ({@code candle.low < candidateLow}) —
 *     ces deux extensions sont indépendantes et peuvent se produire sur la même bougie (bougie
 *     "outside").</li>
 *     <li><b>Confirmation</b> : sur une bougie donnée, si {@code candidateLow} s'étend et que
 *     {@code candidateHigh} NE s'étend PAS, {@code candidateHigh} est figé et confirmé comme pivot
 *     (classé HH/LH par comparaison au précédent pivot high confirmé), puis réarmé sur cette même
 *     bougie. Symétriquement, si {@code candidateHigh} s'étend seul, {@code candidateLow} est figé,
 *     confirmé (HL/LL) et réarmé. Ces deux conditions sont mutuellement exclusives par construction
 *     (l'une exige {@code highExtended=false}, l'autre {@code highExtended=true}) : au plus UNE
 *     confirmation par bougie, jamais les deux à la fois (contrairement à l'ancienne version) — si
 *     aucun des deux ne s'étend (bougie "inside"), ou si les deux s'étendent (bougie "outside"),
 *     rien n'est confirmé sur cette bougie, on attend la suivante.</li>
 * </ul>
 * Cette règle donne le délai minimal possible : un pivot ne peut être connu qu'une fois prouvé par
 * un mouvement en sens inverse (par construction, jamais avant), et ici la preuve minimale — une
 * seule bougie de retournement — suffit à confirmer, sans attendre un franchissement de seuil.
 * <p>
 * Contrepartie assumée, signalée explicitement (pas glissée silencieusement) : sans filtre
 * d'amplitude, un pivot se confirme aussi sur un retracement mineur au sein d'une tendance propre
 * (ex. chaque bougie d'une montée régulière où les plus bas remontent en continu confirme un
 * nouveau pivot low HL) — la classification/régime reste correcte (le sens n'est jamais faux), mais
 * le nombre de pivots confirmés est plus élevé qu'un tracé "à la main", ce qui rend la Confiance
 * (run-length, cf. {@link TrendAnalyzer}) plus sensible au bruit qu'avec l'ancien filtre ATR.
 * <p>
 * Service pur (couche calculateur) : ne dépend ni de {@code IndicatorResult}, ni de
 * {@code IndicatorContext}, ni de Spring.
 */
public class SwingStructureCalculator {

    /**
     * @param candles bougies triées chronologiquement (comme
     *                {@code context.marketDataset().getMarketDatas()})
     */
    public SwingStructureState compute(List<MarketData> candles) {
        if (candles == null) {
            throw new IllegalArgumentException("candles must not be null");
        }
        if (candles.isEmpty()) {
            return new SwingStructureState(SwingStructureRegime.UNDEFINED, null, null, null, null, List.of());
        }

        MarketData first = candles.get(0);
        Extreme candidateLow = new Extreme(first.getLow(), first.getTimestamp());
        Extreme candidateHigh = new Extreme(first.getHigh(), first.getTimestamp());

        SwingPivot lastSwingHigh = null;
        SwingPivot previousSwingHigh = null;
        SwingPivot lastSwingLow = null;
        SwingPivot previousSwingLow = null;

        List<BigDecimal> allConfirmedPrices = new ArrayList<>();

        SwingStructureRegime regime = SwingStructureRegime.UNDEFINED;
        Boolean lastConfirmedBull = null;

        for (int i = 1; i < candles.size(); i++) {
            MarketData candle = candles.get(i);

            boolean highExtended = candle.getHigh().compareTo(candidateHigh.price()) > 0;
            boolean lowExtended = candle.getLow().compareTo(candidateLow.price()) < 0;

            if (highExtended) {
                candidateHigh = new Extreme(candle.getHigh(), candle.getTimestamp());
            }
            if (lowExtended) {
                candidateLow = new Extreme(candle.getLow(), candle.getTimestamp());
            }

            boolean pivotConfirmedThisCandle = false;

            if (lowExtended && !highExtended) {
                SwingPivot previous = lastSwingHigh;
                PivotClassification classification = previous == null
                        ? null
                        : (candidateHigh.price().compareTo(previous.price()) > 0 ? PivotClassification.HH : PivotClassification.LH);
                SwingPivot pivot = new SwingPivot(true, candidateHigh.timestamp(), candidateHigh.price(), classification);

                previousSwingHigh = previous;
                lastSwingHigh = pivot;
                allConfirmedPrices.add(pivot.price());
                pivotConfirmedThisCandle = true;

                candidateHigh = new Extreme(candle.getHigh(), candle.getTimestamp());
            } else if (highExtended && !lowExtended) {
                SwingPivot previous = lastSwingLow;
                PivotClassification classification = previous == null
                        ? null
                        : (candidateLow.price().compareTo(previous.price()) > 0 ? PivotClassification.HL : PivotClassification.LL);
                SwingPivot pivot = new SwingPivot(false, candidateLow.timestamp(), candidateLow.price(), classification);

                previousSwingLow = previous;
                lastSwingLow = pivot;
                allConfirmedPrices.add(pivot.price());
                pivotConfirmedThisCandle = true;

                candidateLow = new Extreme(candle.getLow(), candle.getTimestamp());
            }

            if (pivotConfirmedThisCandle) {
                RegimeUpdate update = recomputeRegime(lastSwingHigh, lastSwingLow, lastConfirmedBull);
                regime = update.regime();
                lastConfirmedBull = update.lastConfirmedBull();
            }
        }

        BigDecimal lastClose = candles.get(candles.size() - 1).getClose();
        List<BigDecimal> nearestSrLevels = allConfirmedPrices.stream()
                .sorted(Comparator.comparing(price -> price.subtract(lastClose).abs()))
                .limit(4)
                .toList();

        return new SwingStructureState(regime, lastSwingHigh, lastSwingLow, previousSwingHigh, previousSwingLow, nearestSrLevels);
    }

    /**
     * Variante de {@link #compute} exposant l'état complet au fil du temps  -  une
     * {@link SwingStructureSnapshot} par bougie d'entrée, même taille et même ordre que
     * {@code candles}  -  plutôt que seulement l'état final. Besoin introduit par le harnais de
     * calibration de l'ancienne version (cf. historique git) et toujours utile à
     * {@code TrendConfirmationStrategy}, qui a besoin du run-length du régime (cf.
     * {@link TrendAnalyzer}), pas seulement de sa valeur finale.
     * <p>
     * <b>Duplique volontairement</b> la boucle de {@link #compute} plutôt que de la faire déléguer
     * à cette méthode (ou l'inverse) — même choix qu'à l'origine de cette classe : {@link #compute}
     * reste un code testé indépendamment, le laisser inchangé élimine tout risque de régression
     * involontaire sur ses propres tests.
     *
     * @param candles identique à {@link #compute}
     */
    public List<SwingStructureSnapshot> computeTimeline(List<MarketData> candles) {
        if (candles == null) {
            throw new IllegalArgumentException("candles must not be null");
        }

        List<SwingStructureSnapshot> timeline = new ArrayList<>(candles.size());
        if (candles.isEmpty()) {
            return timeline;
        }

        MarketData first = candles.get(0);
        Extreme candidateLow = new Extreme(first.getLow(), first.getTimestamp());
        Extreme candidateHigh = new Extreme(first.getHigh(), first.getTimestamp());

        SwingPivot lastSwingHigh = null;
        SwingPivot lastSwingLow = null;

        SwingStructureRegime regime = SwingStructureRegime.UNDEFINED;
        Boolean lastConfirmedBull = null;

        timeline.add(new SwingStructureSnapshot(first.getTimestamp(), regime, List.of()));

        for (int i = 1; i < candles.size(); i++) {
            MarketData candle = candles.get(i);

            boolean highExtended = candle.getHigh().compareTo(candidateHigh.price()) > 0;
            boolean lowExtended = candle.getLow().compareTo(candidateLow.price()) < 0;

            if (highExtended) {
                candidateHigh = new Extreme(candle.getHigh(), candle.getTimestamp());
            }
            if (lowExtended) {
                candidateLow = new Extreme(candle.getLow(), candle.getTimestamp());
            }

            List<SwingPivot> confirmedThisCandle = new ArrayList<>(1);

            if (lowExtended && !highExtended) {
                SwingPivot previous = lastSwingHigh;
                PivotClassification classification = previous == null
                        ? null
                        : (candidateHigh.price().compareTo(previous.price()) > 0 ? PivotClassification.HH : PivotClassification.LH);
                SwingPivot pivot = new SwingPivot(true, candidateHigh.timestamp(), candidateHigh.price(), classification);

                lastSwingHigh = pivot;
                confirmedThisCandle.add(pivot);

                candidateHigh = new Extreme(candle.getHigh(), candle.getTimestamp());
            } else if (highExtended && !lowExtended) {
                SwingPivot previous = lastSwingLow;
                PivotClassification classification = previous == null
                        ? null
                        : (candidateLow.price().compareTo(previous.price()) > 0 ? PivotClassification.HL : PivotClassification.LL);
                SwingPivot pivot = new SwingPivot(false, candidateLow.timestamp(), candidateLow.price(), classification);

                lastSwingLow = pivot;
                confirmedThisCandle.add(pivot);

                candidateLow = new Extreme(candle.getLow(), candle.getTimestamp());
            }

            if (!confirmedThisCandle.isEmpty()) {
                RegimeUpdate update = recomputeRegime(lastSwingHigh, lastSwingLow, lastConfirmedBull);
                regime = update.regime();
                lastConfirmedBull = update.lastConfirmedBull();
            }

            timeline.add(new SwingStructureSnapshot(candle.getTimestamp(), regime, List.copyOf(confirmedThisCandle)));
        }

        return timeline;
    }

    /**
     * Régime recalculé après chaque nouveau pivot confirmé : ne rebascule en
     * {@code BULL_CONFIRMED}/{@code BEAR_CONFIRMED} que lorsque les deux jambes confirment le même
     * sens ; sinon warning dont le sens dépend du dernier régime pleinement confirmé (pas de la
     * seule combinaison courante).
     */
    private RegimeUpdate recomputeRegime(SwingPivot lastSwingHigh, SwingPivot lastSwingLow, Boolean lastConfirmedBull) {
        if (lastSwingHigh == null || lastSwingLow == null
                || lastSwingHigh.classification() == null || lastSwingLow.classification() == null) {
            return new RegimeUpdate(SwingStructureRegime.UNDEFINED, lastConfirmedBull);
        }

        boolean highConfirmsBull = lastSwingHigh.classification() == PivotClassification.HH;
        boolean lowConfirmsBull = lastSwingLow.classification() == PivotClassification.HL;
        boolean highConfirmsBear = lastSwingHigh.classification() == PivotClassification.LH;
        boolean lowConfirmsBear = lastSwingLow.classification() == PivotClassification.LL;

        if (highConfirmsBull && lowConfirmsBull) {
            return new RegimeUpdate(SwingStructureRegime.BULL_CONFIRMED, true);
        }
        if (highConfirmsBear && lowConfirmsBear) {
            return new RegimeUpdate(SwingStructureRegime.BEAR_CONFIRMED, false);
        }

        if (lastConfirmedBull == null) {
            return new RegimeUpdate(SwingStructureRegime.UNDEFINED, null);
        }
        return lastConfirmedBull
                ? new RegimeUpdate(SwingStructureRegime.WARNING_BEAR_BREAK, lastConfirmedBull)
                : new RegimeUpdate(SwingStructureRegime.WARNING_BULL_BREAK, lastConfirmedBull);
    }

    private record Extreme(BigDecimal price, Instant timestamp) {
    }

    private record RegimeUpdate(SwingStructureRegime regime, Boolean lastConfirmedBull) {
    }
}
