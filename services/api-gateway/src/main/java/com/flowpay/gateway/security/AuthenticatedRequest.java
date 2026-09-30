package com.flowpay.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Hides any {@code X-Authenticated-Customer-Id} the client sent and, when the token was valid, replaces it
 * with the id taken from the token. Downstream services can then trust the header.
 */
class AuthenticatedRequest extends HttpServletRequestWrapper {

    static final String HEADER = "X-Authenticated-Customer-Id";

    private final String customerId;

    AuthenticatedRequest(HttpServletRequest request, String customerId) {
        super(request);
        this.customerId = customerId;
    }

    @Override
    public String getHeader(String name) {
        if (HEADER.equalsIgnoreCase(name)) {
            return customerId;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (HEADER.equalsIgnoreCase(name)) {
            return customerId == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(customerId));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        for (String name : Collections.list(super.getHeaderNames())) {
            if (!HEADER.equalsIgnoreCase(name)) {
                names.add(name);
            }
        }
        if (customerId != null) {
            names.add(HEADER);
        }
        return Collections.enumeration(names);
    }
}
