package com.example.smartdataqa.agent;

import com.example.smartdataqa.context.ChatSummarizer;
import com.example.smartdataqa.context.ContextCompactor;
import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 28 · <b>多轮对话：上下文压缩的 A/B</b>。回答计划里的第四问
 * 「上下文压缩对多轮对话的影响」。
 *
 * <h3>它为什么拖到本轮才做</h3>
 * 因为在这之前 <b>Agent 是单轮的</b>——{@code ContextCompactor}（迭代 4）实现了、有独立 demo，
 * 但没接进主链路（迭代 27 的核对把这条列为缺口）。**没有多轮，就没有「压缩对多轮的影响」可测。**
 * 所以本轮的头一件事是把多轮接上（见 {@link ConversationalAgent}），再测。
 *
 * <h3>三档，只动「历史」这一个变量</h3>
 * <pre>
 *   无历史     不带动历史        → 指代无法消解（省 token，但「它」永远是「它」）
 *   全量历史   改写时喂全部历史   → 能消解；但上下文随轮数线性增长
 *   压缩历史   超阈值后压掉远处   → 能不能消解，取决于【摘要有没有留下那个名词】
 * </pre>
 *
 * <h3>★ 判据是【确定性】的，不靠 LLM 判分</h3>
 * 判「这一轮的指代有没有被正确消解」，看的是<b>改写后那句自足问题里有没有出现被指代的【名词】</b>
 * （例如第 4 轮必须出现「客单价」）。<b>字符串包含判断，可复现、零成本。</b>
 * 这比「让 LLM 判答案对不对」稳得多——迭代 15 已经量过判分噪声有多大。
 *
 * <h3>★ 一个必须说清楚的实验设计偏差</h3>
 * 4 轮对话**撞不到真实的压缩阈值**（真实场景阈值是几千 token），所以这里<b>把阈值调小以强制触发压缩</b>
 * （默认 5 条消息 / 保留最近 2 条）。**这是受控实验，不是「真实使用下的压缩效果」。**
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp28.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp28.enabled", havingValue = "true")
public class MultiTurnDemo {

