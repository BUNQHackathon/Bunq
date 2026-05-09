package com.bunq.javabackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Component
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private final String adminToken;

    public BearerTokenAuthFilter(@Value("${app.admin.token:}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && !adminToken.isBlank()) {
            String provided = header.substring(7);
            // Hash both sides to fixed 32-byte digests before constant-time compare,
            // eliminating the length-leak timing oracle.
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] providedHash = digest.digest(provided.getBytes(StandardCharsets.UTF_8));
                digest.reset();
                byte[] expectedHash = digest.digest(adminToken.getBytes(StandardCharsets.UTF_8));
                if (MessageDigest.isEqual(providedHash, expectedHash)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (NoSuchAlgorithmException e) {
                throw new ServletException("SHA-256 not available", e);
            }
        }
        filterChain.doFilter(request, response);
    }
}
