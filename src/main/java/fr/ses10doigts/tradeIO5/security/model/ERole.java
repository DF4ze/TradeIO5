package fr.ses10doigts.tradeIO5.security.model;

import lombok.AllArgsConstructor;
import lombok.Getter;


@AllArgsConstructor
public enum ERole {

    ROLE_USER("User"), ROLE_MODERATOR("Moderator"), ROLE_ADMIN("Administrator"), ROLE_SYS("System");

    @Getter
    private final String pretty;


}