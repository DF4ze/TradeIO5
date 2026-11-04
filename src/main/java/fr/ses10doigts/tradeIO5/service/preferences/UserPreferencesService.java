package fr.ses10doigts.tradeIO5.service.preferences;

import fr.ses10doigts.tradeIO5.model.entity.user.DisplayMode;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.entity.user.UiTheme;
import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.model.enumerate.AlertType;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserModulationService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Préférences d'un utilisateur : thème, profil de départ, alertes coupées et plafonds de dépense personnels. Les plafonds
 * personnels ne font que <b>resserrer</b> les plafonds absolus ({@code ExecutionControl}) : jamais au-dessus, jamais ≤ 0.
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    /** Part du plafond absolu à partir de laquelle un plafond personnel déclenche un avertissement. */
    static final double WARN_RATIO = 0.8;

    private final UserTradingSettingsRepository repository;
    private final UserModulationService modulation;
    private final ExecutionControlService control;

    /** Plafonds personnels en vigueur (valeurs nulles = pas de plafond personnel). */
    public record UserCaps(BigDecimal maxOrderUsd, BigDecimal maxDayUsd) {
    }

    public record Preferences(UiTheme theme, DisplayMode displayMode, StartingProfile startingProfile, Set<AlertType> alertsDisabled, Caps caps,
                              List<String> warnings) {
    }

    public record Caps(Double maxOrderUsd, Double maxDayUsd, double absoluteMaxOrderUsd, double absoluteMaxDayUsd) {
    }

    public record ProfileResult(StartingProfile profile, ModulationSettings modulation) {
    }

    @Transactional(readOnly = true)
    public Preferences get(User user) {
        return repository.findByUser(user).map(s -> toPreferences(s, control.current())).orElseGet(() -> toPreferences(null, control.current()));
    }

    /** Remplace thème, mode d'affichage, alertes coupées et plafonds ; {@link IllegalArgumentException} (rien écrit) si un plafond est invalide. */
    @Transactional
    public Preferences update(User user, UiTheme theme, DisplayMode displayMode, Set<AlertType> alertsDisabled, Double maxOrderUsd,
                              Double maxDayUsd) {
        ExecutionControl ctl = control.current();
        requireCap("par ordre", maxOrderUsd, ExecutionControlService.absoluteMaxOrderUsd(ctl));
        requireCap("par jour", maxDayUsd, ExecutionControlService.absoluteMaxDayUsd(ctl));
        UserTradingSettings s = settingsOf(user);
        s.setTheme(theme);
        s.setDisplayMode(displayMode);
        s.setAlertsDisabled(alertsDisabled == null || alertsDisabled.isEmpty() ? null
                : alertsDisabled.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        s.setMaxOrderUsd(maxOrderUsd);
        s.setMaxDayUsd(maxDayUsd);
        return toPreferences(repository.save(s), control.current());
    }

    /**
     * Choisit le profil de départ et pose sa bande d'exposition (puissance et biais conservés). Bande refusée par les limites
     * admin ⇒ {@link IllegalArgumentException}, rien n'est écrit.
     */
    @Transactional
    public ProfileResult applyProfile(User user, StartingProfile profile) {
        UserModulationService.Result result = modulation.applyProfile(user, profile);
        return new ProfileResult(result.profile(), result.settings());
    }

    /** Plafonds personnels pour l'exécution ; jamais {@code null}. */
    @Transactional(readOnly = true)
    public UserCaps caps(User user) {
        return repository.findByUser(user)
                .map(s -> new UserCaps(decimal(s.getMaxOrderUsd()), decimal(s.getMaxDayUsd())))
                .orElseGet(() -> new UserCaps(null, null));
    }

    /** Types d'alertes que l'utilisateur a coupés. */
    @Transactional(readOnly = true)
    public Set<AlertType> alertsDisabled(User user) {
        return repository.findByUser(user).map(s -> parse(s.getAlertsDisabled())).orElseGet(() -> EnumSet.noneOf(AlertType.class));
    }

    private UserTradingSettings settingsOf(User user) {
        return repository.findByUser(user).orElseGet(() -> UserTradingSettings.builder().user(user)
                .riskCursor(UserTradingSettingsService.DEFAULT_RISK_CURSOR).build());
    }

    private static Preferences toPreferences(UserTradingSettings s, ExecutionControl ctl) {
        BigDecimal absOrder = ExecutionControlService.absoluteMaxOrderUsd(ctl);
        BigDecimal absDay = ExecutionControlService.absoluteMaxDayUsd(ctl);
        Double order = s == null ? null : s.getMaxOrderUsd();
        Double day = s == null ? null : s.getMaxDayUsd();
        List<String> warnings = new ArrayList<>();
        warn(warnings, "par ordre", order, absOrder);
        warn(warnings, "par jour", day, absDay);
        return new Preferences(s == null || s.getTheme() == null ? UiTheme.DARK : s.getTheme(),
                s == null || s.getDisplayMode() == null ? DisplayMode.SIMPLE : s.getDisplayMode(), s == null ? null : s.getStartingProfile(), s == null ? EnumSet.noneOf(AlertType.class) : parse(s.getAlertsDisabled()),
                new Caps(order, day, absOrder.doubleValue(), absDay.doubleValue()), warnings);
    }

    private static void warn(List<String> warnings, String label, Double cap, BigDecimal absolute) {
        if (cap != null && cap >= absolute.doubleValue() * WARN_RATIO) {
            warnings.add("Plafond " + label + " proche du plafond absolu (" + absolute.toPlainString() + " USD)");
        }
    }

    private static void requireCap(String label, Double cap, BigDecimal absolute) {
        if (cap == null) {
            return;
        }
        if (!Double.isFinite(cap) || cap <= 0 || cap > absolute.doubleValue()) {
            throw new IllegalArgumentException("Plafond " + label + " invalide : " + cap + " (entre 0 exclu et "
                    + absolute.toPlainString() + " USD)");
        }
    }

    private static BigDecimal decimal(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    private static Set<AlertType> parse(String csv) {
        Set<AlertType> out = EnumSet.noneOf(AlertType.class);
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(String::trim).forEach(n -> out.add(AlertType.valueOf(n)));
        }
        return out;
    }
}
