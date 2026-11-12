package fr.ses10doigts.tradeIO5.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Garde-fou : {@code ROLE_ADMIN} strict au niveau classe, aucune méthode ne l'assouplit. */
class AdminUserAccessSecurityTest {

    @Test
    void classeReserveeAdmin() {
        PreAuthorize pa = AdminUserAccessController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('ADMIN')", pa.value());
        for (Method m : AdminUserAccessController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(PreAuthorize.class), m.getName());
        }
    }
}
