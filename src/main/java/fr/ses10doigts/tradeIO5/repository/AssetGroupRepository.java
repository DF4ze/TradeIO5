package fr.ses10doigts.tradeIO5.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;

public interface AssetGroupRepository extends JpaRepository<AssetGroup, Long> {

    Optional<AssetGroup> findByCode(String code);
}
