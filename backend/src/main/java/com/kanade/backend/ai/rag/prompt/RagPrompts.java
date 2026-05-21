package com.kanade.backend.ai.rag.prompt;

/**
 * RAG 提示词常量类
 * 
 * <p>集中管理所有 RAG 模块使用的提示词模板常量，避免硬编码在业务逻辑中。
 * 
 * <p>使用示例：
 * <pre>{@code
 * import static com.kanade.backend.ai.rag.prompt.RagPrompts.*;
 * 
 * String prompt = String.format(QUERY_COMPRESSION_TEMPLATE, historyText, queryText);
 * }</pre>
 * 
 * @author kanade
 */
public interface RagPrompts {



    // ==================== 查询转换提示词 ====================

    /**
     * 查询压缩提示词模板
     * 
     * <p>用途：将依赖上下文的模糊查询重写为独立完整的问题
     * 
     * <p>占位符：
     * <ul>
     *   <li>%s - 对话历史（格式化后的文本）</li>
     *   <li>%s - 当前查询文本</li>
     * </ul>
     * 
     * <p>示例：
     * <pre>
     * 原始问题："他还在世吗？"（依赖上下文）
     * 压缩后："Guido van Rossum还在世吗？"（独立完整）
     * </pre>
     */
    public static final String QUERY_COMPRESSION_TEMPLATE = """
        你是一个查询优化助手。请将用户的最新问题结合对话历史，重写为一个独立的、完整的问题。
        
        要求：
        1. 消除代词（如'它'、'这个'），替换为具体实体
        2. 保持原问题的核心意图
        3. 只输出重写后的问题，不要添加任何解释
        
        对话历史：
        %s
        
        最新问题: %s
        
        重写后的问题: 
        """;

    /**
     * 多策略查询转换提示词模板
     * 
     * <p>用途：生成 HyDE（假设文档嵌入）和 Step-back（回退查询）两种查询
     * 
     * <p>占位符：
     * <ul>
     *   <li>%s - 对话历史（格式化后的文本）</li>
     *   <li>%s - 当前查询文本</li>
     * </ul>
     * 
     * <p>期望输出格式：JSON
     * <pre>{@code
     * {
     *   "queries": [
     *     {
     *       "text": "生成的查询文本",
     *       "type": "hyde",
     *       "target": "vector"
     *     },
     *     {
     *       "text": "回退概念查询",
     *       "type": "step_back",
     *       "target": "text"
     *     }
     *   ]
     * }
     * }</pre>
     */
    public static final String MULTI_STRATEGY_QUERY_TEMPLATE = """
        你是一个查询优化专家。请分析用户的当前问题，生成两种类型的辅助查询来提高检索效果：
        
        ## 策略说明
        
        1. **HyDE（假设文档嵌入）**：假设你已经知道答案，生成一段可能的答案摘要。这段摘要的特征词会匹配到相关文档，从而提高语义检索的召回率。target 建议为 "vector"。
        
        2. **Step-back（回退查询）**：将具体问题抽象回更通用的背景概念或上层主题。回退查询适合关键词精确匹配（全文检索），target 建议为 "text"。
        
        ## 约束
        - 根据问题特点选择合适的策略，不必全部使用
        - 简单问题可以只生成 1 个查询，复杂问题可以生成 2 个
        - 每个查询必须标注 target 为 "vector" 或 "text"
        - 严格输出 JSON 格式，不要添加任何解释或 Markdown 标记
        
        ## 对话历史
        %s
        
        当前问题: %s
        
        请输出 JSON 格式（不要添加 ```json 标记）：
        {
          "queries": [
            {
              "text": "生成的查询文本",
              "type": "hyde",
              "target": "vector"
            },
            {
              "text": "回退概念查询",
              "type": "step_back",
              "target": "text"
            }
          ]
        }
        """;

    // ==================== 内容注入提示词 ====================

    /**
     * 标准内容注入模板
     * 
     * <p>用途：将检索到的参考资料注入到提示词中，要求 AI 基于资料回答问题
     * 
     * <p>占位符：
     * <ul>
     *   <li>{contents} - 检索到的参考内容（已格式化，带编号）</li>
     *   <li>{question} - 用户问题</li>
     * </ul>
     */
    public static final String CONTENT_INJECTION_STANDARD = """
        你是一个智能助手，请根据以下参考资料回答问题。
        
        参考资料：
        --------
        {contents}
        --------
        
        要求：
        1. 基于参考资料回答，不要编造信息
        2. 引用资料时标注来源编号
        3. 如果资料不足，请如实告知
        
        问题：{question}
        """;

    /**
     * 简洁内容注入模板
     * 
     * <p>用途：简化版的内容注入，适合快速问答场景
     * 
     * <p>占位符：
     * <ul>
     *   <li>{contents} - 检索到的参考内容</li>
     *   <li>{question} - 用户问题</li>
     * </ul>
     */
    public static final String CONTENT_INJECTION_CONCISE = """
        根据参考资料回答问题：
        
        {contents}
        
        问题：{question}
        回答：
        """;

    /**
     * 分析型内容注入模板
     * 
     * <p>用途：要求 AI 对参考资料进行深入分析，指出关联和差异
     * 
     * <p>占位符：
     * <ul>
     *   <li>{contents} - 检索到的参考内容</li>
     *   <li>{question} - 用户问题</li>
     * </ul>
     */
    public static final String CONTENT_INJECTION_ANALYTICAL = """
        你是一个专业的知识分析助手。请仔细分析以下参考资料，然后回答问题。
        
        参考资料：
        --------
        {contents}
        --------
        
        分析要求：
        1. 综合多条资料的信息，形成完整回答
        2. 指出资料之间的关联和差异
        3. 引用具体来源编号
        4. 如果资料存在矛盾，请明确指出
        
        问题：{question}
        
        请开始分析：
        """;

    // ==================== 工具方法 ====================

    /**
     * 获取内容注入模板
     * 
     * @param templateName 模板名称（standard/concise/analytical）
     * @return 对应的模板字符串，如果不存在则返回 standard 模板
     */
    public static String getContentInjectionTemplate(String templateName) {
        return switch (templateName != null ? templateName.toLowerCase() : "") {
            case "concise" -> CONTENT_INJECTION_CONCISE;
            case "analytical" -> CONTENT_INJECTION_ANALYTICAL;
            default -> CONTENT_INJECTION_STANDARD;
        };
    }
}
