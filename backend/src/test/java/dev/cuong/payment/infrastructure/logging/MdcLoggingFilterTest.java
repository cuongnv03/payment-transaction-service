package dev.cuong.payment.infrastructure.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MdcLoggingFilter}. The filter is invoked directly with mock
 * servlet primitives — no Spring context needed.
 *
 * <p>Each test asserts both the values present <em>during</em> the filter chain
 * (via a recording {@link FilterChain}) and that MDC is empty <em>after</em> the
 * filter returns. The latter is the critical thread-leak guard.
 */
class MdcLoggingFilterTest {

    private final MdcLoggingFilter filter = new MdcLoggingFilter();

    @AfterEach
    void clearStaticState() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void should_set_traceId_and_userId_during_chain_when_request_is_authenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.capturedTraceId.get()).isNotBlank();
        // Valid UUID
        UUID.fromString(chain.capturedTraceId.get());
        assertThat(chain.capturedUserId.get()).isEqualTo(userId.toString());
        assertThat(chain.capturedTransactionId.get()).isNull();
    }

    @Test
    void should_set_transactionId_when_X_Transaction_Id_header_present() throws Exception {
        authenticate(UUID.randomUUID());

        UUID inboundTxId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        request.addHeader("X-Transaction-Id", inboundTxId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.capturedTransactionId.get()).isEqualTo(inboundTxId.toString());
    }

    @Test
    void should_set_only_traceId_when_request_is_unauthenticated() throws Exception {
        // No SecurityContextHolder.getContext().setAuthentication(...) — anonymous

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.capturedTraceId.get()).isNotBlank();
        assertThat(chain.capturedUserId.get()).isNull();
    }

    @Test
    void should_clear_MDC_after_chain_completes_successfully() throws Exception {
        authenticate(UUID.randomUUID());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        request.addHeader("X-Transaction-Id", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new RecordingFilterChain());

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("transactionId")).isNull();
    }

    @Test
    void should_clear_MDC_even_when_chain_throws() {
        authenticate(UUID.randomUUID());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> { throw new ServletException("boom"); };

        try {
            filter.doFilter(request, response, throwingChain);
        } catch (ServletException | IOException expected) {
            // expected
        }

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("transactionId")).isNull();
    }

    @Test
    void should_generate_a_fresh_traceId_per_request() throws Exception {
        authenticate(UUID.randomUUID());

        RecordingFilterChain chain1 = new RecordingFilterChain();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain1);

        RecordingFilterChain chain2 = new RecordingFilterChain();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain2);

        assertThat(chain1.capturedTraceId.get())
                .isNotBlank()
                .isNotEqualTo(chain2.capturedTraceId.get());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /** Captures MDC values at the moment the chain is invoked (mid-filter). */
    private static class RecordingFilterChain implements FilterChain {
        final AtomicReference<String> capturedTraceId = new AtomicReference<>();
        final AtomicReference<String> capturedUserId = new AtomicReference<>();
        final AtomicReference<String> capturedTransactionId = new AtomicReference<>();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            capturedTraceId.set(MDC.get("traceId"));
            capturedUserId.set(MDC.get("userId"));
            capturedTransactionId.set(MDC.get("transactionId"));
        }

        @Override
        public String toString() {
            return "RecordingFilterChain{traceId=" + capturedTraceId.get()
                    + ", userId=" + capturedUserId.get()
                    + ", transactionId=" + capturedTransactionId.get() + "}";
        }
    }

}
