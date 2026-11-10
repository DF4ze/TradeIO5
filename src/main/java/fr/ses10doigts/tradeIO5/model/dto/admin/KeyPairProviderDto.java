package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.util.List;

/** Provider pour lequel le serveur sait générer une paire de clés, avec les portées disponibles et la consigne d'enregistrement. */
public record KeyPairProviderDto(String provider, List<String> scopes, String algorithm, String hint) {
}
