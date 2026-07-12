package fr.ses10doigts.tradeIO5.model.entity.llm;

import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Log append-only d'un appel LLM effectivement réalisé (usage tokens réel remonté par le
 * fournisseur), pour permettre de mesurer le coût par cas d'appel a posteriori.
 * <p>
 * Volontairement pas de coût en euros/dollars stocké ici : les tarifs évoluent dans le temps
 * et un coût figé invaliderait l'historique déjà écrit. Le coût se calcule à la lecture via
 * {@link fr.ses10doigts.tradeIO5.service.connector.LlmCostCalculator}, à partir du tarif courant.
 */
@Data
@Entity
@Table(name = "llm_call_logs")
public class LlmCallLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identifiant libre du site d'appel, ex. "media-watch:classification", "opinion:openai-advisor". */
    private String callSite;

    @Enumerated(EnumType.STRING)
    private LlmTier tier;

    /** Nom du modèle concret résolu (ex. {@code OpenAIModel.name()}), pas le nom d'API du fournisseur. */
    private String model;

    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;

    private Instant occurredAt;
}
