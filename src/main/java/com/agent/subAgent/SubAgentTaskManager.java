
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
import java.util.function.Supplier;


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

    /**
     * 子 Agent 允许自己压缩几次上下文；超过就认为"任务对它太大"或"在原地打转"，
     * 把任务标失败并交回父 Agent 拆小。见 consume() 里 CompactEvent 分支的说明。
     */
    private static final int MAX_SUBAGENT_COMPACTIONS = 2;

    /**
     * 后台子Agent的隔离工作区(目前只有worktree一种),由调用方创建,台账负责在终态收尾:
     * 1. workDir交给子Agent自己——它的session目录、plan文件路径都基于workDir算;
     * 2. onFinish在任务进入终态、生产者确实停下之后调用一次,返回值追加到那条通知里
     *    (比如"worktree保留在X,因为里面还有改动"),否则父Agent永远不知道隔离目录的下场。
     */
    public record WorktreeInfo(String workDir, Supplier<String> onFinish) {}
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
            String prompt,
            //worktree:后台+隔离时的隔离树信息,不需要隔离就传null
            WorktreeInfo worktree
    ) {
        //普通子Agent自己独立的前置工作:按spec裁剪工具 + 起一个没有父历史的空对话
        ToolRegistry subRegistry = ToolFilter.filterForAgent(registry, spec);
        var subAgent = new Agent(client, subRegistry, cfg);
        int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
        subAgent.setMaxIterations(maxTurns);

        //隔离树路径要交给子Agent自己:它的session目录、plan路径都基于workDir算
        if (worktree != null && worktree.workDir() != null) {
            subAgent.setWorkDir(worktree.workDir());
        }

        var conv = new ConversationManager();
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            conv.addSystemReminder(spec.systemPromptOverride());
        }
        //添加任务prompt
        conv.addUserMessage(prompt);

        String taskId = createTask(spec.name() + ": " + truncate(prompt, 50));
        //前置工作做完,剩下的交给公共后半段
        return spawnBackground(taskId, subAgent, conv, worktree);
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
        return spawnBackground(taskId, subAgent, forkConv, null);
    }

    /**
     * spawnSubAgent / spawnForkAgent 的公共后半段:两条线各自把"用哪个Agent、配哪个对话"准备好之后,都从这里进来。
     * 它做三件事:登记台账 → 起后台消费线程 → 返回taskId让父Agent继续干活。
     */
    private String spawnBackground(String taskId, Agent subAgent, ConversationManager conv, WorktreeInfo worktree) {
        //在父线程里登记,而不是等worker线程跑起来再补登记:cancelTask只处理RUNNING的任务,
        //而setRunning在start()之前就把状态置成RUNNING并记下消费线程,
        //所以父Agent拿到taskId那一刻,t.agent与t.thread必须都已经在台账里,否则cancelTask的两个stop会同时打空
        attachAgent(taskId, subAgent);
        Thread thread = Thread.ofVirtual().unstarted(() -> consume(taskId, subAgent, conv, worktree));
        setRunning(taskId, thread);//之后cancelTask可用
        thread.start();
        return taskId;
    }

    /**
     * 后台消费线程主体:先守好"取消发生在run()之前"这道门,再消费子Agent的事件流,结束时把终态写回台账。
     * 两条线(普通子Agent / fork)只有前面的准备不同,事件处理完全一样,所以只有这一份。
     */
    private void consume(String taskId, Agent subAgent, ConversationManager conv, WorktreeInfo worktree) {
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
                //守门时就发现已被取消:Agent从没启动,但这棵树已经建好了,得有人收尾
                finishWorktree(taskId, worktree);
                return;
            }else{
                queue =subAgent.run(conv);
            }
        }
        //生产者(内层AgentLoop)是否已经自行收尾:收到LoopComplete就说明它后面不会再往队列里放事件了
        boolean producerDone = false;
        //子Agent自己压缩过几次上下文(压缩是就地重写它自己的对话,父Agent看不到那份上下文)
        int compactions = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                AgentEvent event;
                try {
                    event = queue.poll(SubAgentStream.IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
                    case AgentEvent.CompactEvent c->{
                        // 同 AgentTool.runSync：压缩是子 Agent 就地整理自己的对话，它会继续跑，
                        // 而且那份上下文不在父 Agent 的对话里——直接判死只会丢掉已经干完的活。
                        compactions++;
                        if (compactions > MAX_SUBAGENT_COMPACTIONS) {
                            setFailed(taskId, "Sub-agent compacted its context " + compactions
                                    + " times — the task is too large (or the agent is looping). "
                                    + "Split it into smaller sub-tasks and retry.");
                            return;
                        }
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
                SubAgentStream.stopAndDrain(subAgent, queue);
            }
            //隔离资源收尾必须放在最后:生产者停稳了,读到的git状态才是最终状态
            finishWorktree(taskId, worktree);
        }
    }


    /**
     * 隔离资源收尾:把onFinish的说明追加到该任务的通知上。
     * 两条路都要走到——正常结束/失败走consume的finally,而"守门时就发现已被取消"那条路
     * 是在try之前return的,不打这通电话那棵树就没人管了。
     */
    private void finishWorktree(String taskId, WorktreeInfo worktree) {
        if (worktree == null) return;
        String note;
        try {
            note = worktree.onFinish().get();
        } catch (Exception e) {
            //收尾自己失败不能连累消费线程,但也不能装看不见
            note = "\n\n(worktree cleanup failed: " + e.getMessage() + ")";
        }
        appendToNotification(taskId, note);
    }

    /**
     * 把补充信息追加到该任务已发出的那条通知上。
     * 为什么是追加而不是重发:终态(含被cancelTask抢先写的取消)在进finally之前就写好了,
     * 父Agent可能已经读过;重发会让一个任务凭空多出第二条结果,而隔离目录的下场又必须让它看见。
     */
    private synchronized void appendToNotification(String taskId, String note) {
        if (note == null || note.isEmpty()) return;
        for (int i = notifications.size() - 1; i >= 0; i--) {
            var n = notifications.get(i);
            if (n.taskId().equals(taskId)) {
                notifications.set(i, new TaskNotification(n.taskId(), n.name(), n.status(), n.output() + note));
                return;
            }
        }
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
