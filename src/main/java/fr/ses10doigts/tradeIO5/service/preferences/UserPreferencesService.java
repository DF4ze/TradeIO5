package fr.ses10doigts.tradeIO5.service.preferences;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.entity.user.UiTheme;
import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.model.enumerate.AlertType;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
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
 * personnels ne font que <b>resserrer</b> les plafonds absolus ({@link ExecutionDefaults}) : jamais au-dessus, jamais ≤ 0.
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    /** Part du plafond absolu à partir de laquelle un plafond personnel déclenche un avertissement. */
    static final double WARN_RATIO = 0.8;

    private final UserTradingSettingsRepository repository;
    private final UserTradingSettingsService tradingSettings;

    /** Plafonds personnels en vigueur (valeurs nulles = pas de plafond personnel). */
    public record UserCaps(BigDecimal maxOrderUsd, BigDecimal maxDayUsd) {
    }

    public record Preferences(UiTheme theme, StartingProfile startingProfile, Set<AlertType> alertsDisabled, Caps caps,
                              List<String> warnings) {
    }

    public record Caps(Double maxOrderUsd, Double maxDayUsd, double absoluteMaxOrderUsd, double absoluteMaxDayUsd) {
    }

    public record ProfileResult(StartingProfile profile, ModulationSettings modulation) {
    }

    @Transactional(readOnly = true)
    public Preferences get(User user) {
        return repository.findByUser(user).map(UserPreferencesService::toPreferences).orElseGet(() -> toPreferences(null));
    }

    /** Remplace thème, alertes coupées et plafonds ; {@link IllegalArgumentException} (rien écrit) si un plafond est invalide. */
    @Transactional
    public Preferences update(User user, UiTheme theme, Set<AlertType> alertsDisabled, Double maxOrderUsd, Double maxDayUsd) {
        requireCap("par ordre", maxOrderUsd, ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD);
        requireCap("par jour", maxDayUsd, ExecutionDefaults.ABSOLUTE_MAX_DAY_USD);
        UserTradingSettings s = settingsOf(user);
        s.setTheme(theme);
        s.setAlertsDisabled(alertsDisabled == null || alertsDisabled.isEmpty() ? null
                : alertsDisabled.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        s.setMaxOrderUsd(maxOrderUsd);
        s.setMaxDayUsd(maxDayUsd);
        return toPreferences(repository.save(s));
    }

    /**
     * Choisit le profil de départ et pose sa bande d'exposition (puissance et biais conservés). Bande refusée par les limites
     * admin ⇒ {@link IllegalArgumentException}, rien n'est écrit.
     */
    @Transactional
    public ProfileResult applyProfile(User user, StartingProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Profil obligatoire (PRUDENT, BALANCED, DYNAMIC)");
        }
        ModulationSettings current = tradingSettings.getModulationSettings(user);
        ModulationSettings saved = tradingSettings.setModulationSettings(user, profile.exposureMin(), profile.exposureMax(),
                current.power(), current.bias());
        UserTradingSettings s = settingsOf(user);
        s.setStartingProfile(profile);
        repository.save(s);
        return new ProfileResult(profile, saved);
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

    private static Preferences toPreferences(UserTradingSettings s) {
        Double order = s == null ? null : s.getMaxOrderUsd();
        Double day = s == null ? null : s.getMaxDayUsd();
        List<String> warnings = new ArrayList<>();
        warn(warnings, "par ordre", order, ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD);
        warn(warnings, "par jour", day, ExecutionDefaults.ABSOLUTE_MAX_DAY_USD);
        return new Preferences(s == null || s.getTheme() == null ? UiTheme.DARK : s.getTheme(),
                s == null ? null : s.getStartingProfile(), s == null ? EnumSet.noneOf(AlertType.class) : parse(s.getAlertsDisabled()),
                new Caps(order, day, ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD.doubleValue(),
                        ExecutionDefaults.ABSOLUTE_MAX_DAY_USD.doubleValue()), warnings);
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
