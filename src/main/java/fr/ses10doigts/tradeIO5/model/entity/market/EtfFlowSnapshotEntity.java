package fr.ses10doigts.tradeIO5.model.entity.market;

import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Historisation du flux ETF quotidien (BTC/ETH, SoSoValue), une ligne par {@code (asset, date)} —
 * cf. docs/etude-cache-etf-flow-historisation.md. Demande explicite de Clem (2026-07-16) : au-delà
 * du cache de commodité (éviter un appel réseau par évaluation), constituer une vraie série
 * temporelle exploitable plus tard pour des indicateurs de tendance sur le flux ETF.
 * <p>
 * Suffixe {@code Entity} et absence de {@code User}/{@code Wallet}, même convention que
 * {@link CandleEntity} (donnée publique partagée, pas de rattachement utilisateur).
 * <p>
 * {@code fetchedAt} (distinct de {@code date}, qui est la date de la donnée telle que publiée par
 * SoSoValue) sert de gate au cache-aside de
 * {@link fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.CachingEtfFlowClient#fetch} :
 * "déjà rafraîchi aujourd'hui" se lit sur {@code fetchedAt}, pas sur {@code date}, pour ne pas
 * dépendre d'une logique de calendrier de marché côté client (cf. javadoc de cette classe).
 */
@Entity
@Table(name = "etf_flow_snapshot",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_etf_flow_snapshot_asset_date",
           columnNames = {"asset", "date"}
       ))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtfFlowSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EtfFlowAsset asset;

    /** Date de la donnée telle que publiée par SoSoValue (champ {@code date} de la réponse API),
     *  pas la date de l'appel réseau — voir {@code fetchedAt}. */
    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "total_net_inflow", nullable = false)
    private Double totalNetInflow;

    /** Date/heure de l'appel réseau qui a produit cette ligne (création ou dernier upsert) — sert
     *  au gate quotidien du cache-aside, cf. javadoc de classe. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
