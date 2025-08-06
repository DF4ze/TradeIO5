package fr.ses10doigts.tradeIO5.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;

public interface AssetRepository extends JpaRepository<Asset, Long> {

}
