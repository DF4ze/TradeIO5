package fr.ses10doigts.tradeIO5.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroupMember;

public interface AssetGroupMemberRepository extends JpaRepository<AssetGroupMember, Long> {

    @Query("select m from AssetGroupMember m join fetch m.asset join fetch m.group order by m.group.code, m.position")
    List<AssetGroupMember> findAllWithAssetAndGroup();

    Optional<AssetGroupMember> findByAsset_Symbol(String symbol);
}
