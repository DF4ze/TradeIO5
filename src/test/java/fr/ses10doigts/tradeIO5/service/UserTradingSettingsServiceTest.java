package fr.ses10doigts.tradeIO5.service;

import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserTradingSettingsService")
@ExtendWith(MockitoExtension.class)
class UserTradingSettingsServiceTest {

    @Mock
    private UserTradingSettingsRepository userTradingSettingsRepository;

    private final User user = User.builder().id(1L).username("alice").build();

    @Test
    @DisplayName("getRiskCursor sans ligne persistée retourne la valeur par défaut")
    void getRiskCursor_noExistingRow_returnsDefault() {
        when(userTradingSettingsRepository.findByUser(user)).thenReturn(Optional.empty());

        UserTradingSettingsService service = new UserTradingSettingsService(userTradingSettingsRepository);

        assertEquals(UserTradingSettingsService.DEFAULT_RISK_CURSOR, service.getRiskCursor(user));
    }

    @Test
    @DisplayName("getRiskCursor avec une ligne persistée retourne la valeur stockée")
    void getRiskCursor_existingRow_returnsStoredValue() {
        when(userTradingSettingsRepository.findByUser(user))
                .thenReturn(Optional.of(UserTradingSettings.builder().user(user).riskCursor(9).build()));

        UserTradingSettingsService service = new UserTradingSettingsService(userTradingSettingsRepository);

        assertEquals(9, service.getRiskCursor(user));
    }

    @Test
    @DisplayName("setRiskCursor hors [0,10] lève IllegalArgumentException")
    void setRiskCursor_outOfRange_throws() {
        UserTradingSettingsService service = new UserTradingSettingsService(userTradingSettingsRepository);

        assertThrows(IllegalArgumentException.class, () -> service.setRiskCursor(user, -1));
        assertThrows(IllegalArgumentException.class, () -> service.setRiskCursor(user, 11));
    }

    @Test
    @DisplayName("setRiskCursor sans ligne existante en crée une nouvelle")
    void setRiskCursor_noExistingRow_createsNewRow() {
        when(userTradingSettingsRepository.findByUser(user)).thenReturn(Optional.empty());

        UserTradingSettingsService service = new UserTradingSettingsService(userTradingSettingsRepository);
        service.setRiskCursor(user, 6);

        ArgumentCaptor<UserTradingSettings> captor = ArgumentCaptor.forClass(UserTradingSettings.class);
        verify(userTradingSettingsRepository, times(1)).save(captor.capture());
        assertEquals(6, captor.getValue().getRiskCursor());
        assertEquals(user, captor.getValue().getUser());
    }

    @Test
    @DisplayName("setRiskCursor deux fois de suite met à jour la même ligne, pas de doublon")
    void setRiskCursor_calledTwice_updatesSameRow() {
        UserTradingSettings existing = UserTradingSettings.builder().id(100L).user(user).riskCursor(2).build();
        when(userTradingSettingsRepository.findByUser(user)).thenReturn(Optional.of(existing));

        UserTradingSettingsService service = new UserTradingSettingsService(userTradingSettingsRepository);
        service.setRiskCursor(user, 4);
        service.setRiskCursor(user, 8);

        ArgumentCaptor<UserTradingSettings> captor = ArgumentCaptor.forClass(UserTradingSettings.class);
        verify(userTradingSettingsRepository, times(2)).save(captor.capture());

        for (UserTradingSettings saved : captor.getAllValues()) {
            assertEquals(100L, saved.getId());
        }
        assertEquals(8, existing.getRiskCursor());
    }
}
