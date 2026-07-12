package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.media.MediaClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MediaClaimRepository extends JpaRepository<MediaClaimEntity, Long> {

    /**
     * Cutoff = borne large de requête (ex. 400 jours, cf. {@code MediaMarketOpinion}, Lot 3),
     * pas un mécanisme de fraîcheur — la décroissance par âge/horizon se calcule en mémoire côté
     * MediaMarketOpinion, pas en SQL (docs/etude-veille-media-youtube.md §5).
     */
    List<MediaClaimEntity> findBySymbolAndVideoContent_PublishedAtAfter(String symbol, Instant cutoff);
}
