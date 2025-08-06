package fr.ses10doigts.tradeIO5.security;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
	// @formatter:off
	http
	.cors()
	.and().csrf().disable()
	.exceptionHandling().authenticationEntryPoint(unauthorizedHandler).accessDeniedPage("/unauthorized.html")
	.and().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
	.and().authorizeHttpRequests()
	.requestMatchers("/api/auth/**").permitAll()
	//.requestMatchers("/api/test/**").permitAll()
	.requestMatchers("/**").permitAll()
	.anyRequest().authenticated()
	;
	// @formatter:on

	http.authenticationProvider(authenticationProvider());

	http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

	return http.build();
    }
}