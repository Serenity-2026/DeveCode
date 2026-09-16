
package com.agent.subAgent;


import com.agent.agent.Agent;
import com.agent.agent.AgentDeps;
import com.agent.agent.AgentEvent;
import com.agent.config.ProviderConfig;
import com.agent.history.ConversationManager;
import com.agent.llm.LlmClient;
import com.agent.llm.Message;
import com.agent.llm.ToolResultBlock;
import com.agent.llm.ToolUseBlock;
import com.agent.teams.SpawnDispatcher;
import com.agent.teams.TeamManager;
import com.agent.teams.TaskTools;
import com.agent.teams.TeamTools;
import com.agent.teams.TeammateRunner;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.result.ContentReplacementState;
import com.agent.tool.result.ToolResult;
import com.agent.worktree.AgentWorktree;
import com.agent.worktree.WorktreeChanges;
import com.agent.worktree.WorktreeManager;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 父Agent眼里根本没有"子Agent"这个概念，它只看到工具池里多了一个叫Agent的工具。
 * 模型的工具调用落到 AgentTool.execute()，由它决定"这次要造哪种子 Agent、怎么造、在哪跑、跑完怎么还给父 Agent"。所以这个类是subAgent包唯一的对外门面
 */
public class AgentTool implements Tool {
    //复用父llmClient
    private final LlmClient client;
    //subAgent和forkAgent的tool registry都从父agent toolRegistry中派生
    private final ToolRegistry parentRegistry;
    private final String protocol;
    private final ProviderConfig providerConfig;

    /** Optional: 将模型别名映射为实例. */
    private Function<String, LlmClient> modelResolver;

    /** Optional: loaded agent definitions (builtins + user + project). */
    private Map<String, SubAgentSpec> agentSpecs;

    /** Optional: receives progress events while the sub-agent runs. */
    private Consumer<SubAgentProgress> progressListener;

    /** Optional: task manager for background agent execution. */
    private SubAgentTaskManager taskManager;

    /** Optional: parent conversation for fork support. */
    private ConversationManager parentConversation;

    /** Optional: worktree manager for isolation mode. */
    private WorktreeManager worktreeManager;

    /** Optional: team manager for team_name registration. */
    private TeamManager teamManager;

    /** Optional: 队友 Agent 的依赖套装（权限裁决 / hook / 文件历史 / 指令 / 记忆 / skill / 迭代上限） */
    private AgentDeps teammateDeps;

    /**
     * 队友的权限询问出口（接 TUI 弹窗）。缺它时队友的 ASK 只能等 5 分钟超时被默认拒绝——
     * 表现为"队友去写文件了，然后什么都没发生"。
     */
    private TeammateRunner.PermissionAsker teammatePermissionAsker;

    /** 队友的结构化问卷出口（AskUserQuestion，同样接 TUI）。 */
    private TeammateRunner.QuestionAsker teammateQuestionAsker;

    /** 标识当前 AgentTool 的生成上下文；fork 子 Agent 中会被设为 FORK_QUERY_SOURCE */
    private String querySource = "";

