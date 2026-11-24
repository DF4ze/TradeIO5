package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NewAssetProposalRepository extends JpaRepository<NewAssetProposal, Long> {

    boolean existsByAssetSymbol(String assetSymbol);

    Optional<NewAssetProposal> findFirstByAssetSymbolOrderByIdDesc(String assetSymbol);

    List<NewAssetProposal> findByStatusOrderByCreatedAtAsc(NewAssetProposalStatus status);

    List<NewAssetProposal> findAllByOrderByIdDesc();
}
