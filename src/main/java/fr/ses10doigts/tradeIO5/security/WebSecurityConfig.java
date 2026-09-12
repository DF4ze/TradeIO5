package fr.ses10doigts.tradeIO5.security;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import fr.ses10doigts.tradeIO5.security.apikey.ApiKeyAuthFilter;
import fr.ses10doigts.tradeIO5.security.jwt.AuthEntryPointJwt;
import fr.ses10doigts.tradeIO5.security.jwt.AuthTokenFilter;
import fr.ses10doigts.tradeIO5.security.service.UserDetailsServiceImpl;

@Configuration
@EnableMethodSecurity(
	// securedEnabled = true,
	// jsr250Enabled = true,
	prePostEnabled = true)
public class WebSecurityConfig {
    @Autowired
    UserDetailsServiceImpl    userDetailsService;

    @Autowired
    private AuthEntryPointJwt unauthorizedHandler;

    @Bean
    AuthTokenFilter authenticationJwtTokenFilter() {
	return new AuthTokenFilter();
    }

    @Bean
    ApiKeyAuthFilter apiKeyAuthFilter() {
	return new ApiKeyAuthFilter();
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider() {
	DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();

	authProvider.setUserDetailsService(userDetailsService);
	authProvider.setPasswordEncoder(passwordEncoder());

	return authProvider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
	return authConfig.getAuthenticationManager();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
	return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

	http
	    .cors(Customizer.withDefaults())
	    .csrf(csrf -> csrf.disable())
	    .exceptionHandling(exception -> exception
		.authenticationEntryPoint(unauthorizedHandler)
		.accessDeniedPage("/unauthorized.html")
	    )
	    .sessionManagement(session -> session
		.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
	    )
	    .authorizeHttpRequests(auth -> auth
		.requestMatchers("/api/auth/**").permitAll()
		//.requestMatchers("/api/test/**").permitAll()
		.requestMatchers("/**").permitAll()
		.anyRequest().authenticated()
	    );

	http.authenticationProvider(authenticationProvider());

	http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);
	http.addFilterBefore(apiKeyAuthFilter(), UsernamePasswordAuthenticationFilter.class);

	return http.build();
    }
}