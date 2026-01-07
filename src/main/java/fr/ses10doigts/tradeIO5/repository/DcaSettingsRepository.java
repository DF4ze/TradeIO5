package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DcaSettingsRepository extends JpaRepository<DcaSettings, Long> {
    List<DcaSettings> findByEnabledTrue();
}