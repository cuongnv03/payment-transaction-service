package dev.cuong.payment.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.RateLimiter;
import dev.cuong.payment.presentation.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Applies a per-user sliding-window rate limit to {@code POST /api/transactions}.
 *
 * <p>This filter runs after {@code JwtAuthenticationFilter}, so the userId is already
 * available in the {@code SecurityContext}. Unauthenticated requests are passed through
 * (the downstream security layer will return 401).
 *
 * <p>When the limit is exceeded the filter writes a 429 JSON response directly — it does
 * not throw an exception, because {@code @RestControllerAdvice} only handles exceptions
 * inside the {@code DispatcherServlet}, not from filters.
 */
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITED_PATH = "/api/transactions";
    private static final long RETRY_AFTER_SECONDS = 60L;

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!isRateLimitedEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID userId = extractUserId();
        if (userId == null) {
            // Unauthenticated request — pass through; 401 handled by Spring Security
            filterChain.doFilter(request, response);
            return;
        }

        if (rateLimiter.tryConsume(userId)) {
            filterChain.doFilter(request, response);
        } else {
            sendRateLimitResponse(response, userId);
        }
    }

    private boolean isRateLimitedEndpoint(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && RATE_LIMITED_PATH.equals(request.getRequestURI());
    }

    private UUID extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID userId)) {
            return null;
        }
        return userId;
    }

    private void sendRateLimitResponse(HttpServletResponse response, UUID userId) throws IOException {
        log.warn("Rate limit response sent: userId={}", userId);
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        ApiError error = new ApiError("RATE_LIMIT_EXCEEDED",
                "Too many requests. You may send at most 10 transactions per 60 seconds.");
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
