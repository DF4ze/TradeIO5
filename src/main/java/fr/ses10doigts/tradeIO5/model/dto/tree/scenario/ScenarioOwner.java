package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import java.util.Optional;

public sealed interface ScenarioOwner
        permits ScenarioOwner.SystemOwner, ScenarioOwner.UserOwner {

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

        @Override
        public Optional<Long> asUserId() {
            return Optional.empty();
        }
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