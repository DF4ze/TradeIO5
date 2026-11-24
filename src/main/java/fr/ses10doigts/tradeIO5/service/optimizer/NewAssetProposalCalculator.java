package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Lite;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Window;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Calcul d'une proposition pour un actif sans réglage dédié (D1b, lot 1) : preset de départ = preset BTC (Bull / Bear) joué TEL QUE LE LIVE
 * L'APPLIQUE avec la Trend par défaut du live, puis calage d'exposition par profil = méthode C3 allégée ({@link ExposureCalibrationStudy#lite} :
 * pire cas sur fenêtres glissantes de 24 mois + historique complet, marge {@code k} fournie, bande neutre). Pur, déterministe, sans I/O :
 * rien n'est lu ni écrit hors des arguments. Jamais d'exception pour un historique court ou impossible à rejouer : drapeaux.
 */
public final class NewAssetProposalCalculator {

    private NewAssetProposalCalculator() {
    }

    /**
     * @param btc  preset source (BTC) : jeux Bear / Bull, fenêtre d'analyse, taux de base du lien source
     * @param pool pool dédié (ordre des résultats inchangé) ; {@code null} : série
     */
    public static NewAssetProposalResult compute(String asset, String sourceLabel, List<MarketData> candles, TransferStudy.Source btc, NewAssetConfig cfg,
                                                 double marginK, RainbowModulationControl limits, ExecutorService pool) {
        long t0 = System.nanoTime();
        Set<ProposalFlag> flags = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        if (candles == null || candles.size() < cfg.minCandles()) {
            flags.add(ProposalFlag.HISTORIQUE_INSUFFISANT);
            notes.add("moins de " + cfg.minCandles() + " bougies D1 : aucun rejeu tenté, repli sur le preset BTC non calibré");
            return empty(asset, btc, sourceLabel, candles, flags, marginK, notes);
        }
        TransferStudy.Target target = new TransferStudy.Target(asset, sourceLabel, candles);
        LocalDate first = candles.getFirst().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = target.lastDay();
        int months = (int) ChronoUnit.MONTHS.between(first, last);
        CandleFingerprint fp = CandleFingerprint.of(asset, candles, last);
        if (months < cfg.provisionalMonths()) {
            flags.add(ProposalFlag.PROVISOIRE);
        }
        TransferConfig tc = TransferConfig.defaults();
        ScorerInputs in;
        ExposureCalibrationConfig cc = ExposureCalibrationConfig.defaults(last);
        LocalDate firstReplayable;
        try {
            in = TransferStudy.inputs(target, btc, tc, limits, false);
            SensitivityEvaluator head = new SensitivityEvaluator(in.withEndDate(last), cc.windowMonths());
            firstReplayable = head.firstReplayableDay(head.template().sets());
        } catch (RuntimeException e) {
            firstReplayable = null;
            in = null;
            notes.add("rejeu impossible : " + e.getMessage());
        }
        if (in == null || firstReplayable == null) {
            flags.add(ProposalFlag.HISTORIQUE_INSUFFISANT);
            notes.add("aucun jour rejouable après préchauffage");
            return new NewAssetProposalResult(asset, btc.asset(), sourceLabel, fp, months, 0, 0, List.copyOf(flags), marginK, List.of(), 0.0, 0, 0.0,
                    (System.nanoTime() - t0) / 1_000_000, List.copyOf(notes));
        }
        List<Window> windows = ExposureCalibrationStudy.windows(last, cc.windowMonths(), cc.windowStepDays(), firstReplayable);
        int blocks = 0;
        for (Block b : TuneBlocks.build(last, cfg.blockMonths(), cfg.blockCount())) {
            blocks += b.from().isBefore(firstReplayable) ? 0 : 1;
        }
        LinkSignalReplay.Config template = in.assets().getFirst().template();
        int[] set = LinkSignalReplay.setOfBar(template, LinkSignalReplay.regimes(template));
        int switches = 0;
        for (int i = 1; i < set.length; i++) {
            switches += set[i] != set[i - 1] ? 1 : 0;
        }
        if (windows.isEmpty()) {
            flags.add(ProposalFlag.MARCHE_TROP_COURT);
            notes.add("aucune fenêtre de " + cc.windowMonths() + " mois rejouable : pire cas calé sur l'historique complet seul");
        }
        double estimate = (double) cc.grid().size() * (windows.size() + 1L) * cc.parity().replayMs() / 60_000.0;
        if (estimate > cfg.budgetMinutes()) {
            flags.add(ProposalFlag.CALAGE_COUT_ELEVE);
            notes.add(String.format(java.util.Locale.ROOT, "coût estimé %.1f min (série) > budget %.0f min : calage non lancé", estimate, cfg.budgetMinutes()));
            return new NewAssetProposalResult(asset, btc.asset(), sourceLabel, fp, months, blocks, switches, List.copyOf(flags), marginK, List.of(), 0.0, 0,
                    estimate, (System.nanoTime() - t0) / 1_000_000, List.copyOf(notes));
        }
        Lite lite = ExposureCalibrationStudy.lite(in, cc, marginK, pool);
        if (lite.levels().isEmpty()) {
            flags.add(ProposalFlag.HISTORIQUE_INSUFFISANT);
            notes.add("aucune plage rejouable pour caler le pire cas");
        }
        return new NewAssetProposalResult(asset, btc.asset(), sourceLabel, fp, months, blocks, switches, List.copyOf(flags), marginK, lite.levels(),
                lite.referenceBase(), lite.replays(), estimate, (System.nanoTime() - t0) / 1_000_000, List.copyOf(notes));
    }

    private static NewAssetProposalResult empty(String asset, TransferStudy.Source btc, String label, List<MarketData> candles, Set<ProposalFlag> flags,
                                                double k, List<String> notes) {
        return new NewAssetProposalResult(asset, btc.asset(), label, null, 0, 0, 0, List.copyOf(flags), k, List.of(), 0.0, 0, 0.0, 0, List.copyOf(notes));
    }
}
