package com.neuroisp.security;

import com.neuroisp.config.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtAuthEntryPoint authEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults()) // ✅ Java 17 compatible
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(authEntryPoint)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/favicon.ico",
                                "/error",
                                "/actuator/health"
                        ).permitAll()

                        // 🔓 PUBLIC (CAPTIVE PORTAL)
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/subscriptions/**",
                                "/api/hotspot/voucher/**",
                                "/api/hotspot/**",
                                "/api/admin/packages/**",
                                "/api/payhero/**"


                        ).permitAll()

                        // 🔐 SYSTEM USERS
                        .requestMatchers("/api/system-users/**")
                        .hasAnyRole("SUPER_ADMIN", "ADMIN")

                        .requestMatchers("/admin/mikrotik/active/**")
                        .hasAnyRole("SUPER_ADMIN", "ADMIN")

                        .requestMatchers("/api/support/**")
                       .hasAnyRole("SUPER_ADMIN", "ADMIN", "SUPPORT")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 🔐 PASSWORD ENCODER
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 🌍 GLOBAL CORS (Java 17 SAFE)
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://192.168.100.3:4567",
                "http://192.168.15.26:8061",
                "https://yourdomain.com",
                "https://gradualistic-tiesha-doughtily.ngrok-free.dev",
                "http://127.0.0.1:4040",
                "http://192.168.15.26:8000",
                "https://gritfiber.netlify.app",
                "https://gallant-spirit-production.up.railway.app"

        ));

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept"
        ));

        config.setExposedHeaders(List.of("Authorization"));

        // ⚠️ JWT → no cookies
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }
    @Bean
    AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }

}
