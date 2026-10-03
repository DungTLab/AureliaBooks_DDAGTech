package com.ddagtech.aureliabooks.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility helper for inspecting HTTP servlet requests.
 * Detects whether an incoming request expects an AJAX/JSON API response or SSR HTML content.
 */
public final class HttpRequestUtil {

    private static final String XML_HTTP_REQUEST = "XMLHttpRequest";
    private static final String HEADER_REQUESTED_WITH = "X-Requested-With";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String API_PREFIX = "/api/";

    private HttpRequestUtil() {
        // Prevent instantiation of utility class
    }

    /**
     * Determines whether the given HTTP request is an AJAX or REST API request.
     *
     * @param request incoming HttpServletRequest
     * @return true if the request expects or sends JSON or originated via XMLHttpRequest
     */
    public static boolean isAjaxOrApi(HttpServletRequest request) {
        if (request == null) {
            return false;
        }

        String requestedWith = request.getHeader(HEADER_REQUESTED_WITH);
        if (XML_HTTP_REQUEST.equalsIgnoreCase(requestedWith)) {
            return true;
        }

        String acceptHeader = request.getHeader(HEADER_ACCEPT);
        if (acceptHeader != null && acceptHeader.contains(APPLICATION_JSON)) {
            return true;
        }

        String contentType = request.getHeader(HEADER_CONTENT_TYPE);
        if (contentType != null && contentType.contains(APPLICATION_JSON)) {
            return true;
        }

        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(API_PREFIX);
    }
}