    private static final Logger log = LoggerFactory.getLogger(MultiTurnDemo.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<String> HEADER = List.of(
            "run", "arm", "turn", "question", "standalone", "expect_noun", "resolved",
            "context_chars", "compacted", "answer_head");

    /** 一轮脚本：问题 + 期望在改写结果里出现的名词（null = 这轮没有指代，不判）。 */
    private record Script(String question, String expectNoun) {
    }

    /**
     * 4 轮脚本。**第 1 轮埋下「客单价」，第 2/3 轮是无关填充，第 4 轮才用「它」回指。**
     *
     * <p>为什么中间要隔两轮：这样到第 4 轮时，第 1 轮的内容已经<b>被压缩进摘要</b>了——
     * **于是第 4 轮能不能消解，直接检验「摘要有没有把那个名词留下」。** 这是整个实验的关键设计。
     */
    private static final List<Script> SCRIPT = List.of(
            new Script("客单价怎么算？", null),
            new Script("2026年第二季度华东区的销售额是多少？", null),
            new Script("退货审核要多久？", null),
            new Script("它的分母为什么必须用已完成订单数？", "客单价"),
            new Script("那它和销售额的口径一致吗？", "客单价"));

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner exp28MultiTurn(SmartQaAgent agent,
                                     ChatClient.Builder chatClientBuilder,
                                     @Value("${sdaq.memory.max-messages:5}") int maxMessages,
                                     @Value("${sdaq.memory.keep-recent:2}") int keepRecent,
                                     @Value("${sdaq.eval.multiturn-metrics:eval/multiturn-compare.csv}") String outPath) {
        return args -> run(agent, chatClientBuilder, maxMessages, keepRecent, outPath);
    }

    private void run(SmartQaAgent agent, ChatClient.Builder builder,
                     int maxMessages, int keepRecent, String outPath) throws Exception {
        ChatClient chat = builder.build();

        log.info("========== 迭代 28 · 多轮对话：上下文压缩 A/B ==========");
        log.info("脚本：{} 轮；需要消解指代的是第 {} 轮（期望名词：{}）",
                SCRIPT.size(), pronounTurns(), "客单价");
        log.info("压缩参数：超过 {} 条消息触发，保留最近 {} 条（**调小以强制触发**，见类注释）",
                maxMessages, keepRecent);

        List<Arm> arms = List.of(
                new Arm("无历史", new ConversationalAgent(agent, chat, null, false)),
                new Arm("全量历史", new ConversationalAgent(agent, chat, null, true)),
                new Arm("压缩历史", new ConversationalAgent(agent, chat,
                        new ContextCompactor(new ChatSummarizer(chat), maxMessages, keepRecent), true)));

        String run = LocalDateTime.now().format(TS);
        List<Row> rows = new ArrayList<>();

        for (Arm arm : arms) {
            log.info("");
            log.info("########## {} ##########", arm.name());
            for (int i = 0; i < SCRIPT.size(); i++) {
                Script s = SCRIPT.get(i);
                ConversationalAgent.Turn t = arm.agent().ask(i + 1, s.question());
                boolean resolved = s.expectNoun() == null || t.standalone().contains(s.expectNoun());
                rows.add(new Row(arm.name(), i + 1, s.question(), t.standalone(),
                        s.expectNoun() == null ? "" : s.expectNoun(), resolved,
                        t.contextChars(), t.compacted()));
                log.info("  第 {} 轮 问：{}", i + 1, s.question());
                log.info("        改写后：{}", t.standalone());
                log.info("        上下文 {} 字{}   判定：{}", t.contextChars(),
                        t.compacted() ? "（本轮压缩过）" : "",
                        s.expectNoun() == null ? "（无指代，不判）" : (resolved ? "[OK] 补回了「" + s.expectNoun() + "」" : "[X] 没补回「" + s.expectNoun() + "」"));
            }
        }

        printSummary(arms, rows);
        writeCsv(Path.of(outPath), run, rows);
        printConclusion(maxMessages, keepRecent);
    }

    // ==================================================================

    private record Arm(String name, ConversationalAgent agent) {
    }

    private record Row(String arm, int turn, String question, String standalone,
                       String expectNoun, boolean resolved, int contextChars, boolean compacted) {
    }

    private void printSummary(List<Arm> arms, List<Row> rows) {
        log.info("");
        log.info("================= 三档对比 =================");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("档", 12),
                ConsoleText.padRight("指代消解", 12),
                ConsoleText.padRight("末轮上下文", 12),
                ConsoleText.padRight("压缩次数", 10), "说明");

        for (Arm arm : arms) {
            List<Row> g = new ArrayList<>();
            for (Row r : rows) {
                if (r.arm().equals(arm.name())) {
                    g.add(r);
                }
            }
            long judged = g.stream().filter(r -> !r.expectNoun().isEmpty()).count();
            long ok = g.stream().filter(r -> !r.expectNoun().isEmpty() && r.resolved()).count();
            int lastCtx = g.isEmpty() ? 0 : g.get(g.size() - 1).contextChars();
            long comps = g.stream().filter(Row::compacted).count();
            String note = switch (arm.name()) {
                case "无历史" -> "省 token，但指代全靠猜";
                case "全量历史" -> "能消解，但上下文线性涨";
                default -> "能消解 + 上下文被压住（取决于摘要保真度）";
            };
            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(arm.name(), 12),
                    ConsoleText.padRight(ok + " / " + judged, 12),
                    ConsoleText.padRight(lastCtx + " 字", 12),
                    ConsoleText.padRight(String.valueOf(comps), 10),
                    note);
        }
        log.info("");
        log.info("★ 判据是【字符串包含】：改写结果里有没有出现被指代的名词。确定性、零判分成本。");
    }

    private void writeCsv(Path path, String run, List<Row> rows) {
        try {
            for (Row r : rows) {
                Csv.appendRow(path, HEADER, List.of(
                        run, r.arm(), String.valueOf(r.turn()), r.question(), r.standalone(),
                        r.expectNoun(), r.resolved() ? "1" : "0",
                        String.valueOf(r.contextChars()), r.compacted() ? "1" : "0",
                        r.standalone().length() > 60 ? r.standalone().substring(0, 60) : r.standalone()));
            }
            log.info("");
            log.info("逐轮结果已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    private void printConclusion(int maxMessages, int keepRecent) {
        log.info("");
        log.info("================= 怎么读这张表 =================");
        log.info("1. 【无历史】那一档是现状：Agent 本来是单轮的，指代无法消解。它的 token 最省。");
        log.info("2. 【全量历史】能消解，代价是上下文随轮数线性增长 —— 这就是要多轮压缩的理由。");
        log.info("3. 【压缩历史】的关键不是「压了没有」，是【摘要有没有把那个名词留下】。");
        log.info("   第 4/5 轮之所以能检验这一点，是因为第 1 轮的「客单价」到那时已经被压进摘要了。");
        log.info("4. 阈值 {} 条 / 保留 {} 条是【调小强制触发】的，不是真实使用下的阈值 —— 见类注释。",
                maxMessages, keepRecent);
        log.info("5. 本实验只测「指代消解」，不测「模型记不记得上一轮的细节」—— 后者要另做。");
    }

    private static int pronounTurns() {
        int n = 0;
        for (Script s : SCRIPT) {
            if (s.expectNoun() != null) {
                n++;
            }
        }
        return n;
    }
}
