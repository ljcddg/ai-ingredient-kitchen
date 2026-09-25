package com.apesource.dishservice.config;

import com.apesource.dishservice.ai.AiTaskStatus;
import com.apesource.dishservice.entity.AiGenerateTask;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时清扫上一次没跑完的任务。
 *
 * <p>为什么需要它：队列是<b>内存</b>的，服务一重启就没了，
 * 但 DB 里那些 {@code 排队中 / 生成中} 的记录还在 ——
 * 不处理的话它们会永远停在那个状态，前端一直转圈，用户以为程序坏了。
 *
 * <p>为什么标成"已中断"而不是"自动重新入队"：
 * <ol>
 *   <li>任务可能已经往库里写了一半（比如菜写好了、食材还没写完），重跑会产生重复数据</li>
 *   <li>用户可能早就不需要了（重启期间他自己都忘了提交过什么）</li>
 * </ol>
 * 所以让用户看到"已中断，可重试"，由他决定要不要再来一次 ——
 * 自动重放看起来贴心，实际是在制造脏数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskCleanupRunner implements ApplicationRunner {

    private final AiGenerateTaskMapper taskMapper;

    /**
     * 本进程的启动时刻 —— 只清扫<b>比它更早</b>创建的任务。
     *
     * <p>这个时间条件不是可有可无的优化，而是必需的：
     * {@link ApplicationRunner} 执行在 Tomcat <b>已经监听之后</b>，
     * 所以从「端口能连上」到「清扫跑完」之间存在一个窗口 ——
     * 这期间用户提交的任务会被无差别地扫成「已中断」，明明它才刚入队、正在生成。
     *
     * <p>加了时间条件后，新提交的任务（create_time 晚于启动时刻）不会被误伤。
     * 这个问题是在端到端验证里暴露的：脚本拿到「端口就绪」就立刻提交，
     * 结果状态直接跳到 5（已中断）。
     *
     * <p>注意这个字段有初始化表达式，所以不会被
     * {@code @RequiredArgsConstructor} 收进构造器，不会和注入打架。
     */
    private final LocalDateTime startupTime = LocalDateTime.now();

    @Override
    public void run(ApplicationArguments args) {
        int cleaned = taskMapper.update(null,
                Wrappers.<AiGenerateTask>lambdaUpdate()
                        .in(AiGenerateTask::getStatus,
                                AiTaskStatus.PENDING.getCode(), AiTaskStatus.RUNNING.getCode())
                        .lt(AiGenerateTask::getCreateTime, startupTime)
                        .set(AiGenerateTask::getStatus, AiTaskStatus.INTERRUPTED.getCode())
                        .set(AiGenerateTask::getErrorMsg, "服务重启导致执行中断，请重新提交"));

        if (cleaned > 0) {
            log.warn("启动清扫：{} 个上次未完成的生成任务已标记为「已中断」", cleaned);
        } else {
            log.info("启动清扫：没有需要处理的遗留任务");
        }
    }
}
