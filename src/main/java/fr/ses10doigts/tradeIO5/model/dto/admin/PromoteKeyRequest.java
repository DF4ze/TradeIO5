package fr.ses10doigts.tradeIO5.model.dto.admin;

/** Clé API obtenue chez l'exchange après enregistrement de la clé publique. {@code toString} masqué. */
public record PromoteKeyRequest(String apiKey) {

    @Override
    public String toString() {
        return "PromoteKeyRequest[***]";
    }
}
