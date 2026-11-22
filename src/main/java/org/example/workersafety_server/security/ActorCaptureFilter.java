package org.example.workersafety_server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class ActorCaptureFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            request.setAttribute("actorRole", authentication.getAuthorities().stream().findFirst()
                    .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", "" )).orElse("UNKNOWN"));
            Object principal = authentication.getPrincipal();
            request.setAttribute("actorId", principal instanceof org.springframework.security.core.userdetails.UserDetails user
                    ? user.getUsername() : principal);
        }
        chain.doFilter(request, response);
    }
}
