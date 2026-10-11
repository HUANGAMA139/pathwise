package com.example.smartdataqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能数据问答助手。
 *
 * <p>目标：让 Agent 自主判断一个问题该走「文档检索(RAG)」还是「数据库查询(Text2SQL)」，
 * 或者两边都走。路由决策本身是本项目的原创点——开源里数据问答类项目都是纯 Text2SQL，
 * RAG 只做 schema 召回；RAG 类项目不碰 SQL。
 */
@SpringBootApplication
public class SmartDataQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartDataQaApplication.class, args);
    }
}
