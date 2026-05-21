package com.kanade.backend.ai.rag.injector;

import org.springframework.stereotype.Component;

@Component
public class PromptTemplateManager {

    public TemplateContentInjector createInjector(String templateName) {
        return new TemplateContentInjector(templateName);
    }
}
