package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.security.model.User;

/** Appelant d'un endpoint Espace Expert : utilisateur connecté en mode EXPERT et son rôle ADMIN. */
public record ExpertContext(User user, boolean admin) {

    public boolean isOwner(User owner) {
        return owner != null && owner.getId().equals(user.getId());
    }
}
