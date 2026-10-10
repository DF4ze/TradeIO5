package fr.ses10doigts.tradeIO5.configuration.initializer;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroupMember;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroupValuation;
import fr.ses10doigts.tradeIO5.repository.AssetGroupMemberRepository;
import fr.ses10doigts.tradeIO5.repository.AssetGroupRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seed des {@link AssetGroup} (après {@link AssetInitializer}). Idempotent, élément par élément : un groupe ou un membre
 * absent est créé, un existant n'est jamais modifié. Seul groupe : USD = USDC (position 0) + USDT (position 1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
public class AssetGroupInitializer implements CommandLineRunner {

    private record GroupSeed(String code, String name, AssetGroupValuation valuation, List<String> memberSymbols) {
    }

    private static final List<GroupSeed> GROUP_SEEDS = List.of(
            new GroupSeed(AssetGroup.USD, "Dollar (stablecoins)", AssetGroupValuation.NOMINAL, List.of("USDC", "USDT")));

    private final AssetGroupRepository groupRepository;
    private final AssetGroupMemberRepository memberRepository;
    private final AssetRepository assetRepository;

    @Override
    public void run(String... args) {
        for (GroupSeed seed : GROUP_SEEDS) {
            AssetGroup group = groupRepository.findByCode(seed.code()).orElseGet(() -> {
                log.info("Groupe d'actifs créé : {}", seed.code());
                return groupRepository.save(AssetGroup.builder().code(seed.code()).name(seed.name())
                        .valuation(seed.valuation()).build());
            });
            for (int position = 0; position < seed.memberSymbols().size(); position++) {
                seedMember(group, seed.memberSymbols().get(position), position);
            }
        }
    }

    private void seedMember(AssetGroup group, String symbol, int position) {
        if (memberRepository.findByAsset_Symbol(symbol).isPresent()) {
            return;
        }
        Optional<Asset> asset = assetRepository.findBySymbol(symbol);
        if (asset.isEmpty()) {
            log.warn("Groupe {} : Asset '{}' absent, membre ignoré", group.getCode(), symbol);
            return;
        }
        memberRepository.save(AssetGroupMember.builder().group(group).asset(asset.get()).position(position).build());
        log.info("Membre de groupe créé : {} -> {}", symbol, group.getCode());
    }
}
