package com.platform.iot.daikin.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 固定厂家路径白名单；控制端点 publicRead=false，旧读取入口无法调用 PUT。
 */
public enum DaikinEndpoint {
    TOKEN("POST", "/token", false, false),
    TOKEN_REFRESH("POST", "/token/refresh", false, false),
    EQUIPMENTS("GET", "/v2/equipments", true, false),
    EQUIPMENT("GET", "/v2/equipments/%s", true, true),
    ABNORMAL_EQUIPMENTS("GET", "/v2/equipments/abnormal", true, false),
    INUNITS("GET", "/v2/equipments/inunit", true, false),
    INUNIT("GET", "/v2/equipments/inunit/%s", true, true),
    OUTUNITS("GET", "/v2/equipments/outunit", true, false),
    OUTUNIT("GET", "/v2/equipments/outunit/%s", true, true),
    INUNIT_RUNTIME("POST", "/v2/runtime/inunit", true, false),
    OUTUNIT_RUNTIME("POST", "/v2/runtime/outunit", true, false),
    CONTROL_ON_OFF("PUT", "/v2/operations/onOff/%s", false, true),
    CONTROL_TEMPERATURE("PUT", "/v2/operations/temperature/%s", false, true),
    CONTROL_MODE("PUT", "/v2/operations/mode/%s", false, true),
    CONTROL_AIR_FLOW("PUT", "/v2/operations/airFlow/%s", false, true),
    CONTROL_FAN_SPEED("PUT", "/v2/operations/fanSpeed/%s", false, true);

    private final String method;
    private final String pathTemplate;
    private final boolean publicRead;
    private final boolean resourceRequired;

    DaikinEndpoint(String method, String pathTemplate, boolean publicRead, boolean resourceRequired) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.publicRead = publicRead;
        this.resourceRequired = resourceRequired;
    }

    public String method() {
        return method;
    }

    public boolean publicRead() {
        return publicRead;
    }

    public String path(String resourceId) {
        if (resourceRequired) {
            if (resourceId == null || resourceId.isBlank() || resourceId.length() > 256
                    || ".".equals(resourceId) || "..".equals(resourceId)
                    || resourceId.indexOf('/') >= 0 || resourceId.indexOf('\\') >= 0
                    || resourceId.codePoints().anyMatch(Character::isISOControl)) {
                throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
            }
            return pathTemplate.formatted(URLEncoder.encode(resourceId, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        if (resourceId != null && !resourceId.isBlank()) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        }
        return pathTemplate;
    }
}
