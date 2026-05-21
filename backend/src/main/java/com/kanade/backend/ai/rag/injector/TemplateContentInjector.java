package com.kanade.backend.ai.rag.injector;

import com.kanade.backend.ai.rag.prompt.RagPrompts;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class TemplateContentInjector implements ContentInjector {

    private final String templateName;

    public TemplateContentInjector() {
        this("standard");
    }

    public TemplateContentInjector(String templateName) {
        this.templateName = templateName;
    }

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (contents == null || contents.isEmpty()) {
            log.warn("无参考内容，返回原消息");
            return chatMessage;
        }

        String formattedContents = formatContents(contents);
        String questionText = chatMessage instanceof UserMessage
            ? ((UserMessage) chatMessage).singleText()
            : chatMessage.toString();

        String template = RagPrompts.getContentInjectionTemplate(templateName);
        String prompt = template.replace("{contents}", formattedContents)
            .replace("{question}", questionText);

        log.info("注入了 {} 条参考内容，使用模板: {}", contents.size(), templateName);
        return UserMessage.from(prompt);
    }

    private String formatContents(List<Content> contents) {
        return IntStream.range(0, contents.size())
            .mapToObj(i -> {
                Content content = contents.get(i);
                String text = content.textSegment().text();
                if (text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                return String.format("[%d] %s", i + 1, text);
            })
            .collect(Collectors.joining("\n\n"));
    }
}
