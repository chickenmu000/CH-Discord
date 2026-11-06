package com.ch.discord;

import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 서버 콘솔(log4j) 로그를 모아서 디스코드 채널로 보냄.
 * 로그를 받는 즉시 큐에 쌓고, 일정 간격마다 2000자 이내로 묶어서 전송 (디스코드 속도 제한 보호).
 */
public class ConsoleAppender extends AbstractAppender {

    private static final int MAX_QUEUE = 5000;
    private static final int LIMIT = 1900; // 메시지 한도(2000) - 코드블록 여유
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern LEGACY = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Supplier<TextChannel> channel;
    private final Set<Level> levels;
    private final boolean codeBlocks;
    private final String prefix;
    private final int maxTrace;
    private final List<Pattern> ignore;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CH-Discord-Console");
        t.setDaemon(true);
        return t;
    });

    public ConsoleAppender(Supplier<TextChannel> channel, Set<Level> levels, boolean codeBlocks, String prefix,
                           int maxTrace, List<Pattern> ignore, int intervalSeconds) {
        super("CH-Discord-Console", null, null, true, Property.EMPTY_ARRAY);
        this.channel = channel;
        this.levels = levels;
        this.codeBlocks = codeBlocks;
        this.prefix = prefix;
        this.maxTrace = Math.max(0, maxTrace);
        this.ignore = ignore;
        sender.scheduleWithFixedDelay(this::flushSafe, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public static Set<Level> parseLevels(List<String> names) {
        Set<Level> set = new java.util.HashSet<>();
        for (String n : names) {
            Level l = Level.getLevel(n.toUpperCase(Locale.ROOT));
            if (l != null) set.add(l);
        }
        return set;
    }

    /** 루트 로거에 붙임 */
    public void attach() {
        start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(this);
    }

    /** 로거에서 떼고 남은 로그를 마저 전송 */
    public void detach() {
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
        sender.shutdown();
        flushSafe();
    }

    @Override
    public void append(LogEvent event) {
        try {
            if (!levels.contains(event.getLevel())) return;
            String logger = event.getLoggerName();
            // JDA 자신의 로그는 보내지 않음 (전송 실패 -> 로그 -> 전송 무한 반복 방지)
            if (logger != null && logger.startsWith("net.dv8tion")) return;

            String msg = strip(event.getMessage().getFormattedMessage());
            StringBuilder body = new StringBuilder(msg);
            Throwable t = event.getThrown();
            if (t != null && maxTrace > 0) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                String[] lines = sw.toString().split("\\R");
                for (int i = 0; i < lines.length && i < maxTrace; i++) body.append('\n').append(lines[i]);
                if (lines.length > maxTrace) body.append("\n\t... ").append(lines.length - maxTrace).append(" more");
            }

            String head = prefix
                    .replace("{date}", TIME.format(Instant.ofEpochMilli(event.getTimeMillis())))
                    .replace("{level}", event.getLevel().name())
                    .replace("{name}", logger == null ? "" : logger.substring(logger.lastIndexOf('.') + 1));

            for (String line : body.toString().split("\\R")) {
                if (line.isBlank()) continue;
                boolean skip = false;
                for (Pattern p : ignore) {
                    if (p.matcher(line).find()) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;
                queue.add(head + line);
                if (queue.size() > MAX_QUEUE) queue.poll();
            }
        } catch (Throwable ignored) {
            // 로그 처리 중 예외가 로거를 망가뜨리지 않도록
        }
    }

    private static String strip(String s) {
        return LEGACY.matcher(ANSI.matcher(s).replaceAll("")).replaceAll("");
    }

    private void flushSafe() {
        try {
            flush();
        } catch (Throwable ignored) {
        }
    }

    private void flush() {
        if (queue.isEmpty()) return;
        TextChannel ch = channel.get();
        if (ch == null) return; // 아직 연결 전이면 계속 쌓아둠

        List<String> lines = new ArrayList<>();
        String l;
        while ((l = queue.poll()) != null) lines.add(l);

        int max = LIMIT - (codeBlocks ? 8 : 0);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.replace("```", "'''");
            while (line.length() > max) {
                send(ch, sb, line.substring(0, max));
                line = line.substring(max);
            }
            if (sb.length() + line.length() + 1 > max) send(ch, sb, null);
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        send(ch, sb, null);
    }

    /** extra 가 있으면 sb 를 먼저 보내고 extra 를 단독 전송, 없으면 sb 만 전송. 전송 후 sb 비움 */
    private void send(TextChannel ch, StringBuilder sb, String extra) {
        if (sb.length() > 0) {
            post(ch, sb.toString());
            sb.setLength(0);
        }
        if (extra != null) post(ch, extra);
    }

    private void post(TextChannel ch, String text) {
        String out = codeBlocks ? "```\n" + text + "\n```" : text;
        ch.sendMessage(out).queue(null, err -> {
        });
    }
}
