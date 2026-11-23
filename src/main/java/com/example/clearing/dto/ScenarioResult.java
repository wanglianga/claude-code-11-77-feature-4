package com.example.clearing.dto;

import java.util.ArrayList;
import java.util.List;

/** 验收场景执行结果：步骤轨迹 + 断言结论 */
public class ScenarioResult {

    public String name;
    public boolean ok = true;
    public List<String> steps = new ArrayList<>();
    public List<String> assertions = new ArrayList<>();

    public ScenarioResult(String name) {
        this.name = name;
    }

    public void step(String step) {
        steps.add(step);
    }

    public void check(boolean cond, String message) {
        assertions.add((cond ? "PASS" : "FAIL") + ": " + message);
        if (!cond) {
            ok = false;
        }
    }

    public String getName() {
        return name;
    }

    public boolean isOk() {
        return ok;
    }

    public List<String> getSteps() {
        return steps;
    }

    public List<String> getAssertions() {
        return assertions;
    }
}
