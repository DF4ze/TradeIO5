package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.llm.LlmCallLogEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLogEntity, Long> {

    List<LlmCallLogEntity> findByOccurredAtAfter(Instant since);

    List<LlmCallLogEntity> findByCallSiteAndOccurredAtAfter(String callSite, Instant since);

    /**
     * Agrégation tokens par callSite/tier depuis une date donnée (ex. 7 derniers jours) —
     * suffisant pour une requête de coût V1, pas de dashboard/reporting automatique.
     */
    @Query("""
            SELECT l.callSite AS callSite,
                   l.tier AS tier,
                   SUM(l.inputTokens) AS inputTokens,
                   SUM(l.outputTokens) AS outputTokens,
                   SUM(l.totalTokens) AS totalTokens,
                   COUNT(l) AS callCount
            FROM LlmCallLogEntity l
            WHERE l.occurredAt >= :since
            GROUP BY l.callSite, l.tier
            """)
    List<CallSiteUsageSummary> summarizeByCallSiteSince(@Param("since") Instant since);

    interface CallSiteUsageSummary {
        String getCallSite();
        LlmTier getTier();
        Long getInputTokens();
        Long getOutputTokens();
        Long getTotalTokens();
        Long getCallCount();
    }
}
