package com.agent;

import com.agent.history.ConversationManager;
import com.agent.config.ProviderConfig;
import com.agent.llm.LlmClient;
import com.agent.llm.StreamEvent;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        ProviderConfig config = new ProviderConfig(
                "deepseek-v4",
                "anthropic",
                "https://api.deepseek.com/anthropic",
                "deepseek-v4-flash",
                "sk-c39ba4ff31ac42ae8fa5d6a20451c66f",
                true);
        LlmClient client = LlmClient.create(config,"你是一个代码编写助手");
        ConversationManager conversationManager = new ConversationManager();
        conversationManager.addUserMessage("我想使用java编写一个冒泡排序代码");
        BlockingQueue<StreamEvent> events = client.stream(conversationManager, new ArrayList<>());
        conversationManager.addAssistantMessage(extracted(events).toString());
        conversationManager.addUserMessage("变成python实现");
        BlockingQueue<StreamEvent> events2 = client.stream(conversationManager, new ArrayList<>());
        extracted(events2);
    }

    private static StringBuilder extracted(BlockingQueue<StreamEvent> events) throws InterruptedException {
        StringBuilder s = new StringBuilder();
        while (true) {
            StreamEvent event = events.take();
            switch (event) {
                case StreamEvent.TextDelta td        -> {
                    System.out.print(td.text());

                    s.append(td.text());
                }
                case StreamEvent.ThinkingDelta td    -> {}  // 忽略思想过程
                case StreamEvent.StreamEnd se        -> { return s;}
                case StreamEvent.Error e             -> { System.err.println("\n[ERROR] " + e.message()); System.exit(1); }
                default -> {}
            }
        }
    }
}
