package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.AssetBaseRate;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * <b>Source unique</b> des taux de base par jour / cash alloué au lien, un jeu de 4 taux (Prudent, Équilibré, Dynamique, Full) par actif, table
 * {@code asset_base_rate}. Lecture : ligne de l'actif ; à défaut ligne BTC, signalée « niveau non calibré » ({@link Rate#calibrated()} faux) ;
 * BTC et ETH retombent sur leur table de calage ({@link ModulationDefaults#CALIBRATED_RATES}) si leur ligne manque. Aucun effet sur les ordres :
 * proposition indicative d'onboarding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBaseRateService {

    /** Taux retenu pour un (profil, actif) : valeur et indicateur « calibré » (faux = repli sur BTC). */
    public record Rate(BigDecimal value, boolean calibrated) {
    }

    /** Les 4 taux d'un actif, dans l'ordre Prudent → Full. */
    public record Rates(BigDecimal prudent, BigDecimal balanced, BigDecimal dynamic, BigDecimal full) {

        static Rates of(AssetBaseRate row) {
            return new Rates(row.getRatePrudent(), row.getRateBalanced(), row.getRateDynamic(), row.getRateFull());
        }

        static Rates of(ModulationDefaults.RateTable table) {
            return new Rates(table.prudent(), table.balanced(), table.dynamic(), table.full());
        }

        /** Taux du profil ; {@code null} pour un profil sans table ({@code CUSTOM}, nul). */
        public BigDecimal rate(StartingProfile profile) {
            if (profile == null || !profile.selectable()) {
                return null;
            }
            return switch (profile) {
                case PRUDENT -> prudent;
                case BALANCED -> balanced;
                case DYNAMIC -> dynamic;
                default -> full;
            };
        }

        /** Chaque taux ≥ 0 et dans les bornes absolues, et Prudent ≤ Équilibré ≤ Dynamique ≤ Full ; {@link IllegalArgumentException} sinon. */
        public void validate() {
            for (BigDecimal r : new BigDecimal[]{prudent, balanced, dynamic, full}) {
                if (r == null) {
                    throw new IllegalArgumentException("Taux manquant : les 4 profils sont obligatoires");
                }
                if (r.signum() < 0) {
                    throw new IllegalArgumentException("Taux négatif refusé : " + r.toPlainString());
                }
                if (!ModulationDefaults.baseRateInBounds(r)) {
                    throw new IllegalArgumentException("Taux hors bornes absolues [" + ModulationDefaults.BASE_RATE_MIN.toPlainString() + " ; "
                            + ModulationDefaults.BASE_RATE_MAX.toPlainString() + "] : " + r.toPlainString());
                }
            }
            if (prudent.compareTo(balanced) > 0 || balanced.compareTo(dynamic) > 0 || dynamic.compareTo(full) > 0) {
                throw new IllegalArgumentException("Taux non croissants Prudent ≤ Équilibré ≤ Dynamique ≤ Full : " + prudent.toPlainString() + " / "
                        + balanced.toPlainString() + " / " + dynamic.toPlainString() + " / " + full.toPlainString());
            }
        }
    }

    /**
     * Taux d'un actif tels que lus par l'onboarding.
     *
     * @param calibrated {@code false} = repli sur la ligne BTC (« niveau non calibré »)
     * @param dedicated  une ligne propre à l'actif existe en base
     */
    public record AssetRates(String asset, Rates rates, boolean calibrated, boolean dedicated) {
    }

    private final AssetBaseRateRepository rows;
    private final RainbowLiveBindingRepository bindings;
    private final DomainClock clock;

    /** @return {@code null} si le profil n'a pas de table ({@code CUSTOM}, nul) */
    @Transactional(readOnly = true)
    public Rate resolve(StartingProfile profile, String asset) {
        if (profile == null || !profile.selectable()) {
            return null;
        }
        AssetRates found = rates(asset);
        return new Rate(found.rates().rate(profile), found.calibrated());
    }

    /**
     * Une ligne {@code asset_base_rate} VALIDE existe-t-elle pour l'actif (taux présents, bornés, croissants) ? Critère « calibré » : sans elle, l'actif est
     * en simulation seule (« en attente de validation ») et le déclencheur D1b propose un calcul. Ne tient aucun compte du repli BTC ni de la table de calage.
     */
    @Transactional(readOnly = true)
    public boolean isCalibrated(String asset) {
        return rows.findByAssetSymbol(asset).map(row -> {
            try {
                Rates.of(row).validate();
                return true;
            } catch (IllegalArgumentException e) {
                log.warn("Ligne asset_base_rate invalide pour {} : {}", asset, e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    /** Les 4 taux de l'actif : sa ligne, sinon (BTC / ETH) sa table de calage, sinon le repli BTC non calibré. */
    @Transactional(readOnly = true)
    public AssetRates rates(String asset) {
        AssetBaseRate row = rows.findByAssetSymbol(asset).orElse(null);
        if (row != null) {
            return new AssetRates(asset, Rates.of(row), true, true);
        }
        if (ModulationDefaults.CALIBRATED_RATES.containsKey(asset)) {
            return new AssetRates(asset, Rates.of(ModulationDefaults.CALIBRATED_RATES.get(asset)), true, false);
        }
        AssetBaseRate btc = rows.findByAssetSymbol(ModulationDefaults.FALLBACK_RATE_ASSET).orElse(null);
        Rates fallback = btc != null ? Rates.of(btc)
                : Rates.of(ModulationDefaults.CALIBRATED_RATES.get(ModulationDefaults.FALLBACK_RATE_ASSET));
        return new AssetRates(asset, fallback, false, false);
    }

    /** Actifs supervisés (liens actifs) et actifs ayant une ligne, BTC et ETH toujours présents, par ordre alphabétique. */
    @Transactional(readOnly = true)
    public List<AssetRates> list() {
        TreeSet<String> assets = new TreeSet<>(ModulationDefaults.CALIBRATED_RATE_ASSETS);
        assets.addAll(bindings.findActiveAssetSymbols());
        rows.findAll().forEach(r -> assets.add(r.getAssetSymbol()));
        List<AssetRates> out = new ArrayList<>();
        assets.stream().filter(a -> a != null && !a.isBlank()).sorted(Comparator.naturalOrder()).forEach(a -> out.add(rates(a)));
        return out;
    }

    /** Écrit (crée ou remplace) la ligne de l'actif après validation ; {@code proposalId} conservé si {@code null}. */
    @Transactional
    public AssetRates save(String asset, Rates rates, Long proposalId, String by) {
        requireAsset(asset);
        if (rates == null) {
            throw new IllegalArgumentException("Taux absents");
        }
        rates.validate();
        AssetBaseRate row = rows.findByAssetSymbol(asset).orElseGet(() -> AssetBaseRate.builder().assetSymbol(asset).build());
        row.setRatePrudent(rates.prudent());
        row.setRateBalanced(rates.balanced());
        row.setRateDynamic(rates.dynamic());
        row.setRateFull(rates.full());
        if (proposalId != null) {
            row.setProposalId(proposalId);
        }
        row.setValidatedBy(by);
        row.setValidatedAt(clock.now());
        rows.save(row);
        log.info("Taux de base {} enregistrés par {}", asset, by);
        return rates(asset);
    }

    /** « Remettre les défauts » : BTC / ETH = table de calage ; autre actif = suppression de la ligne dédiée (retour au repli BTC). */
    @Transactional
    public AssetRates resetDefaults(String asset, String by) {
        requireAsset(asset);
        ModulationDefaults.RateTable table = ModulationDefaults.CALIBRATED_RATES.get(asset);
        if (table != null) {
            return save(asset, Rates.of(table), null, by);
        }
        rows.findByAssetSymbol(asset).ifPresent(rows::delete);
        log.info("Taux de base {} : ligne dédiée supprimée par {} (retour au repli {})", asset, by, ModulationDefaults.FALLBACK_RATE_ASSET);
        return rates(asset);
    }

    private static void requireAsset(String asset) {
        if (asset == null || asset.isBlank() || asset.length() > 16) {
            throw new IllegalArgumentException("Actif invalide : " + asset);
        }
    }
}
