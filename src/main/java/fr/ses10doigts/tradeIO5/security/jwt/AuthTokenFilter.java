package fr.ses10doigts.tradeIO5.security.jwt;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.UserDetailsServiceImpl;
import fr.ses10doigts.tradeIO5.security.service.impl.UserDetailsImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class AuthTokenFilter extends OncePerRequestFilter {
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private UserRepository userRepository;

    @Value("${tradeio.auth.last-login-throttle-minutes:15}")
    private long lastLoginThrottleMinutes;

    private static final Logger logger = LoggerFactory.getLogger(AuthTokenFilter.class);

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
	    @NonNull FilterChain filterChain)
	    throws ServletException, IOException {
	try {
	    String jwt = parseJwt(request);
	    if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
		String username = jwtUtils.getUserNameFromJwtToken(jwt);
		UserDetails userDetails = userDetailsService.loadUserByUsername(username);

		UsernamePasswordAuthenticationToken authentication =
			new UsernamePasswordAuthenticationToken(userDetails,
				null,
				userDetails.getAuthorities());

		authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

		SecurityContextHolder.getContext().setAuthentication(authentication);

		updateLastLoginIfStale(userDetails);
	    }
	} catch (Exception e) {
	    logger.error("Cannot set user authentication " + e.getMessage());
	}

	filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
	return jwtUtils.getJwtFromCookies(request);
    }

    private void updateLastLoginIfStale(UserDetails principal) {
	if (!(principal instanceof UserDetailsImpl userDetails)) {
	    return;
	}
	userRepository.findById(userDetails.getId()).ifPresent(u -> {
	    Instant now = Instant.now();
	    if (u.getLastLogin() == null
		    || u.getLastLogin().isBefore(now.minus(lastLoginThrottleMinutes, ChronoUnit.MINUTES))) {
		u.setLastLogin(now);
		userRepository.save(u);
	    }
	});
    }
}