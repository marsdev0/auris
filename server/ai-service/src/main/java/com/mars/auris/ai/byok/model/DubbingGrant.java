package com.mars.auris.ai.byok.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * @author geyan
 * @date 2026/11/13
 */
@Data
@AllArgsConstructor
public class DubbingGrant {

    /**
     * Engine task body "byok" node: {user_id, llm:{...}, tts:{...}, asr?:{...}}; never null when llm/tts gate passed
     */
    private Map<String, Object> engineByok;

    /**
     * JSON for dubbing_task.config_snapshot: {llm:{config_id,provider,model}, ...}; no api_key
     */
    private String snapshot;
}
