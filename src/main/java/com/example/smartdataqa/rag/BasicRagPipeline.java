package com.example.smartdataqa.rag;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.vector.VectorDocumentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * 迭代 15 · <b>教科书版的「基础 RAG」</b>：用 Spring AI 自带的
 * {@link QuestionAnswerAdvisor} 把「检索 → 注入 → 生成」串成一步。
 *
 * <h3>它为什么单独存在，而不是替掉 SmartQaAgent</h3>
 * 它可以被看成「不用自己写任何编排」的对照组：
 * <pre>
 *   自己写（SmartQaAgent）：路由 → 取证据 → <b>手工拼材料</b> → 模型综合
 *   教科书版（本类）：          一句话挂上 advisor → 检索和注入都由框架做
 * </pre>
 * 两者放在同一个测试集上跑，就能回答一个具体问题：
 * <b>我们手写的那套编排，到底比框架默认的做法好多少？</b>
 * 拿它当评测 baseline，后面 迭代 16/17/22 的每一次改进才有干净的对照点。
 *
 * <h3>三件必须知道的事</h3>
 * <ol>
 *   <li><b>它会丢掉「出处」。</b>advisor 注入提示词时只取 {@code Document.getText()}，
 *       把片段拼成一段纯文本——我们 {@code Chunk.render()} 里的
 *       「【片段 id｜出处：xxx】」标注在这里是看不见的。后果很实际：
 *       <b>模型没有东西可引用，用户也就没东西可核实</b>（这正是 迭代 8 定 {@code source}
 *       字段时最在意的那件事）。这个差异值多少分，评测会给答案。</li>
 *   <li><b>检索结果藏在 context 里，不在返回值里。</b>advisor 把文档放进
 *       {@code ChatClientResponse.context()}（键 {@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS}），
 *       所以要走 {@code .call().chatClientResponse()} 而不是 {@code .content()}——
 *       否则只拿到文本、拿不到「它到底检索了什么」。<b>拿不到中间产物就没法评测。</b></li>
 *   <li><b>提示词我们换成了中文的。</b>框架默认模板是英文的（"Answer YES or NO" 那套风格），
 *       中文语料 + 中文提问下用它会把语言不一致的变量混进对照里。
 *       但两个占位符名不能改：必须叫 {@code query} 和 {@code question_answer_context}。</li>
 * </ol>
 */
public class BasicRagPipeline {

    private static final Logger log = LoggerFactory.getLogger(BasicRagPipeline.class);

    /** 与 {@code SmartQaAgent} 保持一致，否则两条链路的差异里会混进「检索条数不同」。 */
    private static final int TOP_K = 5;

    /**
     * 注入用的提示词模板。
     *
     * <p>两个占位符名是<b>框架定死的</b>（见 {@code QuestionAnswerAdvisor.DEFAULT_PROMPT_TEMPLATE}），
     * 换名字就渲染不出来。
     */
    private static final String PROMPT_TEMPLATE = """
            {query}

            以下是可用资料，夹在两条虚线之间：

            ---------------------
            {question_answer_context}
            ---------------------

            只根据上面的资料回答问题。资料里没有的，直接说「资料里没有」，
            不要用常识或推测补充。如果资料是空的，明确说没有检索到相关内容。
            回答简洁，不要客套话。
            """;

    /** 一次问答的结果。形状刻意和评测需要的东西对齐：答案文本 + 检索到的片段。 */
    public record Answer(String text, List<Chunk> chunks) {
    }

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor advisor;

    public BasicRagPipeline(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(TOP_K).build())
                .promptTemplate(new PromptTemplate(PROMPT_TEMPLATE))
                .build();
    }

    /**
     * 回答一个问题。检索和注入都由 advisor 在 {@code before()} 里完成。
     */
    public Answer answer(String question) {
        log.info("  [BasicRAG] 提问：{}", question);

        // 用 chatClientResponse() 而不是 content()：前者才带 context（检索结果在那里面）
        ChatClientResponse r = this.chatClient.prompt()
                .user(question)
                .advisors(this.advisor)
                .call()
                .chatClientResponse();

        ChatResponse cr = r.chatResponse();
        String text = (cr == null || cr.getResult() == null)
                ? "" : cr.getResult().getOutput().getText();

        List<Chunk> chunks = retrievedChunks(r);
        log.info("  [BasicRAG] 检索到 {} 个片段，答案 {} 字", chunks.size(), text == null ? 0 : text.length());
        return new Answer(text, chunks);
    }

    /**
     * 从 context 里把检索到的文档取出来，还原成项目的 {@link Chunk}。
     *
     * <p>写成「挨个判断类型再转」而不是直接强转 {@code List<Document>}，
     * 是因为 {@code context} 是个不带头型的 {@code Map<String,Object>}——
     * 直接强转在编译期只能靠 unchecked 压过去，真出问题也是运行期才炸。
     */
    private List<Chunk> retrievedChunks(ChatClientResponse r) {
        Object raw = r.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .map(VectorDocumentTool::toChunk)
                .toList();
    }
}
