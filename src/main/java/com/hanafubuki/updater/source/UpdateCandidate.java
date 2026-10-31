package com.hanafubuki.updater.source;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.hanafubuki.updater.util.Checksum;

/**
 * The newest version a source offers for a plugin.
 *
 * @param version         version name as published by the source
 * @param downloadUrl     where to get the jar (https, or file: for the local source)
 * @param checksum        expected hash of the file, or null if the source publishes none
 * @param downloadHeaders extra headers for the download request (auth for private sources)
 * @param freshness       whether the source already knows this is newer than the installed jar
 * @param pageUrl         human readable page for this version (for notifications), may be null
 * @param notes           warnings to show to admins, e.g. "no checksum published"
 */
public record UpdateCandidate(String version, URI downloadUrl, Checksum checksum, Map<String, String> downloadHeaders,
                              Freshness freshness, String pageUrl, List<String> notes) {

    public UpdateCandidate {
        downloadHeaders = downloadHeaders == null ? Map.of() : Map.copyOf(downloadHeaders);
        notes = notes == null ? List.of() : List.copyOf(notes);
        if (freshness == null) {
            freshness = Freshness.UNKNOWN;
        }
    }

    /** What the source knows about this candidate compared to the installed jar. */
    public enum Freshness {
        /** The source knows the candidate is newer (e.g. Modrinth identified the installed file). */
        NEWER,
        /** The installed jar is this exact file or a newer one. */
        SAME,
        /** Unknown; compare version strings. */
        UNKNOWN
    }
}
