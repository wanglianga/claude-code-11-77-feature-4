package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ProfileState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复节流器：保守恢复。
 *  - DOWN → RECOVERING：需探针连续成功达到确认阈值（短暂恢复信号不被追认）；
 *  - DEGRADED → RECOVERING：需连续若干个"有尝试且零失败"的干净周期；
 *  - RECOVERING：生效上限按步长逐周期爬坡（绝不超过声明上限），
 *    期间任何一次真实失败或探测失败都立即回落 DOWN（RECOVERY_ABORTED）；
 *  - 爬坡到达声明上限才恢复 NORMAL。
 */
@Service
public class RecoveryThrottler {

    private final CapacityProfileService profileService;
    private final DegradationOrchestrator orchestrator;
    private final ClearingProperties properties;

    public RecoveryThrottler(CapacityProfileService profileService,
            DegradationOrchestrator orchestrator,
            ClearingProperties properties) {
        this.profileService = profileService;
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recoveryTick() {
        ClearingProperties.Recovery cfg = properties.getCapacity().getRecovery();
        int confirmThreshold = properties.getCapacity().getProbe().getConsecutiveOkToRecover();
        for (CapacityProfile profile : profileService.list()) {
            if (profile.isManualOverride()) {
                continue;
            }
            String code = profile.getDependencyCode();
            int attempts = orchestrator.attemptsLastTick(code);
            int failures = orchestrator.failuresLastTick(code);
            switch (profile.getState()) {
                case DOWN -> {
                    if (profile.getConsecutiveProbeOk() >= confirmThreshold) {
                        int start = Math.min(cfg.getInitialCeiling(), profile.getDeclaredCeiling());
                        profile.setConsecutiveFailureTicks(0);
                        profile.setRampDwellTicks(0);
                        profileService.transition(profile, ProfileState.RECOVERING, start,
                                CapacityEventType.RECOVERY_STARTED,
                                "探针连续 " + profile.getConsecutiveProbeOk()
                                        + " 次成功，进入保守恢复：生效上限从 " + start
                                        + "/周期起步爬坡（声明上限 " + profile.getDeclaredCeiling()
                                        + "），期间任何失败立即回落");
                    }
                }
                case DEGRADED -> {
                    if (attempts > 0 && failures == 0) {
                        profile.setConsecutiveProbeOk(profile.getConsecutiveProbeOk() + 1);
                        if (profile.getConsecutiveProbeOk() >= confirmThreshold) {
                            profile.setConsecutiveFailureTicks(0);
                            profile.setRampDwellTicks(0);
                            profileService.transition(profile, ProfileState.RECOVERING,
                                    profile.getEffectiveCeiling(), CapacityEventType.RECOVERY_STARTED,
                                    "连续 " + profile.getConsecutiveProbeOk()
                                            + " 个干净周期（有尝试零失败），从降级上限 " + profile.getEffectiveCeiling()
                                            + "/周期开始爬坡恢复");
                            continue;
                        }
                        profileService.save(profile);
                    } else if (failures > 0) {
                        profile.setConsecutiveProbeOk(0);
                        profileService.save(profile);
                    }
                }
                case RECOVERING -> {
                    boolean probeFailedThisTick = profile.getProbesThisTick() > 0
                            && profile.getConsecutiveProbeOk() == 0;
                    if (failures > 0 || probeFailedThisTick) {
                        profile.setConsecutiveProbeOk(0);
                        profile.setRampDwellTicks(0);
                        profileService.transition(profile, ProfileState.DOWN, 0,
                                CapacityEventType.RECOVERY_ABORTED,
                                "恢复期间出现" + (failures > 0 ? "真实调用失败 " + failures + " 次" : "探测失败")
                                        + "：保守恢复原则，立即回落 DOWN（生效上限置零），不追认短暂恢复信号");
                        continue;
                    }
                    profile.setRampDwellTicks(profile.getRampDwellTicks() + 1);
                    if (profile.getRampDwellTicks() >= cfg.getDwellTicks()) {
                        profile.setRampDwellTicks(0);
                        int next = Math.min(profile.getDeclaredCeiling(),
                                profile.getEffectiveCeiling() + cfg.getStep());
                        if (next >= profile.getDeclaredCeiling()) {
                            profile.setConsecutiveProbeOk(0);
                            profileService.transition(profile, ProfileState.NORMAL,
                                    profile.getDeclaredCeiling(), CapacityEventType.RECOVERED,
                                    "爬坡到达声明上限 " + profile.getDeclaredCeiling()
                                            + "/周期，恢复正常容量（全程未越过声明上限）");
                        } else {
                            profileService.transition(profile, ProfileState.RECOVERING, next,
                                    CapacityEventType.RAMP_STEP,
                                    "稳定驻留 " + cfg.getDwellTicks() + " 个周期，生效上限爬坡至 " + next
                                            + "/周期（声明上限 " + profile.getDeclaredCeiling() + "）");
                        }
                    } else {
                        profileService.save(profile);
                    }
                }
                default -> {
                    // NORMAL：无需恢复动作
                }
            }
        }
    }
}
