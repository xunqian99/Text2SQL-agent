package com.text2sql.agent.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;

/** Browser-native HTTP Basic login, same-origin mutation guard, and global API admission. */
public final class SingleUserAccessFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final boolean enabled;
    private final byte[] credentialHash;
    private final ApiAdmissionGate gate;

    public SingleUserAccessFilter(AccessProperties config, ApiAdmissionGate gate) {
        enabled = config.isEnabled();
        if (enabled && (config.getToken() == null || !config.getToken().matches("[!-~]{32,256}"))) {
            // Deliberately omit rejected values; the exception may be logged on startup.
            throw new IllegalArgumentException("agent.access.token must contain 32..256 printable ASCII characters without spaces");
        }
        credentialHash = enabled ? digest(("demo:" + config.getToken()).getBytes(StandardCharsets.UTF_8)) : new byte[0];
        this.gate = gate;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (!enabled) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!authenticated(request)) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Text2SQL demo\", charset=\"UTF-8\"");
            reject(response, 401, "UNAUTHORIZED", "需要应用访问凭据");
            return;
        }
        if (!sameOrigin(request)) {
            reject(response, 403, "CROSS_ORIGIN_REJECTED", "不允许跨站访问");
            return;
        }
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        // Health is authenticated but unmetered; operators must be able to diagnose a saturated API.
        if (!path.startsWith("/api") || (path.equals("/api/health") && request.getMethod().equals("GET"))) {
            chain.doFilter(request, response);
            return;
        }
        var admission = gate.acquire();
        if (admission.permit() == null) {
            response.setHeader("Retry-After", Integer.toString(admission.retryAfterSeconds()));
            reject(response, 429, admission.rejection(), "访问过于频繁或并发请求已满，请稍后重试");
            return;
        }
        try (var permit = admission.permit()) { chain.doFilter(request, response); }
    }

    private boolean authenticated(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) return false;
        String header = headers.getFirst();
        if (header.length() > 1024 || !header.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(header.substring(6)); }
        catch (IllegalArgumentException malformed) { return false; }
        try { return MessageDigest.isEqual(credentialHash, digest(decoded)); }
        finally { Arrays.fill(decoded, (byte) 0); }
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) return false;
        if (SAFE_METHODS.contains(request.getMethod())) return true;
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.isEmpty()) return true; // CLI clients authenticate explicitly; browsers send Origin on mutations.
        if (origins.size() != 1) return false;
        try {
            URI origin = URI.create(origins.getFirst());
            int port = origin.getPort() == -1 ? ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80) : origin.getPort();
            return origin.getUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && request.getScheme().equalsIgnoreCase(origin.getScheme())
                    && request.getServerName().equalsIgnoreCase(origin.getHost()) && request.getServerPort() == port;
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
