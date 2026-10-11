package fr.ses10doigts.tradeIO5.configuration.initializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroupMember;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroupValuation;
import fr.ses10doigts.tradeIO5.repository.AssetGroupMemberRepository;
import fr.ses10doigts.tradeIO5.repository.AssetGroupRepository;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;

@DataJpaTest
@DisplayName("AssetGroupInitializer")
class AssetGroupInitializerTest {

    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private AssetProviderRepository assetProviderRepository;
    @Autowired
    private AssetGroupRepository groupRepository;
    @Autowired
    private AssetGroupMemberRepository memberRepository;

    private AssetGroupInitializer initializer;

    @BeforeEach
    void setup() {
        new AssetInitializer(assetRepository, assetProviderRepository).run();
        initializer = new AssetGroupInitializer(groupRepository, memberRepository, assetRepository);
    }

    @Test
    @DisplayName("Groupe absent => USD créé avec USDC puis USDT, valorisation nominale")
    void createsUsdGroup() {
        initializer.run();

        AssetGroup usd = groupRepository.findByCode(AssetGroup.USD).orElseThrow();
        assertEquals(AssetGroupValuation.NOMINAL, usd.getValuation());
        List<AssetGroupMember> members = memberRepository.findAllWithAssetAndGroup();
        assertEquals(2, members.size());
        assertEquals("USDC", members.getFirst().getAsset().getSymbol());
        assertEquals(0, members.getFirst().getPosition());
        assertEquals("USDT", members.getLast().getAsset().getSymbol());
        assertEquals(1, members.getLast().getPosition());
    }

    @Test
    @DisplayName("Groupe présent => intact, rien dupliqué")
    void existingGroupIsLeftIntact() {
        initializer.run();
        AssetGroup usd = groupRepository.findByCode(AssetGroup.USD).orElseThrow();
        usd.setName("Renommé à la main");
        groupRepository.saveAndFlush(usd);

        initializer.run();

        assertEquals(1, groupRepository.count());
        assertEquals(2, memberRepository.count());
        assertEquals("Renommé à la main", groupRepository.findByCode(AssetGroup.USD).orElseThrow().getName());
    }

    @Test
    @DisplayName("Un actif n'appartient qu'à un seul groupe")
    void assetBelongsToOneGroupOnly() {
        initializer.run();
        AssetGroup other = groupRepository.saveAndFlush(AssetGroup.builder().code("X").name("X")
                .valuation(AssetGroupValuation.NOMINAL).build());
        AssetGroupMember duplicate = AssetGroupMember.builder().group(other)
                .asset(assetRepository.findBySymbol("USDC").orElseThrow()).position(0).build();

        boolean rejected = false;
        try {
            memberRepository.saveAndFlush(duplicate);
        } catch (RuntimeException e) {
            rejected = true;
        }
        assertTrue(rejected);
    }
}
