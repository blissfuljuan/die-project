package com.blissfuljuan.dieapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Session cookie attributes, set explicitly from the server.servlet.session.cookie.* properties so they hold
 * regardless of how the session auto-configuration maps them (HttpOnly, Secure and SameSite are security controls).
 */
@Configuration
public class SessionCookieConfig {

    @Bean
    CookieSerializer cookieSerializer(
        @Value("${server.servlet.session.cookie.http-only:true}") boolean httpOnly,
        @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
        @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(httpOnly);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(sameSite.isEmpty() ? null : sameSite.substring(0, 1).toUpperCase() + sameSite.substring(1).toLowerCase());
        return serializer;
    }
}
