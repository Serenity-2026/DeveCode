
package com.agent.subAgent;



import com.agent.agent.Agent;
import com.agent.agent.AgentEvent;
import com.agent.config.ProviderConfig;
import com.agent.history.ConversationManager;
import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;
import com.agent.tool.result.ContentReplacementState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * 它是“后台子Agent的任务台账 + 结果信箱”。它把“派生子 Agent”和“等待子Agent结果”解耦：
 * 父 Agent 调用一次立刻拿到 taskId 继续干活,后台子 Agent 完成后把结果写进通知队列，父 Agent 在方便的时候再来取。
 */
public class SubAgentTaskManager {
    // 任务状态机:PENDING → RUNNING → COMPLETED
    //                            ↘ FAILED
    //                            ↘ CANCELLED
    public enum TaskStatus {
        //刚创建,线程还没跑起来；
        PENDING,
        //已启动,正在消费子Agent的事件
        RUNNING,
        //正常收到LoopComplete
        COMPLETED,
        //出现ErrorEvent、超时、被中断；
        FAILED,
        //父Agent显式调用cancelTask。
        CANCELLED
    }
    // 内部TaskEntry对外的映射
    public record Task(String id, String name, TaskStatus status, String output, String error) {}
    // 给父Agent的通知,父Agent不需要轮询,只需要drainNotifications()就能知道谁完成了
    public record TaskNotification(String taskId, String name, TaskStatus status, String output) {}
    //id-taskEntry
    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();

    private final List<TaskNotification> notifications = new ArrayList<>();
    private int nextId=0;

    private static class TaskEntry {
        final String id;
        final String name;
        //会被后台线程和父线程同时读写,所以volatile保证可见性
        volatile TaskStatus status;
        volatile String output;
        volatile String error;
        volatile Thread thread;
        //指明该Task用的是哪个Agent,要停止后台Agent只能通过agent.stop(),Thread.currentThread().interrupt()只能停止消费线程
        volatile Agent agent;


        TaskEntry(String id, String name) {
            this.id = id;
            this.name = name;
            this.status = TaskStatus.PENDING;
        }
    }
    public synchronized void attachAgent(String id, Agent agent) {
        TaskEntry t = tasks.get(id);
        if (t == null || agent == null) return;
        if (t.status == TaskStatus.COMPLETED
                || t.status == TaskStatus.FAILED
                || t.status == TaskStatus.CANCELLED) return;
        t.agent = agent;
    }

    public synchronized String createTask(String name) {
        String id = "task_" + nextId;
        nextId++;
        tasks.put(id, new TaskEntry(id, name));
        return id;
    }

