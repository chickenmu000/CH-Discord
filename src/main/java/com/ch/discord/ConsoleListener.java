package com.ch.discord;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 콘솔 채널에 올라온 메시지를 서버 콘솔 명령어로 실행 */
public class ConsoleListener extends ListenerAdapter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CHDiscord plugin;
    private final String channelId;

    public ConsoleListener(CHDiscord plugin, String channelId) {
        this.plugin = plugin;
        this.channelId = channelId;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;
        if (!event.getChannel().getId().equals(channelId)) return;
        if (event.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) return;

        var cfg = plugin.getConfig();
        if (cfg.getBoolean("command.block-bots", true) && event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw().trim();
        if (content.isEmpty()) return;

        // 블랙리스트 / 화이트리스트 (명령어 이름만 비교, 플러그인 접두사 minecraft:xxx 는 제거)
        boolean whitelist = cfg.getString("command.filter-mode", "blacklist").equalsIgnoreCase("whitelist");
        List<String> filter = cfg.getStringList("command.filter-commands");
        String name = content.split(" ")[0].toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf(':') + 1);
        boolean listed = false;
        for (String f : filter) {
            if (f.toLowerCase(Locale.ROOT).equals(name)) {
                listed = true;
                break;
            }
        }
        if (whitelist != listed) return;

        // 실행 기록 (실패하면 안전을 위해 실행하지 않을 수 있음)
        String logName = cfg.getString("command.usage-log", "");
        if (logName != null && !logName.isBlank()) {
            File file = new File(plugin.getDataFolder(), logName);
            String line = "[" + LocalDateTime.now().format(STAMP) + " | ID " + event.getAuthor().getId() + "] "
                    + event.getAuthor().getName() + ": " + content + System.lineSeparator();
            try {
                Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().warning("명령어 기록 실패 (" + file.getAbsolutePath() + "): " + e.getMessage());
                if (cfg.getBoolean("command.cancel-if-log-fails", true)) return;
            }
        }

        final String command = content;
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }
}
