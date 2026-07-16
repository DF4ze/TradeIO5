package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.CachingEtfFlowClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rafraîchissement quotidien délibéré d'ETF_FLOW (BTC + ETH), docs/etude-cache-etf-flow-historisation.md.
 * Garantit une ligne {@code etf_flow_snapshot} par asset et par jour même les jours où personne
 * n'évalue {@code ETF_FLOW}/{@code CONFIDENCE_MODULATOR} — objectif explicite de Clem (2026-07-16) :
 * une historisation continue en vue de futurs indicateurs de tendance sur le flux ETF, pas
 * seulement un cache de commodité pour les évaluations en direct (celui-ci est déjà assuré par
 * {@link CachingEtfFlowClient#fetch} indépendamment de ce job).
 * <p>
 * Passe par {@link CachingEtfFlowClient#refresh} (bypass volontaire du gate quotidien de
 * {@code fetch}) : ce job EST le point de rafraîchissement autoritaire de la journée, il ne doit
 * jamais être court-circuité par un gate pensé pour les appels utilisateur.
 * <p>
 * Créneau unique {@code 07h00} heure locale JVM (Europe/Paris en prod, même absence de {@code zone}
 * explicite que {@link MediaWatchIngestionJob}) : hypothèse <b>non vérifiée empiriquement</b>
 * (contrairement au calage MediaWatch, basé sur des horaires Cryptolyze observés en réel) que
 * SoSoValue a publié la donnée de la veille avant cette heure — à ajuster si un trou est observé en
 * pratique. Isolation par asset (même principe que MediaWatch par source) : un échec sur BTC
 * n'empêche pas la tentative ETH.
 */
@Component
@RequiredArgsConstructor
public class EtfFlowHistorizationJob {

    private static final Logger logger = LoggerFactory.getLogger(EtfFlowHistorizationJob.class);

    private final CachingEtfFlowClient cachingEtfFlowClient;
    private final IndicatorCredentialResolver credentialResolver;

    @Scheduled(cron = "${tradeio.etf-flow.historization-cron:0 0 7 * * *}")
    public void refreshDailySnapshots() {
        ApiCredentialDTO credential = credentialResolver.resolve(IndicatorType.ETF_FLOW);
        if (credential == null) {
            logger.warn("EtfFlowHistorizationJob: aucune credential SOSOVALUE résolue, exécution ignorée.");
            return;
        }

        for (EtfFlowAsset asset : EtfFlowAsset.values()) {
            try {
                EtfFlowResponse response = cachingEtfFlowClient.refresh(credential, asset);
                if (response.isValid()) {
                    logger.info("EtfFlowHistorizationJob: {} rafraîchi (date={}, total={}).",
                            asset, response.getDate(), response.getTotal());
                } else {
                    logger.warn("EtfFlowHistorizationJob: réponse invalide pour {}, snapshot non persisté ce cycle.", asset);
                }
            } catch (Exception e) {
                // Isolation par asset (même principe que MediaWatchIngestionJob#pollActiveSources) :
                // un échec sur un asset ne doit jamais empêcher la tentative sur l'autre.
                logger.error("EtfFlowHistorizationJob: échec du rafraîchissement pour {}", asset, e);
            }
        }
    }
}
