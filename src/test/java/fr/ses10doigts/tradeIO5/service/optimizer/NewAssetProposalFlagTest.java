package fr.ses10doigts.tradeIO5.service.optimizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drapeau D1b : actif par défaut (code + application.properties) ; désactivé dans le modèle de recette et dans les propriétés de test. */
@DisplayName("Drapeau tradeio.new-asset-proposal.enabled : vrai par défaut, coupé en test et en recette")
class NewAssetProposalFlagTest {

    private static final String TRUE_LINE = NewAssetProposalService.ENABLED_PROPERTY + "=true";
    private static final String FALSE_LINE = NewAssetProposalService.ENABLED_PROPERTY + "=false";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @Test
    @DisplayName("Code : matchIfMissing = true (sans propriété, le pipeline est actif)")
    void defaultInCode() {
        ConditionalOnProperty condition = NewAssetProposalService.class.getAnnotation(ConditionalOnProperty.class);
        assertTrue(condition.matchIfMissing());
        assertEquals("true", condition.havingValue());
        assertEquals(NewAssetProposalService.ENABLED_PROPERTY, condition.name()[0]);
    }

    @Test
    @DisplayName("application.properties : true ; modèle de recette et modèle de test : false")
    void properties() throws IOException {
        assertTrue(read("src/main/resources/application.properties").contains(TRUE_LINE));
        assertTrue(read("src/main/resources/application-recette.properties.template").contains(FALSE_LINE));
        assertTrue(read("src/test/resources/template-application.properties").contains(FALSE_LINE));
    }
}
