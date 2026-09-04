

package com.agent.command;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 命令运行时上下文,传给handler的参数包
 */
public record CommandContext(
        String args,
        String workDir,
        String model,
        //全部用Supplier/Runnable/Consumer函数而非直接传值，使得CommandContext在创建时不需要复制任何应用状态——handler执行时才求值，
        //永远拿到最新状态。命令层因此完全不依赖TUI/Agent的具体类，实现了依赖倒置：registry只认识函数接口，由调用方（DeveCodeModel）负责装配。
        //简而言之:Supplier类初始化时需要一个有返回值的方法,Consumer类被调用accept传入参数时执行对应逻辑,Runnable直接跑一段代码
        Supplier<String> permissionMode,
        IntSupplier toolCount,
        Supplier<int[]> tokenCount,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo,
        Supplier<List<String>> skillList,
        IntSupplier skillReload,
        Supplier<String> mcpInfo,
        Supplier<String> sandboxStatus,
        Consumer<Integer> sandboxSwitch
) {}

