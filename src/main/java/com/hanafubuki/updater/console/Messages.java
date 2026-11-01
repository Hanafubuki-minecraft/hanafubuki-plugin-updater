package com.hanafubuki.updater.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.command.CommandSender;

import com.hanafubuki.updater.config.UpdaterSettings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.format.Style;

/**
 * Formats chat and console messages with MiniMessage, using the styles from config.yml
 * ({@code style:} section).
 *
 * <p>Message templates use the tags {@code <primary>}, {@code <accent>}, {@code <success>},
 * {@code <warning>}, {@code <error>} and {@code <muted>}. Values such as plugin names are always
 * inserted as plain text, so they can never inject formatting or click events.
 */
public final class Messages {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Component prefix;
    private final TagResolver styles;

    public Messages(UpdaterSettings.Style style) {
        this.prefix = mini.deserialize(style.prefix());
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, String> e : style.colors().entrySet()) {
            Style s = styleOf(e.getValue());
            resolvers.add(TagResolver.resolver(e.getKey(), Tag.styling(builder -> builder.merge(s))));
        }
        this.styles = TagResolver.resolver(resolvers);
    }

    /** Extracts the style that a MiniMessage opening tag sequence such as "<#ff00aa><bold>" applies. */
    private Style styleOf(String openingTags) {
        Component c = mini.deserialize(openingTags + "x");
        Style s = c.style();
        while (!c.children().isEmpty()) {
            c = c.children().get(0);
            s = s.merge(c.style());
        }
        return s;
    }

    /** Plain-text placeholder, e.g. {@code text("plugin", name)} for {@code <plugin>}. */
    public static TagResolver text(String key, Object value) {
        return Placeholder.unparsed(key, value == null ? "-" : String.valueOf(value));
    }

    public Component prefix() {
        return prefix;
    }

    /** Formats a template without the prefix. */
    public Component format(String template, TagResolver... placeholders) {
        return mini.deserialize(template, TagResolver.resolver(styles, TagResolver.resolver(placeholders)));
    }

    /** Formats a template with the prefix. */
    public Component prefixed(String template, TagResolver... placeholders) {
        return prefix.append(format(template, placeholders));
    }

    public void send(CommandSender to, String template, TagResolver... placeholders) {
        to.sendMessage(prefixed(template, placeholders));
    }

    /** Sends a line without prefix (for lists below a header). */
    public void sendLine(CommandSender to, String template, TagResolver... placeholders) {
        to.sendMessage(format(template, placeholders));
    }
}
