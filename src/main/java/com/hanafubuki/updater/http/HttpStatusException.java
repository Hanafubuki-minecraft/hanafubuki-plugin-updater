package com.hanafubuki.updater.http;

import java.io.IOException;
import java.net.URI;

/**
 * Thrown when a server answers with an unexpected HTTP status.
 */
public class HttpStatusException extends IOException {

    private final int status;
    private final URI uri;

    public HttpStatusException(int status, URI uri, String bodySnippet) {
        super("HTTP " + status + " from " + uri.getHost() + uri.getPath()
                + (bodySnippet == null || bodySnippet.isBlank() ? "" : ": " + bodySnippet));
        this.status = status;
        this.uri = uri;
    }

    public int status() {
        return status;
    }

    public URI uri() {
        return uri;
    }
}
