package com.blissfuljuan.dieapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationHealthTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayMigrationsApplied() {
        Integer tables = new JdbcTemplate(dataSource).queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name IN "
                + "('audit_log','outbox_event','app_user','academic_class','project','spring_session')",
            Integer.class);
        assertThat(tables).isEqualTo(6);
    }

    @Test
    void otherEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/anything")).andExpect(status().isUnauthorized());
    }
}
