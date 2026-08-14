package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Optional;

/**
 * Palier 3, étape 4 (correctif découvert en préparant cette étape, hors périmètre initial du
 * prompt mais bloquant pour lui) : {@code @JsonTypeInfo}/{@code @JsonSubTypes} ajoutés ici — sans
 * eux, Jackson ne peut pas désérialiser un champ typé {@code ScenarioOwner} (interface scellée,
 * aucune info de type dans le JSON par défaut), ce qui cassait silencieusement (catch+log, jamais
 * remonté) TOUTE désérialisation de {@code ScenarioEvent}/{@code DecisionEvent} via {@code
 * JpaEventStore.toDomain()} — pas seulement le cas DECISION ajouté par ce lot. Découvert en écrivant
 * {@code JpaEventStoreTest} (régression demandée par le prompt), signalé en détail dans le rapport
 * final.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ScenarioOwner.SystemOwner.class, name = "SYSTEM"),
        @JsonSubTypes.Type(value = ScenarioOwner.UserOwner.class, name = "USER")
})
public sealed interface ScenarioOwner
        permits ScenarioOwner.SystemOwner, ScenarioOwner.UserOwner {

    /**
     * {@code @JsonIgnore} (Palier 3, étape 4, même correctif que le reste de cette classe) :
     * sans lui, Jackson introspecte ce getter en plus des composants du record (ex. {@code userId}
     * pour {@link UserOwner}) et sérialise une propriété {@code "id"} en trop, que la
     * désérialisation par constructeur canonique rejette ensuite ("Unrecognized field").
     */
    @JsonIgnore
    String getId();

    static ScenarioOwner fromString( String userId ){
        if("SYSTEM".equals(userId)){
            return SYSTEM;

        }else if( userId != null ){
            return user( userId );

        }

        return null;
    }

    /**
     * Point de conversion canonique {@code User} (JPA, {@code Long id}) -> {@code ScenarioOwner}.
     * Palier 2 (2026-08) : avant cette méthode, chaque appelant devait convertir "à la main"
     * (ex. {@code ScenarioOwner.user(String.valueOf(user.getId()))}), ce qui aurait fini par
     * diverger. À utiliser partout où un {@code User} authentifié doit devenir un owner de
     * scénario/décision.
     */
    static ScenarioOwner of(fr.ses10doigts.tradeIO5.security.model.User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User must be persisted (non-null id) to become a ScenarioOwner");
        }
        return user(String.valueOf(user.getId()));
    }

    record SystemOwner() implements ScenarioOwner {
        @Override
        public String getId() {
            return "SYSTEM";
        }
        // asUserId() : pas d'override, la valeur par défaut de l'interface (Optional.empty()) convient déjà.
    }
    record UserOwner(String userId) implements ScenarioOwner {
        @Override
        public String getId() {
            return userId;
        }

        @Override
        public Optional<Long> asUserId() {
            return Optional.of(Long.valueOf(userId));
        }
    }

    ScenarioOwner SYSTEM = new SystemOwner();
    static ScenarioOwner user(String userId) {
        return new UserOwner(userId);
    }

    default boolean isVisible( ScenarioOwner other ){
        return other.getId().equals(getId()) || other.getId().equals("SYSTEM");
    }

    /**
     * Sens inverse de {@link #of(fr.ses10doigts.tradeIO5.security.model.User)} : permet de
     * recharger le {@code User.id} réel depuis un {@code ScenarioOwner}, typiquement pour requêter
     * {@code UserRepository}. Vide pour {@link SystemOwner} (pas de {@code User} associé), présent
     * pour {@link UserOwner}.
     */
    default Optional<Long> asUserId() {
        return Optional.empty();
    }
}