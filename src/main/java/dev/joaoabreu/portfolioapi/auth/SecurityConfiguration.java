package dev.joaoabreu.portfolioapi.auth;

import dev.joaoabreu.portfolioapi.assistant.AssistantRequestFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    ClientRegistrationRepository githubClients(
            @Value("${portfolio.github.client-id}") String clientId,
            @Value("${portfolio.github.client-secret}") String clientSecret,
            @Value("${portfolio.github.redirect-uri}") String redirectUri) {
        ClientRegistration github = ClientRegistration.withRegistrationId("github")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .clientName("GitHub")
                .build();
        return new InMemoryClientRegistrationRepository(github);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ClientRegistrationRepository clients, GitHubAdminUserService adminUsers) throws Exception {
        var authorizationRequests = new DefaultOAuth2AuthorizationRequestResolver(
                clients, "/oauth2/authorization");
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http
                .csrf(csrf -> csrf.ignoringRequestMatchers(AssistantRequestFilter::matches))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(AssistantRequestFilter::matches).permitAll()
                        .requestMatchers("/admin", "/api/auth/csrf", "/actuator/health",
                                "/oauth2/authorization/github", "/login/oauth2/code/github").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/profile-card.svg", "/api/projects", "/api/visits", "/api/recommendations", "/api/recommendation-images/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/contact", "/api/visits").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                        .userInfoEndpoint(endpoint -> endpoint.userService(adminUsers))
                        .defaultSuccessUrl("/admin", true)
                        .failureHandler((request, response, exception) -> {
                            var session = request.getSession(false);
                            if (session != null) session.invalidate();
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)));
        return http.build();
    }
}
