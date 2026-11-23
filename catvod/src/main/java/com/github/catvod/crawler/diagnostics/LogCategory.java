package com.github.catvod.crawler.diagnostics;

import java.util.Locale;

/**
 * Display categories for the runtime log (#49, defined by Liu): every
 * stored entry carries one of these as a field so the in-app panel can
 * filter on the field itself instead of guessing from line content.
 * The label is also written into the stored line at capture time (text
 * entries get a "[label]" prefix on the message, structured av-diag
 * entries get a "logCategory" JSON property), so the panel, the panel
 * export and the web debug page download all show the same category.
 */
public enum LogCategory {
    RUN("运行"), FILE("文件加载"), MEDIA("影片"), PLAY("播放"), SPIDER("蜘蛛"), AD("广告拦截");

    /**
     * Error marker written right after the category label of a text line
     * ("[影片][报错] ..."). "报错" is a filter, not a category: an error entry
     * stays in its own category and is additionally shown under 报错.
     */
    public static final String ERROR_LABEL = "报错";

    public final String label;

    LogCategory(String label) {
        this.label = label;
    }

    /**
     * Deterministic source-side mapping from the emitter's declared tag
     * (the tag is part of the logging call, not scraped from the message).
     * Anything unmapped is a program/runtime log (RUN) by definition.
     */
    public static LogCategory forTag(String tag) {
        String t = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        if (t.equals("spiderdebug") || t.equals("quickjs") || t.equals("python-spider") || t.startsWith("spider")) return SPIDER;
        switch (t) {
            // Liu 2026-11-23 17:26: details page and play links belong to PLAY.
            case "detail":
            case "httpresolver":
            case "customwebview":
                return PLAY;
            // Web-source search results (per-site counts) are film lists.
            case "web-search":
                return MEDIA;
            // Spider creation failures and the web-source fetcher are spider logs;
            // the web-source engine's own lines get their stage category explicitly.
            case "jar-spider":
            case "js-spider":
            case "webfetch":
            case "web":
                return SPIDER;
            case "localsource":
                return FILE;
            case "hlsadsparser":
                return AD;
            case "home":
            case "homevideo":
            case "category":
            case "search":
            case "action":
                // SiteApi content calls: film lists, details and search data.
                // (SiteApi's playerContent calls share the "player" tag with
                // the player itself, so those pass MEDIA explicitly instead.)
                return MEDIA;
            // Player/engine tags ("player", "player-engine", "playback-flow",
            // "exo-source", "okhttp-player", "playback*"/"mpv*"/"iso*" prefixes)
            // are engine diagnostics and fall through to RUN: since #51 the PLAY
            // category is reserved for the now-playing info entry, which is
            // always written with an explicit category.
            case "config":
            case "jar":
            case "dex":
            case "ext":
            case "download":
                return FILE;
            default:
                break;
        }
        if (t.startsWith("file") || t.startsWith("load")) return FILE;
        return RUN;
    }

    /**
     * Structured av-diag events are engine/playback diagnostics. By Liu's
     * definition (#51) they all belong to RUN; PLAY is reserved for the
     * now-playing info entry (film details + playback address), which is
     * written as a text entry with an explicit category.
     */
    public static LogCategory forEvent(String name) {
        return RUN;
    }

    /**
     * Rebuild the category of a line restored from disk, from the marker
     * written into it at capture time. This is format parsing of our own
     * written marker, used only when reconstructing buffer entries after
     * a process restart; live entries always carry the field itself.
     */
    public static LogCategory fromLine(String line) {
        if (line == null) return RUN;
        int json = line.indexOf("\"logCategory\":\"");
        if (json >= 0) {
            int start = json + "\"logCategory\":\"".length();
            int end = line.indexOf('"', start);
            if (end > start) {
                LogCategory category = byLabel(line.substring(start, end));
                if (category != null) return category;
            }
            return RUN;
        }
        // Text lines: "time [thread] tag: [label] message ..." — the tag
        // colon is the first ": " (the timestamp's colons are never
        // followed by a space).
        int colon = line.indexOf(": ");
        if (colon >= 0 && colon + 3 < line.length() && line.charAt(colon + 2) == '[') {
            int end = line.indexOf(']', colon + 3);
            if (end > colon + 3) {
                LogCategory category = byLabel(line.substring(colon + 3, end));
                if (category != null) return category;
            }
        }
        return RUN;
    }

    /** True when a stored line carries the error marker (text) or an error/fatal level (structured). */
    public static boolean isError(String line) {
        if (line == null) return false;
        int json = line.indexOf("\"logCategory\":\"");
        if (json >= 0) return line.contains("\"level\":\"error\"") || line.contains("\"level\":\"fatal\"");
        int colon = line.indexOf(": ");
        if (colon >= 0 && colon + 3 < line.length() && line.charAt(colon + 2) == '[') {
            int end = line.indexOf(']', colon + 3);
            return end > colon + 3 && line.startsWith("[" + ERROR_LABEL + "]", end + 1);
        }
        return false;
    }

    public static LogCategory byLabel(String label) {
        for (LogCategory category : values()) if (category.label.equals(label)) return category;
        return null;
    }
}

