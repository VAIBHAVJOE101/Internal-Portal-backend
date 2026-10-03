package com.platform.portal.auth;

import java.util.List;
import java.util.Map;

import com.platform.portal.common.Strings;
import com.platform.portal.config.PortalProperties;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;

/**
 * Maps a GitHub login onto portal roles:
 * <ul>
 *   <li>active member of the configured admin team (default {@code devops_team}) -> ADMIN</li>
 *   <li>any other active member of the organization -> READER</li>
 *   <li>not an org member -> login rejected</li>
 * </ul>
 */
public class GithubOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(GithubOAuth2UserService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final PortalProperties properties;
    private final SettingsService settings;

    public GithubOAuth2UserService(PortalProperties properties, SettingsService settings) {
        this.properties = properties;
        this.settings = settings;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        String login = user.getAttribute("login");
        Map<String, String> github = settings.resolve(SettingType.GITHUB);
        String org = !Strings.isBlank(properties.auth().githubOrg()) ? properties.auth().githubOrg() : github.get("org");
        if (Strings.isBlank(org)) {
            throw denied("portal_misconfigured", "GitHub organization is not configured (GITHUB_ORG)");
        }
        String apiUrl = github.getOrDefault("apiUrl", "https://api.github.com");
        RestClient client = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + request.getAccessToken().getTokenValue())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();

        if (!isActive(client, "/user/memberships/orgs/{org}", org)) {
            log.info("Rejected login for {}: not an active member of {}", login, org);
            throw denied("not_org_member", "You must be a member of the " + org + " GitHub organization");
        }
        String role = isActive(client, "/orgs/{org}/teams/{team}/memberships/{login}", org, properties.auth().adminTeamSlug(), login)
                ? "ADMIN" : "READER";
        log.info("GitHub login {} granted role {}", login, role);
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_" + role)), user.getAttributes(), "login");
    }

    private static boolean isActive(RestClient client, String uri, Object... vars) {
        Map<String, Object> body = client.get().uri(uri, vars)
                .exchange((req, res) -> {
                    HttpStatusCode status = res.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return res.bodyTo(MAP);
                    }
                    return null;
                });
        return body != null && "active".equals(body.get("state"));
    }

    private static OAuth2AuthenticationException denied(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, message, null), message);
    }
}
