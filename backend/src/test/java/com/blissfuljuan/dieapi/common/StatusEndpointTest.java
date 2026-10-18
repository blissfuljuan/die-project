package com.blissfuljuan.dieapi.common;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatusEndpointTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void anonymousCallerGetsStatus() throws Exception {
        mvc.perform(get("/api/v1/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.apiVersion").value("v1"))
            .andExpect(jsonPath("$.application").value("die-api"));
    }
}
