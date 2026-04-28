package dev.cuong.payment.infrastructure.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates SLF4J MDC with three correlation fields for the duration of an HTTP
 * request, then clears MDC in {@code finally} so no values leak to the next request
 * served by the same Tomcat thread.
 *
 * <p>MDC keys set:
 * <ul>
 *   <li>{@code traceId} — generated UUID, one per request. Unconditional.</li>
 *   <li>{@code userId}  — taken from the authenticated principal in the
 *       {@link SecurityContextHolder}. Set only for authenticated requests.</li>
 *   <li>{@code transactionId} — taken from the {@code X-Transaction-Id} request
 *       header if the client provides it (e.g. for cross-service correlation).</li>
 * </ul>
 *
 * <p>Wired in {@code SecurityConfig} <em>after</em> {@code JwtAuthenticationFilter}
 * so the principal is already populated when this filter runs.
 */
@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_USER_ID = "userId";
    static final String MDC_TRANSACTION_ID = "transactionId";

    private static final String HEADER_TRANSACTION_ID = "X-Transaction-Id";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            MDC.put(MDC_TRACE_ID, UUID.randomUUID().toString());

            String userId = currentUserId();
            if (userId != null) {
                MDC.put(MDC_USER_ID, userId);
            }

            String transactionId = request.getHeader(HEADER_TRANSACTION_ID);
            if (transactionId != null && !transactionId.isBlank()) {
                MDC.put(MDC_TRANSACTION_ID, transactionId);
            }

            filterChain.doFilter(request, response);
        } finally {
            // Clear all keys this filter could have set — safe because this filter is
            // the outermost MDC writer for HTTP requests.
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        // JwtAuthenticationFilter sets the principal as a UUID; "anonymousUser" string
        // is the AnonymousAuthenticationFilter's placeholder for unauthenticated requests.
        if (principal instanceof UUID uuid) {
            return uuid.toString();
        }
        return null;
    }
}
