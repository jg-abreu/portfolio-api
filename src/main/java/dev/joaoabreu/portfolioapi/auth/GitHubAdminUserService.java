package dev.joaoabreu.portfolioapi.auth;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class GitHubAdminUserService extends DefaultOAuth2UserService {

    private final String adminId;

    public GitHubAdminUserService(@Value("${portfolio.admin.github-id}") long adminId) {
        if (adminId <= 0) throw new IllegalArgumentException("Configure a positive GitHub admin ID");
        this.adminId = Long.toString(adminId);
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        if (!"github".equals(request.getClientRegistration().getRegistrationId())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        }
        OAuth2User user = super.loadUser(request);
        if (!adminId.equals(String.valueOf(user.getAttributes().get("id")))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        }
        return new DefaultOAuth2User(Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                user.getAttributes(), "id");
    }
}