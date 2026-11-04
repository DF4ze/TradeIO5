package fr.ses10doigts.tradeIO5.service;

import fr.ses10doigts.tradeIO5.model.entity.user.ModulationChangeSource;
import fr.ses10doigts.tradeIO5.model.entity.user.ModulationValues;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.entity.user.UserModulationHistory;
import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.repository.UserModulationHistoryRepository;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Changements des réglages de modulation d'un utilisateur : point de passage unique qui valide (via
 * {@link UserTradingSettingsService}), bascule le profil et historise.
 * <ul>
 *   <li>Choisir un profil ({@link #applyProfile}) pose sa bande (puissance, biais et marge conservés).</li>
 *   <li>Modifier soi-même un paramètre ({@link #update}) bascule le profil sur {@link StartingProfile#CUSTOM}, seulement si une
 *       valeur change réellement (enregistrer sans toucher à rien ne change ni le profil ni l'historique).</li>
 * </ul>
 * Chaque changement effectif écrit une ligne d'historique append-only ({@link UserModulationHistory}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserModulationService {

    private final UserTradingSettingsService tradingSettings;
    private final UserTradingSettingsRepository settingsRepository;
    private final UserModulationHistoryRepository historyRepository;
    private final RainbowModulationControlService modulationControl;
    private final DomainClock clock;

    /** Profil retenu et réglages en vigueur après le changement. */
    public record Result(StartingProfile profile, ModulationSettings settings) {
    }

    /** Modification manuelle ; {@code regulationMarginPct} nul = inchangée. Valeur invalide : {@link IllegalArgumentException}, rien d'écrit. */
    @Transactional
    public Result update(User user, double exposureMin, double exposureMax, double power, double bias, Double regulationMarginPct) {
        ModulationSettings before = tradingSettings.getModulationSettings(user);
        StartingProfile profileBefore = profileOf(user);
        ModulationSettings saved = tradingSettings.setModulationSettings(user, exposureMin, exposureMax, power, bias, regulationMarginPct);
        if (same(before, saved)) {
            return new Result(profileBefore, saved);
        }
        setProfile(user, StartingProfile.CUSTOM);
        record(user, ModulationChangeSource.MANUAL, profileBefore, StartingProfile.CUSTOM, before, saved);
        return new Result(StartingProfile.CUSTOM, saved);
    }

    /** Choix d'un profil : pose sa bande (puissance, biais et marge conservés). {@code CUSTOM} ne se choisit pas. */
    @Transactional
    public Result applyProfile(User user, StartingProfile profile) {
        if (profile == null || !profile.selectable()) {
            throw new IllegalArgumentException("Profil obligatoire (PRUDENT, BALANCED, DYNAMIC, FULL) : Custom s'obtient en modifiant la modulation");
        }
        ModulationSettings before = tradingSettings.getModulationSettings(user);
        StartingProfile profileBefore = profileOf(user);
        ModulationSettings saved = tradingSettings.setModulationSettings(user, profile.exposureMin(), profile.exposureMax(),
                before.power(), before.bias());
        setProfile(user, profile);
        if (!same(before, saved) || profileBefore != profile) {
            record(user, ModulationChangeSource.PROFILE, profileBefore, profile, before, saved);
        }
        return new Result(profile, saved);
    }

    /** Historique des changements (du plus récent au plus ancien, 50 au plus). */
    @Transactional(readOnly = true)
    public List<UserModulationHistory> history(User user) {
        return historyRepository.findTop50ByUserOrderByOccurredAtDescIdDesc(user);
    }

    /** Profil en vigueur (nul si jamais choisi ni modifié). */
    @Transactional(readOnly = true)
    public StartingProfile profileOf(User user) {
        return settingsRepository.findByUser(user).map(UserTradingSettings::getStartingProfile).orElse(null);
    }

    private void setProfile(User user, StartingProfile profile) {
        UserTradingSettings s = settingsRepository.findByUser(user).orElseThrow();
        s.setStartingProfile(profile);
        settingsRepository.save(s);
    }

    /** Mêmes valeurs ? Une marge absente vaut le défaut admin (enregistrer ce défaut n'est pas un changement). */
    private boolean same(ModulationSettings a, ModulationSettings b) {
        return a.exposureMin() == b.exposureMin() && a.exposureMax() == b.exposureMax() && a.power() == b.power()
                && a.bias() == b.bias() && Objects.equals(margin(a), margin(b));
    }

    private Double margin(ModulationSettings s) {
        return s.regulationMarginPct() != null ? s.regulationMarginPct() : modulationControl.current().getRegulationMarginPct().doubleValue();
    }

    private ModulationValues values(ModulationSettings s) {
        return new ModulationValues(s.exposureMin(), s.exposureMax(), s.power(), s.bias(), margin(s));
    }

    private void record(User user, ModulationChangeSource source, StartingProfile profileBefore, StartingProfile profileAfter,
                        ModulationSettings before, ModulationSettings after) {
        historyRepository.save(UserModulationHistory.builder().user(user).occurredAt(clock.now()).source(source)
                .profileBefore(profileBefore).profileAfter(profileAfter).before(values(before)).after(values(after)).build());
        log.info("Modulation user={} : {} {} -> {} bande {}-{} puissance {} biais {}", user.getId(), source, profileBefore, profileAfter,
                after.exposureMin(), after.exposureMax(), after.power(), after.bias());
    }
}
