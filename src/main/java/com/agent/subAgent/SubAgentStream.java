
package com.agent.subAgent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 消费子Agent事件流的两条公共约定:后台台账(SubAgentTaskManager.consume)与同步调用(AgentTool.runSync)共用,
 * 免得同一条规则在两处各写一遍、改一处漏一处。
 *
 * 1.空闲超时:工具执行期间(比如跑一次几分钟的构建)子Agent不产生任何事件,
 *   所以"静默"的判定阈值必须足够宽,否则正常的慢工具会被误判成卡死。
 * 2.收尾排空:消费者一旦不再读队列,生产者(内层AgentLoop)填满容量64的队列后会永久park在putSafe上,
 *   一个虚拟线程就那么吊着整份对话(fork场景还吊着cloneForFork出来的注册表克隆),而且期间还在花token。
 */
final class SubAgentStream {

    private SubAgentStream() {}

    /** 多久收不到任何事件就判定卡死(5分钟:长构建/长测试期间工具不产生任何事件)。 */
    static final long IDLE_TIMEOUT_SECONDS = 300;

    /** 放弃任务后陪生产者收尾的等待上限,见 {@link #stopAndDrain}。 */
    private static final long DRAIN_GRACE_SECONDS = 10;

    /**
     * 停掉生产者并把残留事件读干净:一直读到它发出LoopComplete(说明内层线程已经退出)或超过等待上限。
     * 不能只调queue.clear():清空只是腾出空间,生产者马上又会填满,挡不住它继续跑。
     */
    static void stopAndDrain(Agent agent, BlockingQueue<AgentEvent> queue) {
        agent.stop();
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
}