    public synchronized void setRunning(String id, Thread thread) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status==TaskStatus.PENDING) {
            t.status = TaskStatus.RUNNING;
            t.thread = thread;
        }
    }

    public synchronized void setCompleted(String id, String output) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status==TaskStatus.RUNNING) {
            t.status = TaskStatus.COMPLETED;
            t.output = output;
            notifications.add(new TaskNotification(id, t.name, TaskStatus.COMPLETED, output));
            t.agent=null;
            t.thread=null;
        }
    }

    public synchronized void setFailed(String id, String errMsg) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status==TaskStatus.RUNNING) {
            t.status = TaskStatus.FAILED;
            t.error = errMsg;
            notifications.add(new TaskNotification(id, t.name, TaskStatus.FAILED, errMsg));
            t.agent=null;
            t.thread=null;
        }
    }

    public synchronized void cancelTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status == TaskStatus.RUNNING) {
            t.status = TaskStatus.CANCELLED;
            if (t.agent != null){
                t.agent.stop();     // 停真正的 Agent
                t.agent=null;
            }
            if (t.thread != null){
                t.thread.interrupt(); // 停事件消费线程
                t.thread=null;
            }
            notifications.add(new TaskNotification(id, t.name, TaskStatus.CANCELLED, ""));
        }
    }
    //父Agent定期拉取
    public synchronized List<TaskNotification> drainNotifications() {
        var result = new ArrayList<>(notifications);
        notifications.clear();
        return result;
    }

    public synchronized Task getTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t == null) return null;
        return new Task(t.id, t.name, t.status, t.output, t.error);
    }

    public synchronized List<Task> listTasks() {
        return tasks.values().stream()
                .map(t -> new Task(t.id, t.name, t.status, t.output, t.error))
                .toList();
    }
    //子Agent静默超过这个时间就判定为卡死(5分钟:长构建/长测试期间工具不产生任何事件)
    private static final long IDLE_TIMEOUT_SECONDS = 300;
    //被放弃任务的收尾等待上限,见 drainRemaining
    private static final long DRAIN_GRACE_SECONDS = 10;

    /**
     * 按照SubAgentSpec生产SubAgent
     * 预定义SubAgentSpec的Agent parentState=null，因为普通子Agent没有共享历史
     * fork时需要复制父级的工具结果裁剪决策日志，使父子对继承的tool_use_id 做出一致的裁剪决策，保证prompt cache前缀一致。
     */
    public String spawnSubAgent(
            LlmClient client,
            ToolRegistry registry,
            ProviderConfig cfg,
            SubAgentSpec spec,
            //prompt是任务描述
            String prompt
    ) {
        //普通子Agent自己独立的前置工作:按spec裁剪工具 + 起一个没有父历史的空对话
        ToolRegistry subRegistry = ToolFilter.filterForAgent(registry, spec);
        var subAgent = new Agent(client, subRegistry, cfg);
        int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
        subAgent.setMaxIterations(maxTurns);

        var conv = new ConversationManager();
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            conv.addSystemReminder(spec.systemPromptOverride());
        }
        //添加任务prompt
        conv.addUserMessage(prompt);

        String taskId = createTask(spec.name() + ": " + truncate(prompt, 50));
        //前置工作做完,剩下的交给公共后半段
        return spawnBackground(taskId, subAgent, conv);
    }

    /**
     * Fork 专用：用已经 clone 好的注册表（不再过滤）生成后台子 Agent。
     * 与 {@link #spawnSubAgent} 的区别：
     * 1.跳过 ToolFilter，maxTurns 固定 200。
     * 2.拥有父Agent的完整对话历史(forkConv为父Agent的Conv的复制版)与裁决记录ContentReplacementState复制版
     */
    public String spawnForkAgent(
            LlmClient client,
            ToolRegistry registry,
            ProviderConfig cfg,
            String taskLabel,
            ConversationManager forkConv,
            ContentReplacementState forkParentState
    ) {
        //fork自己独立的前置工作:不调 ToolFilter.filterForAgent——fork 直接沿用 cloneForFork 产生的完整注册表,并带父对话的复制版
        var subAgent = new Agent(client, registry, cfg);
        subAgent.setMaxIterations(200);
        if (forkParentState != null) {
            subAgent.setReplacementState(forkParentState);
        }

        String taskId = createTask("fork: " + truncate(taskLabel, 50));
        //前置工作做完,剩下的交给公共后半段
        return spawnBackground(taskId, subAgent, forkConv);
    }

    /**
     * spawnSubAgent / spawnForkAgent 的公共后半段:两条线各自把"用哪个Agent、配哪个对话"准备好之后,都从这里进来。
     * 它做三件事:登记台账 → 起后台消费线程 → 返回taskId让父Agent继续干活。
     */
    private String spawnBackground(String taskId, Agent subAgent, ConversationManager conv) {
        //在父线程里登记,而不是等worker线程跑起来再补登记:cancelTask只处理RUNNING的任务,
        //而setRunning在start()之前就把状态置成RUNNING并记下消费线程,
        //所以父Agent拿到taskId那一刻,t.agent与t.thread必须都已经在台账里,否则cancelTask的两个stop会同时打空
        attachAgent(taskId, subAgent);
        Thread thread = Thread.ofVirtual().unstarted(() -> consume(taskId, subAgent, conv));
        setRunning(taskId, thread);//之后cancelTask可用
        thread.start();
        return taskId;
    }

    /**
     * 后台消费线程主体:先守好"取消发生在run()之前"这道门,再消费子Agent的事件流,结束时把终态写回台账。
     * 两条线(普通子Agent / fork)只有前面的准备不同,事件处理完全一样,所以只有这一份。
     */
    private void consume(String taskId, Agent subAgent, ConversationManager conv) {
        var output = new StringBuilder();
        //产生了两个线程,1个外部的消费线程及1个内部的AgentLoop线程
        //如果父Agent在run()之前就调用cancelTask，worker线程的interrupt标志会被设置，subAgent会被调用stop不过还没有run，agentThread=null，no op
        //subAgent.run()正常启动
        BlockingQueue<AgentEvent> queue;
        //守卫与cancelTask共用同一把锁(监视器this):要么cancelTask在守卫之前执行完(这里直接返回,Agent根本不启动),
        //要么cancelTask被这把锁挡在守卫之后(那时subAgent.run已经发布了agentThread,agent.stop()一定打得到目标)
        synchronized (this){
            if (Thread.currentThread().isInterrupted()) {
                subAgent.stop();
                setFailed(taskId, "Interrupted");
                return;
            }else{
                queue =subAgent.run(conv);
            }
        }
        //生产者(内层AgentLoop)是否已经自行收尾:收到LoopComplete就说明它后面不会再往队列里放事件了
        boolean producerDone = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                AgentEvent event;
                try {
                    event = queue.poll(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setFailed(taskId, "Interrupted");
                    return;
                }
                if (event == null) {
                    setFailed(taskId, "Timeout");
                    return;
                }

                switch (event) {
                    case AgentEvent.StreamText st -> output.append(st.text());
                    case AgentEvent.ErrorEvent err -> {
                        //普通子Agent的处理理由:
                        //有可能发了ErrorEvent后在进行compact或者rate_limit恢复
                        //SubAgent直接把它当做错误处理,因为subagent没有那么长的上下文以及速率达到上限也不应该继续用subagent
                        //fork的处理理由:
                        //有可能发了ErrorEvent后在进行compact或者rate_limit恢复
                        //ForkAgent不允许在进行错误恢复，rate_limit不应该用子Agent、too long context应该在父Agent中compact后再传,避免压缩两次
                        setFailed(taskId, err.message());
                        return;
                    }
                    case AgentEvent.RetryEvent r -> {
                        //RetryEvent是子Agent正在自己做错误恢复(too long context→compact、rate_limit→等待、max_tokens→续写),
                        //too long context→compact、rate_limit已在ErrorEvent中处理，此处是在处理max_tokens
                        setFailed(taskId, "Sub-agent aborted on retry: " + r.reason());
                        return;
                    }
                    //只在父Agent做压缩
                    case AgentEvent.CompactEvent c->{
                        setFailed(taskId, "Sub-agent aborted: its context needed compaction. Compact this conversation (or split the task) and retry. " );
                        return;
                    }
                    case AgentEvent.LoopComplete lc -> {
                        //正常结束发 LoopComplete(n>0),异常/中断/超限在 finally 里发 LoopComplete(0)
                        producerDone = true;
                        if (lc.totalTurns() > 0) {
                            setCompleted(taskId, output.toString().isEmpty()
                                    ? "(agent produced no output)" : output.toString());
                        } else {
                            setFailed(taskId, "Agent ended without completing");
                        }
                        return;
                    }

                    default -> {}
                }
            }
            //循环静默退出:中断落在"处理事件"的过程中,没有走任何return,终态就没人写
            setFailed(taskId, "Interrupted");
        }
        catch (RuntimeException e) {
            //消费体自己抛异常也要留终态,否则任务永远停在RUNNING、父Agent等不到通知
            setFailed(taskId, "Consumer error: " + e.getMessage());
        }
        finally {
            //兜底:取消/超时/错误路径下生产者可能还活着,停掉它并把残留事件读干净
            if (!producerDone) {
                subAgent.stop();
                drainRemaining(queue);
            }
        }
    }

    /**
     * 陪生产者走完最后一程:消费者一旦return就再没人读队列,而队列容量只有64,
     * 生产者(内层AgentLoop)若还活着,填满后就会永久park在putSafe上——一个虚拟线程就这么吊着
     * 整份对话(fork场景还吊着cloneForFork出来的注册表克隆),而且期间还在花token。
     * 所以这里牺牲一点等待时间继续读,直到它发出LoopComplete(说明内层线程已经退出)或超过等待上限。
     * 不能只调queue.clear():清空只是腾出空间,生产者马上又会填满,挡不住它继续跑。
     */
    private static void drainRemaining(BlockingQueue<AgentEvent> queue) {
        //取消路径下消费线程自己正带着中断标志,不先清掉的话poll会立刻抛InterruptedException,排空就做不成
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_GRACE_SECONDS);
            while (System.nanoTime() < deadline) {
                AgentEvent tail;
                try {
                    tail = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                //LoopComplete是生产者的最后一条事件,收到它就说明内层线程已经退出
                if (tail instanceof AgentEvent.LoopComplete) return;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
