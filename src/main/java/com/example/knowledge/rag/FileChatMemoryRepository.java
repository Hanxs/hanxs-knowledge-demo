package com.example.knowledge.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地文件的对话记忆仓库，实现 {@link ChatMemoryRepository}。
 * <p>
 * 采用 {@code conversationId|role|base64(text)} 的行格式，避免引入额外序列化依赖，
 * 同时规避文本中的换行/分隔符转义问题。
 */
@Slf4j
public class FileChatMemoryRepository implements ChatMemoryRepository {

    private static final String SEPARATOR = "|";

    private final Path path;
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    public FileChatMemoryRepository(String filePath) {
        this.path = Path.of(filePath);
        load();
    }

    @Override
    public List<String> findConversationIds() {
        return new ArrayList<>(store.keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return new ArrayList<>(store.getOrDefault(conversationId, List.of()));
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        store.put(conversationId, new ArrayList<>(messages));
        persist();
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        store.remove(conversationId);
        persist();
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) {
                    continue;
                }
                Message message = toMessage(parts[1],
                        new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8));
                if (message != null) {
                    store.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(message);
                }
            }
            log.info("已从 {} 恢复 {} 个会话的对话记忆", path, store.size());
        } catch (Exception e) {
            log.warn("加载对话记忆失败: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            StringBuilder sb = new StringBuilder();
            store.forEach((conversationId, messages) -> messages.forEach(message -> sb
                    .append(conversationId).append(SEPARATOR)
                    .append(message.getMessageType().getValue()).append(SEPARATOR)
                    .append(Base64.getEncoder().encodeToString(
                            message.getText() == null ? new byte[0] : message.getText().getBytes(StandardCharsets.UTF_8)))
                    .append(System.lineSeparator())));
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("持久化对话记忆失败: {}", e.getMessage());
        }
    }

    private Message toMessage(String role, String text) {
        return switch (role) {
            case "user" -> new UserMessage(text);
            case "assistant" -> new AssistantMessage(text);
            case "system" -> new SystemMessage(text);
            default -> null;
        };
    }
}
