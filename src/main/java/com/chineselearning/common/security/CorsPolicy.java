package com.chineselearning.common.security;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.*;

@Configuration
public class CorsPolicy {
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${security.cors.allowed-origins:}") String origins) {
        var allowed=new ArrayList<String>();
        for(String entry:origins.split(",")) {
            String origin=entry.strip(); if(origin.isEmpty())continue;
            URI uri=URI.create(origin);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getRawPath().isEmpty() || origin.contains("*"))
                throw new IllegalArgumentException("CORS origins must be exact HTTPS origins without paths or wildcards.");
            allowed.add(origin);
        }
        var config=new CorsConfiguration();config.setAllowedOrigins(allowed);config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type","X-CSRF-TOKEN"));config.setExposedHeaders(List.of("X-Request-ID","Retry-After"));config.setMaxAge(600L);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/**",config);return source;
    }
}
