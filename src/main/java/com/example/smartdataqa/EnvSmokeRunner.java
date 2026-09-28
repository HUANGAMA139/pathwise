package com.example.smartdataqa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 迭代 1 环境冒烟测试。
 *
 * <p>只做一件事：证明 API Key 有效、模型能访问。这一步值得单独做——
 * 等到 迭代 6 写 Agent 时才发现 Key 不通，会很难定位是环境问题还是代码问题。
 *
 * <p>开启方式（默认关闭，避免每次启动都花钱）：
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--sdaq.smoke.enabled=true
 * </pre>
 * 测完会自动退出，不用手动 Ctrl+C。
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.smoke.enabled", havingValue = "true")
public class EnvSmokeRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvSmokeRunner.class);

    @Bean
    ApplicationRunner environmentSmokeTest(ChatModel chatModel, ConfigurableApplicationContext ctx) {
        return args -> {
            log.info("========== 迭代 1 环境冒烟测试 ==========");
            log.info("正在调用通义千问，验证 API Key 与网络连通性 ...");

            long start = System.currentTimeMillis();
            try {
                var response = chatModel.call(new Prompt("用一句话回答：你现在能正常工作吗？"));
                long cost = System.currentTimeMillis() - start;

                // 直接打印整个响应对象：这样不依赖具体 getter 名称，先确保跑通。
                // 迭代 6 学 ChatClient 时再规范地取文本。
                log.info("模型原始响应: {}", response);
                log.info("耗时: {} ms", cost);
                log.info("========== 冒烟测试通过：API Key 有效、模型可访问 ==========");

                exit(ctx, 0);
            }
            catch (Exception e) {
                // 把最常见的失败原因直接讲清楚，而不是甩一堆堆栈让人去猜
                log.error("========== 冒烟测试失败 ==========");
                log.error("错误信息: {}", e.getMessage());
                log.error("");
                log.error("按下面几条逐一排查（按出现频率排序）：");
                log.error("  1. API Key 没设置，或值写错了");
                log.error("     新开一个 PowerShell 执行 echo $env:AI_DASHSCOPE_API_KEY 确认有值");
                log.error("  2. 设完 setx 之后没有重开终端/IDE（setx 只对新开的窗口生效）");
                log.error("  3. 阿里云账号还没完成实名认证（未实名拿不到可用 Key）");
                log.error("  4. 网络不通：公司/学校网络可能拦截了 dashscope.aliyuncs.com");
                log.error("  5. Key 对应的百炼服务未开通或额度已耗尽");

                exit(ctx, 1);
            }
        };
    }

    /** 标准的 Spring Boot 退出方式：先让容器优雅关闭，再退出 JVM。 */
    private static void exit(ConfigurableApplicationContext ctx, int code) {
        System.exit(SpringApplication.exit(ctx, () -> code));
    }
}
