package fr.ses10doigts.tradeIO5.security.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authentification par clé d'API pour les intégrations machine-à-machine (ex : un agent qui lit
 * périodiquement l'état des Decision, cf. {@code GET /api/admin/decision/decisions}). Alternative au
 * cookie JWT ({@link fr.ses10doigts.tradeIO5.security.jwt.AuthTokenFilter}), pensée pour un client
 * sans session interactive/formulaire de login (2026-09-03, à la demande de Clem).
 * <p>
 * Désactivé par défaut : {@code tradeio.security.agent-api-key} vide/absent => le filtre ne fait
 * rien (même patron "opt-in via property" que les cron des étapes précédentes du Palier 3). Un
 * client authentifié par ce biais reçoit uniquement {@code ROLE_API_AGENT} — volontairement pas
 * {@code ROLE_ADMIN}, pour ne pas donner accès aux endpoints de déclenchement
 * (orchestrate/snapshot/archive) avec une simple clé statique dans un header. Comparaison en temps
 * constant ({@link MessageDigest#isEqual}) pour éviter une fuite de timing sur la clé. Ne fait rien
 * si une authentification est déjà présente dans le contexte (ex: cookie JWT valide) — n'écrase
 * jamais une authentification existante.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Api-Key";
    public static final String ROLE_API_AGENT = "API_AGENT";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    @Value("${tradeio.security.agent-api-key:}")
    private String configuredApiKey;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null
                && configuredApiKey != null && !configuredApiKey.isBlank()) {
            String providedKey = request.getHeader(HEADER_NAME);
            if (providedKey != null && constantTimeEquals(providedKey, configuredApiKey)) {
                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + ROLE_API_AGENT));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken("api-agent", null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("ApiKeyAuthFilter: requête authentifiée via {} (ROLE_{}).", HEADER_NAME, ROLE_API_AGENT);
            } else if (providedKey != null) {
                log.warn("ApiKeyAuthFilter: clé API invalide fournie sur {}.", request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
