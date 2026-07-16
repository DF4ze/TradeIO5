package fr.ses10doigts.tradeIO5.repository.market;

import fr.ses10doigts.tradeIO5.model.entity.market.EtfFlowSnapshotEntity;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Accès à l'historique ETF_FLOW persisté par
 * {@link fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.CachingEtfFlowClient}.
 * <p>
 * {@link #findByAssetOrderByDateAsc} n'a aucun consommateur aujourd'hui — exposée par anticipation
 * pour un futur indicateur de tendance sur le flux ETF (demande explicite de Clem, cf. javadoc
 * {@link EtfFlowSnapshotEntity}), l'index de la contrainte unique {@code uk_etf_flow_snapshot_asset_date}
 * la sert nativement (préfixe gauche en égalité sur {@code asset}).
 */
public interface EtfFlowSnapshotRepository extends JpaRepository<EtfFlowSnapshotEntity, Long> {

    Optional<EtfFlowSnapshotEntity> findTopByAssetOrderByDateDesc(EtfFlowAsset asset);

    Optional<EtfFlowSnapshotEntity> findByAssetAndDate(EtfFlowAsset asset, LocalDate date);

    List<EtfFlowSnapshotEntity> findByAssetOrderByDateAsc(EtfFlowAsset asset);
}
