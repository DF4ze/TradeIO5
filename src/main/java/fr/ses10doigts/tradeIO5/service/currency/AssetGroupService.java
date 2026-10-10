package fr.ses10doigts.tradeIO5.service.currency;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroupMember;
import fr.ses10doigts.tradeIO5.repository.AssetGroupMemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * Accès aux {@link fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup} : résolution symbole -> groupe, membres
 * ordonnés par préférence, somme d'une map de soldes par groupe. Valorisation nominale (1 membre = 1 unité du groupe).
 */
@Service
@RequiredArgsConstructor
public class AssetGroupService {

    private final AssetGroupMemberRepository memberRepository;

    /** Symbole d'un membre -> code de son groupe (les actifs hors groupe sont absents). */
    public Map<String, String> groupBySymbol() {
        Map<String, String> result = new HashMap<>();
        for (AssetGroupMember m : memberRepository.findAllWithAssetAndGroup()) {
            result.put(m.getAsset().getSymbol(), m.getGroup().getCode());
        }
        return result;
    }

    /** Symboles membres du groupe, du plus préféré au moins préféré ; vide si le groupe n'existe pas. */
    public List<String> members(String groupCode) {
        return memberRepository.findAllWithAssetAndGroup().stream()
                .filter(m -> m.getGroup().getCode().equals(groupCode))
                .map(m -> m.getAsset().getSymbol())
                .toList();
    }

    /** Solde de chaque membre du groupe (0 si absent de {@code balances}), dans l'ordre de préférence. */
    public Map<String, Double> byMember(String groupCode, Map<String, BigDecimal> balances) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String member : members(groupCode)) {
            result.put(member, balances.getOrDefault(member, BigDecimal.ZERO).doubleValue());
        }
        return result;
    }
}
