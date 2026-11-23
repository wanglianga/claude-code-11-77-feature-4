package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.CapacityEvent;
import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ProfileState;
import com.example.clearing.repo.CapacityEventRepository;
import com.example.clearing.repo.CapacityProfileRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 容量画像库：每个被保护依赖（清算数据库 / 待遇规则 / 基金网关）的容量画像。
 * 预先声明的容量上限（declaredCeiling）是硬安全边界；降级编排器与恢复节流器
 * 只在该边界之内调节生效上限（effectiveCeiling），即使画像短暂滞后也不会越界。
 * 画像同时承载近期成功率（EMA）与探针统计，供闭环判断与审计复核。
 */
@Service
public class CapacityProfileService {

    /** 成功率画像的指数滑动平均系数 */
    private static final double EMA_ALPHA = 0.3;

    private final CapacityProfileRepository profileRepository;
    private final CapacityEventRepository eventRepository;
    private final ClearingProperties properties;
    private final TokenBucketService bucketService;

    public CapacityProfileService(CapacityProfileRepository profileRepository,
            CapacityEventRepository eventRepository,
            ClearingProperties properties,
            TokenBucketService bucketService) {
        this.profileRepository = profileRepository;
        this.eventRepository = eventRepository;
        this.properties = properties;
        this.bucketService = bucketService;
    }

    /** 启动种子：按配置为每个被保护依赖建立画像（已存在则保留运行态） */
    @Transactional
    public void ensureProfiles() {
        properties.getCapacity().getProfiles().forEach((code, spec) -> {
            profileRepository.findById(code).orElseGet(() -> {
                CapacityProfile profile = new CapacityProfile(code, spec.getDeclaredCeiling(),
                        spec.getMinSharePerSource());
                CapacityProfile saved = profileRepository.save(profile);
                eventRepository.save(new CapacityEvent(code, CapacityEventType.PROFILE_SEEDED,
                        spec.getDeclaredCeiling(),
                        "容量画像初始化：声明上限 " + spec.getDeclaredCeiling() + "/周期（硬边界），每来源最低服务份额 "
                                + spec.getMinSharePerSource()));
                return saved;
            });
        });
    }

    /** 演练复位：画像回到配置初始状态（NORMAL、生效上限=声明上限、清零探针统计） */
    @Transactional
    public void resetAll() {
        properties.getCapacity().getProfiles().forEach((code, spec) -> {
            CapacityProfile p = profileRepository.findById(code)
                    .orElse(new CapacityProfile(code, spec.getDeclaredCeiling(), spec.getMinSharePerSource()));
            p.setDeclaredCeiling(spec.getDeclaredCeiling());
            p.setEffectiveCeiling(spec.getDeclaredCeiling());
            p.setMinSharePerSource(spec.getMinSharePerSource());
            p.setState(ProfileState.NORMAL);
            p.setManualOverride(false);
            p.setConsecutiveFailureTicks(0);
            p.setConsecutiveProbeOk(0);
            p.setProbesThisTick(0);
            p.setTotalProbes(0);
            p.setTotalProbeFailures(0);
            p.setRecentSuccessRate(1.0);
            p.setRampDwellTicks(0);
            p.setLastStateChangeAt(Instant.now());
            p.setUpdatedAt(Instant.now());
            profileRepository.save(p);
        });
    }

    public CapacityProfile get(String dependencyCode) {
        return profileRepository.findById(dependencyCode).orElse(null);
    }

    public List<CapacityProfile> list() {
        return profileRepository.findAll();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public CapacityProfile save(CapacityProfile profile) {
        profile.setUpdatedAt(Instant.now());
        return profileRepository.save(profile);
    }

    /** 记录一次真实调用结果到成功率画像（EMA） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordOutcome(String dependencyCode, boolean success) {
        CapacityProfile profile = profileRepository.findById(dependencyCode).orElse(null);
        if (profile == null) {
            return;
        }
        double sample = success ? 1.0 : 0.0;
        profile.setRecentSuccessRate(
                EMA_ALPHA * sample + (1 - EMA_ALPHA) * profile.getRecentSuccessRate());
        save(profile);
    }

    /**
     * 状态迁移并写容量事件（审计轨迹）。生效上限永远夹在 [0, declaredCeiling]，
     * 并同步到依赖本地令牌桶：生效上限即补充速率，0 即冻结（不放行、不补充）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CapacityProfile transition(CapacityProfile profile, ProfileState newState,
            int newEffectiveCeiling, CapacityEventType eventType, String detail) {
        int clamped = Math.max(0, Math.min(profile.getDeclaredCeiling(), newEffectiveCeiling));
        profile.setState(newState);
        profile.setEffectiveCeiling(clamped);
        profile.setLastStateChangeAt(Instant.now());
        CapacityProfile saved = save(profile);
        bucketService.syncRate(profile.getDependencyCode(), clamped);
        eventRepository.save(new CapacityEvent(profile.getDependencyCode(), eventType, clamped, detail));
        return saved;
    }

    /** 只写事件不改状态（探测记录等） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordEvent(String dependencyCode, CapacityEventType type, int effectiveCeiling, String detail) {
        eventRepository.save(new CapacityEvent(dependencyCode, type, effectiveCeiling, detail));
    }

    public List<CapacityEvent> events(int limit) {
        return eventRepository.findByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 500)));
    }
}
