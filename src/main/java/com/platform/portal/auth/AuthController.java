package com.platform.portal.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import com.platform.portal.common.CurrentUser;
import com.platform.portal.config.PortalProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final PortalProperties properties;

    public AuthController(PortalProperties properties) {
        this.properties = properties;
    }

    /** Unauthenticated bootstrap info for the login page (also issues the CSRF cookie). */
    @GetMapping("/api/public/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", properties.isMock() ? "mock" : "real");
        info.put("environment", properties.environment());
        info.put("loginUrl", properties.isMock() ? "/api/auth/login" : "/oauth2/authorization/github");
        info.put("githubOrg", properties.auth().githubOrg());
        info.put("adminTeam", properties.auth().adminTeamSlug());
        return info;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", authentication.getName());
        me.put("role", CurrentUser.role());
        me.put("admin", CurrentUser.isAdmin());
        if (authentication.getPrincipal() instanceof OAuth2User oauthUser) {
            me.put("name", oauthUser.getAttribute("name"));
            me.put("avatarUrl", oauthUser.getAttribute("avatar_url"));
            me.put("profileUrl", oauthUser.getAttribute("html_url"));
            me.put("provider", "github");
        } else {
            me.put("name", "admin".equals(authentication.getName()) ? "Demo Admin" : "Demo Reader");
            me.put("avatarUrl", null);
            me.put("provider", "local");
        }
        return me;
    }
}
