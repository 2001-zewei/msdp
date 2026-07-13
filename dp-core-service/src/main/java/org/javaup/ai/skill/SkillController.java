package org.javaup.ai.skill;

import org.javaup.ai.agent.AgentOrchestrator;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Skills 管理端点：查看 + 热加载。
 */
@RestController
@RequestMapping("/ai/skills")
public class SkillController {

    private final SkillManager skillManager;
    private final AgentOrchestrator orchestrator;

    public SkillController(SkillManager skillManager, AgentOrchestrator orchestrator) {
        this.skillManager = skillManager;
        this.orchestrator = orchestrator;
    }

    /** 查看当前已加载的 Skills */
    @GetMapping
    public Map<String, Object> summary() {
        return skillManager.summary();
    }

    /** 运行时重新扫描 Skill 目录，不需要重启服务 */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        skillManager.reload();
        orchestrator.setSkillManager(skillManager);
        return skillManager.summary();
    }
}
