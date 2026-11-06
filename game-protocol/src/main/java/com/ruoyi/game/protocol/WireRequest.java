package com.ruoyi.game.protocol;

import tools.jackson.databind.JsonNode;

public record WireRequest(String path, String api, String command, JsonNode data) { }
