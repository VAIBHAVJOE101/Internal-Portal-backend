package com.platform.portal.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.platform.portal.auth.GithubOAuth2UserService;
import com.platform.portal.settings.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Backend-for-frontend security: session cookie auth, cookie based CSRF token readable by the SPA,
 * GitHub OAuth login in real mode and local demo users in mock mode.
 * READER may call GET endpoints; every mutating endpoint requires ADMIN.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, PortalProperties properties,
                                                   SettingsService settingsService) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // resolve token eagerly so the cookie is always issued

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/public/**", "/api/auth/**", "/actuator/health/**", "/actuator/info", "/error",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/login/**", "/oauth2/**").permitAll()
                        .requestMatchers("/api/prefs/**", "/api/me").hasAnyRole("READER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("READER", "ADMIN")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout"))
                        .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value()))
                        .deleteCookies("PORTAL_SESSION"));

        String frontend = properties.frontendUrl();
        if (properties.isMock()) {
            http.formLogin(form -> form
                    .loginProcessingUrl("/api/auth/login")
                    .successHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value()))
                    .failureHandler((req, res, e) -> res.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid credentials")));
        } else {
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(u -> u.userService(new GithubOAuth2UserService(properties, settingsService)))
                    .defaultSuccessUrl(frontend, true)
                    .failureHandler((req, res, e) -> res.sendRedirect(frontend + (frontend.endsWith("/") ? "" : "/")
                            + "login?error=" + URLEncoder.encode(e.getMessage() == null ? "Login failed" : e.getMessage(), StandardCharsets.UTF_8))));
        }
        return http.build();
    }

    /** Persist GitHub OAuth tokens in the database so any replica can call GitHub on the user's behalf. */
    @Bean
    @ConditionalOnProperty(name = "portal.mode", havingValue = "real", matchIfMissing = true)
    public OAuth2AuthorizedClientService authorizedClientService(JdbcTemplate jdbcTemplate, ClientRegistrationRepository registrations) {
        return new JdbcOAuth2AuthorizedClientService(jdbcTemplate, registrations);
    }

    /** Demo users for mock mode only. */
    @Bean
    @ConditionalOnProperty(name = "portal.mode", havingValue = "mock")
    public UserDetailsService mockUsers() {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password("{noop}admin").roles("ADMIN").build(),
                User.withUsername("reader").password("{noop}reader").roles("READER").build());
    }

    /** Forces the deferred CSRF token to load so the XSRF-TOKEN cookie is written on every response. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