    private static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    private static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. Final report must be under 500 characters, starting with "Scope:".
            </fork_boilerplate>""";

    /** fork 子 Agent 的 querySource 标记值，用于运行时拦截嵌套 fork */
    private static final String FORK_QUERY_SOURCE = "agent:builtin:fork";

    /**
     * 子 Agent 允许自己压缩几次上下文；超过就认为"任务对它太大"或"在原地打转"，
     * 交回父 Agent 拆小重试。见 runSync 里 CompactEvent 分支的说明。
     */
    private static final int MAX_SUBAGENT_COMPACTIONS = 2;

    public AgentTool(LlmClient client, ToolRegistry parentRegistry, String protocol,
                     ProviderConfig providerConfig) {
        this.client = client;
        this.parentRegistry = parentRegistry;
        this.protocol = protocol;
        this.providerConfig = providerConfig;
    }

    public void setModelResolver(Function<String, LlmClient> modelResolver) {
        this.modelResolver = modelResolver;
    }

    public void setAgentSpecs(Map<String, SubAgentSpec> agentSpecs) {
        this.agentSpecs = agentSpecs;
    }

    public void setProgressListener(Consumer<SubAgentProgress> progressListener) {
        this.progressListener = progressListener;
    }

    public void setTaskManager(SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public SubAgentTaskManager getTaskManager() {
        return taskManager;
    }

    public void setParentConversation(ConversationManager parentConversation) {
        this.parentConversation = parentConversation;
    }


    private ContentReplacementState parentReplacementState;

    public void setParentReplacementState(ContentReplacementState state) {
        this.parentReplacementState = state;
    }

    public void setWorktreeManager(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    public void setTeamManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    public void setTeammateDeps(AgentDeps teammateDeps) {
        this.teammateDeps = teammateDeps;
    }

    public void setTeammatePermissionAsker(com.agent.teams.TeammateRunner.PermissionAsker asker) {
        this.teammatePermissionAsker = asker;
    }

    public void setTeammateQuestionAsker(com.agent.teams.TeammateRunner.QuestionAsker asker) {
        this.teammateQuestionAsker = asker;
    }

    public String getQuerySource() { return querySource; }
    public void setQuerySource(String querySource) { this.querySource = querySource; }

    /**
     * 浅复制当前 AgentTool 并设置新的 querySource。
     * fork 用它来标记子 Agent 的 AgentTool，使嵌套 fork 在调用时被拦截。
     */
    public AgentTool cloneWithQuerySource(String qs) {
        AgentTool clone = new AgentTool(this.client, this.parentRegistry, this.protocol, this.providerConfig);
        clone.modelResolver = this.modelResolver;
        clone.agentSpecs = this.agentSpecs;
        clone.progressListener = this.progressListener;
        clone.taskManager = this.taskManager;
        clone.parentConversation = this.parentConversation;
        clone.worktreeManager = this.worktreeManager;
        clone.teamManager = this.teamManager;
        clone.teammateDeps = this.teammateDeps;
        clone.teammatePermissionAsker = this.teammatePermissionAsker;
        clone.teammateQuestionAsker = this.teammateQuestionAsker;
        clone.parentReplacementState = this.parentReplacementState;
        clone.querySource = qs;
        return clone;
    }

    // ---- Tool interface ----

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        var sb = new StringBuilder();
        sb.append("Launch a sub-agent to handle a complex task. Each agent runs independently ");
        sb.append("with its own context.\n\n");
        sb.append("Use this when a task benefits from focused, isolated work -- e.g., ");
        sb.append("researching a question, implementing a component, or reviewing code. ");
        sb.append("The sub-agent cannot see the current conversation.\n\n");
        sb.append("Available agent types:");

        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            for (String name : AgentLoader.listNames(agentSpecs)) {
                SubAgentSpec spec = agentSpecs.get(name);
                sb.append("\n- ").append(name).append(": ").append(spec.description());
            }
        } else {
            sb.append("\n- general-purpose: Full tool access for multi-step tasks (default)");
            sb.append("\n- plan: Read-only tools for designing implementation plans");
            sb.append("\n- explore: Read-only search agent for locating code");
        }

        sb.append("\n\nWrite a detailed prompt explaining what the agent should do and why ");
        sb.append("-- it has no prior context.");
        return sb.toString();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        List<String> agentTypes;
        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            agentTypes = AgentLoader.listNames(agentSpecs);
        } else {
            agentTypes = List.of("general-purpose", "plan", "explore");
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of(
                "type", "string",
                "description", "A short (3-5 word) description of the task"
        ));
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "The task for the agent to perform. Be detailed -- the agent has no context from this conversation."
        ));
        properties.put("subagent_type", Map.of(
                "type", "string",
                "enum", agentTypes,
                "description", "The type of agent to use. Defaults to general-purpose."
        ));
        properties.put("model", Map.of(
                "type", "string",
                "enum", List.of("sonnet", "opus", "haiku"),
                "description", "Override the model for this agent. Defaults to the parent's model."
        ));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "Set to true to run the agent in the background."
        ));
        properties.put("isolation", Map.of(
                "type", "string",
                "enum", List.of("worktree"),
                "description", "Isolation mode. 'worktree' creates a temporary git worktree."
        ));
        properties.put("team_name", Map.of(
                "type", "string",
                "description", "REQUIRED when creating team members. Spawns the agent as a long-running "
                        + "teammate under this team (created via TeamCreate). Unlike regular sub-agents, team "
                        + "members run in their own terminal, persist after the lead returns, and communicate "
                        + "with each other via SendMessage. Without team_name the agent runs as a one-shot "
                        + "sub-agent that blocks and returns inline."
        ));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("description", "prompt"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", inputSchema);
        return schema;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    /**
     * @param args:name       是否必填           描述
     *            description:是,3-5 词任务摘要，主要用于给父 Agent 自己看的结果文案
     *            prompt:是,任务正文，子 Agent 对话里唯一的那条 user 消息
     *            subagent_type:否,选哪种预定义模板；不填 = 走 fork
     *            model:否,覆盖模型，值域靠 modelResolver 解释
     *            isolation:否,目前只有 "worktree" 有意义
     *            run_in_background:否,默认 false
     *            team_name:否,有值就走 teammate 路径
     * @return
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String description = getStringArg(args, "description");
        String prompt = getStringArg(args, "prompt");
        if (description == null || description.isEmpty() || prompt == null || prompt.isEmpty()) {
            return ToolResult.error("Error: description and prompt are required");
        }

        String subagentType = getStringArg(args, "subagent_type");
        String modelOverride = getStringArg(args, "model");
        String isolation = getStringArg(args, "isolation");
        String teamName = getStringArg(args, "team_name");
        try {
        // Team-member path: check BEFORE fork/subagent so team_name is never skipped,subagent_type缺省时用general-purpose兜底
        if (teamName != null && !teamName.isEmpty() && teamManager != null) {
            SubAgentSpec spec = (subagentType != null && !subagentType.isEmpty())
                    ? resolveSpec(subagentType) : resolveSpec("general-purpose");
            if (spec == null) spec = resolveSpec("general-purpose");
            return runAsTeammate(spec, teamName, description, prompt, modelOverride, isolation);
        }

            // Fork path: no subagent_type specified.
            if (subagentType == null || subagentType.isEmpty()) {
                return runFork(description, prompt, modelOverride);
            }

        // Resolve the spec,如果使用了预定义外的Agent,返回可用子Agent列表
        SubAgentSpec spec = resolveSpec(subagentType);
        if (spec == null) {
            String available = (agentSpecs != null)
                    ? String.join(", ", AgentLoader.listNames(agentSpecs))
                    : "general-purpose, plan, explore";
            return ToolResult.error(
                    "Error: unknown agent type '%s'. Available: %s".formatted(subagentType, available));
        }

        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));

        if (runInBackground) {
            return runAsync(spec, description, prompt, modelOverride, isolation);
        }
        return runSync(spec, description, prompt, modelOverride, isolation);
        }
        catch (Exception ie){
            return ToolResult.error("agent tool execute error:"+ie.getMessage());
        }
    }

    // ---- Internal ----

    private ToolResult runAsync(SubAgentSpec spec, String description, String prompt,
                                String modelOverride, String isolation) {
        if (taskManager == null) {
            return ToolResult.error("Background execution not available (no task manager configured)");
        }
        LlmClient subClient = selectClient(spec.model(), modelOverride);

        //后台子Agent会和父Agent(以及别的子Agent)并行改同一份工作区,所以它比同步路径更需要隔离:
        //这里为它单开一棵worktree。收尾不在这里做——任务在别的线程里结束,由台账在终态时回调onFinish
        AgentWorktree.Result wtResult = null;
        SubAgentTaskManager.WorktreeInfo worktree = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                wtResult = createAgentWorktree();
                prompt = worktreeNotice(wtResult) + "\n\n" + prompt;
                final AgentWorktree.Result wt = wtResult;
                worktree = new SubAgentTaskManager.WorktreeInfo(
                        wt.worktreePath(), () -> cleanupWorktree(wt));
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        try {
            String taskId = taskManager.spawnSubAgent(
                    subClient, parentRegistry, providerConfig, spec, prompt, worktree);
            return ToolResult.success(
                    "Agent \"%s\" launched in background (task %s)%s. You will be notified when it completes."
                            .formatted(description, taskId,
                                    worktree == null ? "" : " in an isolated worktree"));
        } catch (RuntimeException e) {
            //台账都没登记上,就没有人会来给这棵树收尾了,只能在这里就地收拾
            return ToolResult.error("Error launching background agent: " + e.getMessage()
                    + (wtResult == null ? "" : cleanupWorktree(wtResult)));
        }
    }

    /**
     * fork 的全部价值是"继承父历史 + 父工具池，在同一份工作区里和父并行干活",所以没有考虑worktree
     */
    private ToolResult runFork(String description, String prompt, String modelOverride) throws IOException {
        if (parentConversation == null) {
            return ToolResult.error("Error: fork requires parent conversation context");
        }
        if (taskManager == null) {
            return ToolResult.error("Error: fork requires task manager for background execution");
        }

        // 主检测：querySource 标记（压缩安全，对话历史被摘要后仍可检测）
        if (FORK_QUERY_SOURCE.equals(querySource)) {
            return ToolResult.error("Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.");
        }

        // Build forked conversation: copy parent messages + append fork boilerplate + task
        ConversationManager forkedConv = buildForkedConversation(parentConversation, prompt);

        LlmClient subClient = selectClient(null, modelOverride);
        // fork 继承父 Agent 的完整工具池，确保子 Agent 拥有相同的工具能力；
        // AgentTool 实例的 querySource 被标记以拦截嵌套
        ToolRegistry forkedRegistry = ToolFilter.cloneForFork(parentRegistry);
        String taskId = taskManager.spawnForkAgent(
                subClient, forkedRegistry,  providerConfig,
                prompt,
                forkedConv,
                parentReplacementState.copy());

        return ToolResult.success(
                "Forked agent \"%s\" launched in background (task %s). Results will arrive via task-notification."
                        .formatted(description, taskId));
    }

    /**
     * 给subAgent一份新的cm副本:
     * assistant带tool_uses但没有tool_results：这是"父刚好停在工具调用中间"的半截状态，API要求每个tool_use必有配对结果，所以先补一条 (tool execution interrupted by fork) 的占位结果；。
     * 最后追加 FORK_BOILERPLATE + "\n\nYour task:\n" + prompt。
     * @return
     */
    private static ConversationManager buildForkedConversation(ConversationManager parent, String task) throws IOException {
        ConversationManager forked = new ConversationManager(parent);
        Message lastMessage = parent.getMessages().getLast();
        boolean hasAgentTool=false;
        for (ToolUseBlock toolUs : lastMessage.getToolUses()) {
            if ("Agent".equals(toolUs.toolName())) {
                hasAgentTool = true;
                break;
            }
        }
        if(hasAgentTool){
            //为最后一条message的所有工具调用构造占位符
            var placeholders = lastMessage.getToolUses().stream()
                    .map(tu -> new ToolResultBlock(
                            tu.toolId(), "(tool execution interrupted by fork)", false))
                    .toList();
            forked.addToolResultsMessage(placeholders);
            forked.addUserMessage(FORK_BOILERPLATE + "\n\nYour task:\n" + task);
        }
        else throw new IOException("don't have agent tool use ");
        return forked;
    }

    private ToolResult runSync(SubAgentSpec spec, String description, String prompt, String modelOverride, String isolation) {
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        LlmClient subClient = selectClient(spec.model(), modelOverride);

        Agent subAgent = new Agent(subClient, subRegistry,  providerConfig);
        int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
        subAgent.setMaxIterations(maxTurns);

        // Worktree isolation via AgentWorktree API
        AgentWorktree.Result wtResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                wtResult = createAgentWorktree();
                subAgent.setWorkDir(wtResult.worktreePath());
                //告诉子Agent它在一个隔离树里工作,继承来的路径要翻译过来
                prompt = worktreeNotice(wtResult) + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        ConversationManager conv = new ConversationManager();
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            conv.addSystemReminder(spec.systemPromptOverride());
        }
        conv.addUserMessage(prompt);

        long startNanos = System.nanoTime();
        var output = new StringBuilder();
        int toolCount = 0;

        BlockingQueue<AgentEvent> queue = subAgent.run(conv);

        //失败原因,null表示成功:终态只在循环外处理一次,避免每个分支各写一套收尾
        String failure = null;
        //生产者(内层AgentLoop)是否已经自行收尾:收到LoopComplete就说明它后面不会再往队列里放事件了
        boolean producerDone = false;
        //子Agent自己压缩过几次上下文(压缩是就地重写它自己的对话,父Agent看不到那份上下文)
        int compactions = 0;
        try {
            loop:
            while (!Thread.currentThread().isInterrupted()) {
                AgentEvent event;
                try {
                    event = queue.poll(SubAgentStream.IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = "Agent interrupted";
                    break;
                }
                if (event == null) {
                    failure = "Agent timed out waiting for events";
                    break;
                }

                switch (event) {
                    case AgentEvent.StreamText st -> output.append(st.text());

                    case AgentEvent.ToolResultEvent tre -> {
                        toolCount++;
                        emitProgress(description, spec.name(), tre.toolName(), tre.output(),
                                tre.isError(), false, toolCount, elapsedSeconds(startNanos));
                    }

                    case AgentEvent.ErrorEvent err -> {
                        //SubAgent直接把它当做错误处理,因为subagent没有那么长的上下文以及速率达到上限也不应该继续用subagent
                        failure = "Agent failed: " + err.message();
                        break loop;
                    }

                    case AgentEvent.RetryEvent r -> {
                        //RetryEvent是子Agent正在自己做错误恢复(too long context→compact、rate_limit→等待、max_tokens→续写),
                        //too long context→compact、rate_limit已在ErrorEvent中处理，此处是在处理max_tokens
                        failure = "Agent aborted on retry: " + r.reason();
                        break loop;
                    }

                    case AgentEvent.CompactEvent c -> {
                        // 这里原来直接判死,但那是误判:
                        // ContextCompactor.manage() 是"就地压缩子 Agent 自己的对话"并返回报告,
                        // 子 Agent 发完这个事件会继续跑;而且它的上下文根本不在父 Agent 的对话里,
                        // 让父 Agent 去 compact 解决不了任何问题——只会白白丢掉已经干完的活。
                        // 所以这里只计数:偶尔压一次很正常,反复压缩才说明任务太大或在原地打转。
                        compactions++;
                        if (compactions > MAX_SUBAGENT_COMPACTIONS) {
                            failure = "Agent compacted its context " + compactions
                                    + " times — the task is too large (or the agent is looping). "
                                    + "Split it into smaller sub-tasks and retry.";
                            break loop;
                        }
                    }

                    case AgentEvent.LoopComplete lc -> {
                        //正常结束发 LoopComplete(n>0),异常/中断/超限在 finally 里发 LoopComplete(0)
                        producerDone = true;
                        if (lc.totalTurns() <= 0) {
                            failure = "Agent ended without completing";
                        }
                        break loop;
                    }

                    default -> {
                        // ThinkingText, ThinkingComplete, ToolUseEvent, TurnComplete, UsageEvent, etc.
                        // -- consumed but not surfaced to the parent
                    }
                }
            }
            // 循环静默退出：中断落在"处理事件"的过程中，上面任何一个分支都没走。
            // 必须排除"正常收尾"：LoopComplete 那条路是 break loop 出来的，producerDone 已经置位，
            // 此时 failure 本来就该保持 null 表示成功。以前这里不看 producerDone，
            // 于是每一次正常完成的同步子 Agent 都会被改写成失败（"Agent interrupted"）。
            if (failure == null && !producerDone) {
                failure = "Agent interrupted";
            }
        }
        catch (RuntimeException e) {
            //消费体自己抛异常也要给父Agent一个结论,否则这次工具调用永远不返回
            failure = "Agent consumer error: " + e.getMessage();
        }
        finally {
            //唯一的停止点:不管从哪条路径离开(成功/失败/中断/异常),都保证生产者被停、残留事件读干净
            if (!producerDone) {
                SubAgentStream.stopAndDrain(subAgent, queue);
            }
        }

        double totalTime = elapsedSeconds(startNanos);
        if (failure != null) {
            emitProgress(description, spec.name(), true, true, toolCount, totalTime);
            return ToolResult.error(failure + cleanupWorktree(wtResult));
        }

        emitProgress(description, spec.name(), false, true, toolCount, totalTime);
        String result = output.toString();
        if (result.isEmpty()) {
            result = "(agent produced no output)";
        }
        long elapsedMs = Math.round(totalTime * 1000);
        return ToolResult.success(
                "Agent \"%s\" completed in %d.%03ds.\n\n%s%s".formatted(
                        description, elapsedMs / 1000, elapsedMs % 1000, result, cleanupWorktree(wtResult)));
    }

    /**
     * Worktree收尾(fail-closed):有未提交改动或新提交 → 留下并告知路径;完全干净 → 删掉。
     * 成功与失败两条路都要走,否则每次失败都会在磁盘上留下一个没人管的worktree。
     */
    private String cleanupWorktree(AgentWorktree.Result wtResult) {
        if (wtResult == null) return "";
        if (WorktreeChanges.hasChanges(wtResult.worktreePath(), wtResult.headCommit())) {
            return "\n\nWorktree kept at %s (branch %s) — has uncommitted changes or new commits."
                    .formatted(wtResult.worktreePath(), wtResult.worktreeBranch());
        }
        AgentWorktree.remove(wtResult.worktreePath(), wtResult.worktreeBranch(), wtResult.gitRoot());
        return "";
    }

    /**
     * 建一棵子Agent专用的隔离树:随机slug避免并发撞名(同步与后台两条路共用这一段)。
     */
    private AgentWorktree.Result createAgentWorktree() throws Exception {
        byte[] rndBytes = new byte[4];
        new SecureRandom().nextBytes(rndBytes);
        String slug = "agent-a" + HexFormat.of().formatHex(rndBytes).substring(0, 7);
        return AgentWorktree.create(slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
    }

    /**
     * 拼"你在隔离树里、继承来的路径要翻译成新根"的说明,放在prompt最前面。
     * 第一个参数是仓库根(项目根),不是进程工作目录——要翻译的是父的路径前缀。
     */
    private String worktreeNotice(AgentWorktree.Result wt) {
        return AgentWorktree.buildNotice(worktreeManager.getProjectRoot(), wt.worktreePath());
    }

    /**
     * 根据SubAgent的name返回具体实例
     */
    private SubAgentSpec resolveSpec(String name) {
        if (agentSpecs != null) {
            return agentSpecs.get(name);
        }
        return switch (name) {
            case "general-purpose" -> SubAgentSpec.GENERAL_PURPOSE;
            case "plan" -> SubAgentSpec.PLAN;
            case "explore" -> SubAgentSpec.EXPLORE;
            default -> null;
        };
    }


    private ToolResult runAsTeammate(SubAgentSpec spec, String teamName,
                                     String description, String prompt, String modelOverride, String isolation) {
        var team = teamManager.getTeam(teamName);
        //验证是否有这个团队
        if (team == null) {
            return ToolResult.error("Error: team '%s' not found. Create it first with TeamCreate.".formatted(teamName));
        }
        //使用desc创建队员名
        String memberName = description.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")     // 非字母数字统一变成连字符（顺带干掉 Windows 非法字符）
                .replaceAll("^-+|-+$", "");        // 去掉首尾连字符
        if (memberName.isEmpty()) memberName = "teammate";
        if (memberName.length() > 30) memberName = memberName.substring(0, 30);
        int suffix = 2;
        String base = memberName;
        while (team.hasMember(memberName)) {
            memberName = base + "-" + suffix++;
        }

        // isAsync=true + isInProcessTeammate=true：套上"队友可用工具"白名单——
        // 队友能读写/搜索/Bash/Skill/任务板，但拿不到 TeamCreate / TeamDelete / Agent（避免队伍自我繁殖）
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec, true, false, true);
        // Add coordination tools for teammates
        subRegistry.register(new TeamTools.SendMessageTool(teamManager, memberName));
        // 任务板工具必须按"队友身份"重挂一遍：ToolFilter 复用父注册表里的同一个 Tool 实例，
        // 直接继承会让 createdBy 记成 lead、团队解析也按 lead 去找（多队时直接报 no team found）
        subRegistry.register(new TaskTools.TaskCreateTool(teamManager, memberName));
        subRegistry.register(new TaskTools.TaskListTool(teamManager, memberName));
        subRegistry.register(new TaskTools.TaskGetTool(teamManager, memberName));
        subRegistry.register(new TaskTools.TaskUpdateTool(teamManager, memberName));

        LlmClient subClient = selectClient(spec.model(), modelOverride);

        // Gather peer names for addendum
        var otherMembers = team.memberNames();

        //告诉队友"你是谁、队友有谁"
        String addendum = TeammateRunner.buildTeammateAddendum(
                teamName, memberName, otherMembers);

        // Optional worktree isolation
        String workdir = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                // 复用现成 helper：随机 slug + 仓库根 + 软链目录；notice 也按"仓库根 → 隔离树"翻译
                // （原来这里拿 user.dir 当第一参数，从子目录启动时路径会翻译错位）
                var wtResult = createAgentWorktree();
                workdir = wtResult.worktreePath();
                prompt = worktreeNotice(wtResult) + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating teammate worktree: " + e.getMessage());
            }
        }

        // Spawn teammate
        try {
            var spawnResult = SpawnDispatcher.spawnTeammate(
                    new SpawnDispatcher.SpawnConfig(
                            team, memberName, prompt, addendum,
                            subClient, subRegistry,  providerConfig, workdir, teammateDeps,
                            teammatePermissionAsker, teammateQuestionAsker));

            return ToolResult.success(
                    "Teammate \"%s\" spawned in team \"%s\" (mode: %s). The teammate is now working on the assigned task."
                            .formatted(memberName, teamName, spawnResult.mode()));
        } catch (Exception e) {
            return ToolResult.error("Error spawning teammate: " + e.getMessage());
        }
    }

    /**
     * 父llmClient兜底
     */
    private LlmClient selectClient(String specModel, String overrideModel) {
        String model = (overrideModel != null && !overrideModel.isEmpty()) ? overrideModel : specModel;
        if (model == null || model.isEmpty() || "inherit".equals(model)) {
            return client;
        }
        if (modelResolver != null) {
            LlmClient resolved = modelResolver.apply(model);
            if (resolved != null) {
                return resolved;
            }
        }
        return client;
    }

    private void emitProgress(String description, String agentType,
                              boolean isError, boolean done, int toolCount, double totalTime) {
        emitProgress(description, agentType, null, null, isError, done, toolCount, totalTime);
    }

    private void emitProgress(String description, String agentType,
                              String toolName, String toolOutput,
                              boolean isError, boolean done, int toolCount, double totalTime) {
        if (progressListener != null) {
            progressListener.accept(new SubAgentProgress(
                    agentType, description, toolName, toolOutput,
                    isError, done, toolCount, totalTime));
        }
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
