package com.hanafubuki.updater.source;

import java.io.IOException;

/**
 * Thrown by a source when it has no usable version and can say why (for example only beta
 * versions exist). The plugin is reported as "no version" with this message, not as a failure.
 */
public class NoVersionException extends IOException {

    public NoVersionException(String message) {
        super(message);
    }
}
