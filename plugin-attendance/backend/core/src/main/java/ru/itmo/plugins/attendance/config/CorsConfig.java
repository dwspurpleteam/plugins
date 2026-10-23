package ru.itmo.plugins.attendance.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
class CorsConfig {

    /**
     * Meet встраивает плагин в iframe с {@code sandbox="allow-scripts"} без {@code allow-same-origin}:
     * у страницы opaque origin ({@code Origin: null}), и даже запросы к своему же серверу становятся
     * кросс-доменными. CORS нужен и API, и статике: модульные скрипты Vite загружаются в режиме cors.
     * Cookie и другие учётные данные плагин не использует, поэтому хватает {@code *}. POST нужен кнопкам плагина.
     */
    @Bean
    CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(CorsConfiguration.ALL);
        configuration.addAllowedMethod(HttpMethod.GET);
        configuration.addAllowedMethod(HttpMethod.HEAD);
        configuration.addAllowedMethod(HttpMethod.POST);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}
