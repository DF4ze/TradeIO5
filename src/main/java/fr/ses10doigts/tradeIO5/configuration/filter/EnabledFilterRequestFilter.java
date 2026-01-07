package fr.ses10doigts.tradeIO5.configuration.filter;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class EnabledFilterRequestFilter extends OncePerRequestFilter {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // ⚠ Si aucun user connecté → on filtre sur enabled=true
        boolean isAdmin = false;
        if (authentication != null && authentication.isAuthenticated()) {
            isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
        }

        Session session = entityManager.unwrap(Session.class);
        if (!isAdmin) {
            // 🔹 Active le filtre uniquement pour les non-admins
            Filter filter = session.enableFilter("enabledFilter");
            filter.setParameter("isEnabled", true);
        } else {
            // 🔹 Désactive le filtre pour Admins
            session.disableFilter("enabledFilter");
        }

        filterChain.doFilter(request, response);
    }
}