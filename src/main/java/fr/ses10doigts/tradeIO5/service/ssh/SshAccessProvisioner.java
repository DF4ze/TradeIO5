package fr.ses10doigts.tradeIO5.service.ssh;

import java.util.List;

/**
 * Accès VPS (tunnel SSH) des utilisateurs TradeIO. Seule interface vers le helper root {@code tradeio-user}.
 * Sémantique commune aux implémentations : {@code add} sur compte existant => CONFLICT ; {@code disable}/{@code enable}
 * sur compte inconnu => UNKNOWN ; {@code del} sur compte inconnu => no-op (idempotent).
 */
public interface SshAccessProvisioner {

    /** Compte tunnel présent sur le VPS ; {@code active} = jamais expiré ({@code expiration == never}). */
    record Account(String login, String expiration, boolean active) {
    }

    void add(String login, String publicKey);

    void disable(String login);

    void enable(String login);

    void del(String login);

    List<Account> list();
}
