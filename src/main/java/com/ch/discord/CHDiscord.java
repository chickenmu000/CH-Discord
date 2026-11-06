package com.ch.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 디스코드 채널로 서버 콘솔을 보내고, 그 채널에서 콘솔 명령어를 실행하는 플러그인 */
public class CHDiscord extends JavaPlugin {

    private volatile JDA jda;
    private volatile TextChannel consoleChannel;
    private ConsoleAppender appender;
    private int generation; // reload / disable 시 이전 연결 작업을 무효화

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start();
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void start() {
        String token = getConfig().getString("bot-token", "");
        String channelId = getConfig().getString("console-channel-id", "");
        if (token == null || token.isBlank() || channelId == null || channelId.isBlank()) {
            getLogger().warning("config.yml 의 bot-token / console-channel-id 를 채운 뒤 /chdiscord reload 하세요.");
            return;
        }

        // 로그는 연결 전부터 쌓아두었다가 채널이 준비되면 전송 (서버 시작 로그도 전달됨)
        List<Pattern> ignore = new ArrayList<>();
        for (String re : getConfig().getStringList("console.ignore-patterns")) {
            try {
                ignore.add(Pattern.compile(re));
            } catch (PatternSyntaxException ex) {
                getLogger().warning("ignore-patterns 정규식 오류 (" + re + "): " + ex.getDescription());
            }
        }
        appender = new ConsoleAppender(
                () -> consoleChannel,
                ConsoleAppender.parseLevels(getConfig().getStringList("console.levels")),
                getConfig().getBoolean("console.use-code-blocks", true),
                getConfig().getString("console.prefix", "[{date} {level}] "),
                getConfig().getInt("console.max-stacktrace-lines", 20),
                ignore,
                Math.max(1, getConfig().getInt("console.send-interval-seconds", 2)));
        appender.attach();

        final int gen = ++generation;
        getServer().getScheduler().runTaskAsynchronously(this, () -> connect(gen, token, channelId));
    }

    private void connect(int gen, String token, String channelId) {
        try {
            JDA built = JDABuilder.createLight(token,
                            GatewayIntent.GUILDS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .disableCache(CacheFlag.values())
                    .setMemberCachePolicy(MemberCachePolicy.NONE)
                    .addEventListeners(new ConsoleListener(this, channelId))
                    .build();
            built.awaitReady();
            if (gen != generation) { // 연결하는 동안 reload / disable 됨
                built.shutdownNow();
                return;
            }
            jda = built;
            TextChannel ch = built.getTextChannelById(channelId);
            if (ch == null) {
                getLogger().warning("콘솔 채널을 찾지 못했습니다. console-channel-id 와 봇의 채널 권한을 확인하세요: " + channelId);
                return;
            }
            consoleChannel = ch;
            getLogger().info("디스코드 콘솔 채널 연결: #" + ch.getName());
        } catch (Exception ex) {
            getLogger().warning("디스코드 연결 실패: " + ex.getMessage());
        }
    }

    private void stop() {
        generation++;
        if (appender != null) { // 남은 로그를 먼저 전송한 뒤 채널 연결 해제
            appender.detach();
            appender = null;
        }
        consoleChannel = null;
        JDA j = jda;
        jda = null;
        if (j != null) j.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("chdiscord.admin")) {
            sender.sendMessage("§c권한이 없습니다.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            stop();
            start();
            sender.sendMessage("§aCH-Discord 설정을 다시 불러오고 디스코드에 재연결합니다.");
            return true;
        }
        sender.sendMessage("§e/chdiscord reload §7- config.yml 다시 불러오기");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("chdiscord.admin")) return List.of("reload");
        return List.of();
    }
}
