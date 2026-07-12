package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentSourceRepository extends JpaRepository<ContentSourceEntity, Long> {

    List<ContentSourceEntity> findByActiveTrue();
}
