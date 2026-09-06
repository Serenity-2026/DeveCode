// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.agent.skill;



import com.agent.llm.Message;

import java.util.List;

/**
 skill以fork模式运行时Agent需要的能力
 */
public interface SkillForkHost extends SkillHost {
    /*用指定模型跑一个隔离子Agent，入参是skill正文+继承的父对话种子，返回子Agent的最终文本；*/
    String runSubAgent(String body, List<Message> seed, String model);
    /*快照（不是引用）父对话消息列表。用“快照”这个词是因为子Agent跑的过程中父对话可能继续变化，子Agent拿到的必须是启动那一刻的冻结版本。*/
    List<Message> snapshotParentMessages();
}

