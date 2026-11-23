package com.example.clearing.dto;

import java.util.ArrayList;
import java.util.List;

/** 一次调度周期的结果摘要 */
public class TickResult {

    public int promoted;
    public int dispatched;
    public int throttled;
    public int quotaDenied;
    public int waitingDependency;
    public int waitingReceipt;
    public int confirmed;
    public int failed;
    public int escalated;
    public List<String> notes = new ArrayList<>();

    public int getPromoted() {
        return promoted;
    }

    public int getDispatched() {
        return dispatched;
    }

    public int getThrottled() {
        return throttled;
    }

    public int getQuotaDenied() {
        return quotaDenied;
    }

    public int getWaitingDependency() {
        return waitingDependency;
    }

    public int getWaitingReceipt() {
        return waitingReceipt;
    }

    public int getConfirmed() {
        return confirmed;
    }

    public int getFailed() {
        return failed;
    }

    public int getEscalated() {
        return escalated;
    }

    public List<String> getNotes() {
        return notes;
    }
}
