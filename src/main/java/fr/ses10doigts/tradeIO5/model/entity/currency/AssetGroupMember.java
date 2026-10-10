package fr.ses10doigts.tradeIO5.model.entity.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Appartenance d'un {@link Asset} à un {@link AssetGroup}. Un actif appartient à un seul groupe. */
@Entity
@Table(name = "asset_group_member",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_group_member_asset", columnNames = "asset_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private AssetGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    /** Ordre de préférence dans le groupe (0 = premier : membre de cotation privilégié). */
    @Column(nullable = false)
    private int position;
}
