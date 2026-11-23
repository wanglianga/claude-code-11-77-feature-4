package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.TaskEvent;
import com.example.clearing.repo.TaskEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 决策事件日志：恢复解释器的数据来源 */
@Service
public class EventLog {

    private final TaskEventRepository eventRepository;

    public EventLog(TaskEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** 记录一次决策，并同步任务的 lastDecision/lastReason（任务实体由调用方保存） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(ClearingTask task, Decision decision, String reason) {
        task.setLastDecision(decision);
        task.setLastReason(reason);
        eventRepository.save(new TaskEvent(task.getId(), decision, reason));
    }
}
