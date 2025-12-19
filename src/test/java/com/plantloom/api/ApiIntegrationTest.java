package com.plantloom.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsLoadedDefinitions() throws Exception {
        mvc.perform(get("/api/definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem("quick")));
    }

    @Test
    void startsAnInstanceAndExposesItsDetail() throws Exception {
        String body = mvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"quick\",\"variables\":{\"heat\":true}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.definitionId", is("quick")))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/api/instances/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id", is(id)))
                .andExpect(jsonPath("$.stages[0].stageId", is("mix")))
                .andExpect(jsonPath("$.variables.heat", is(true)));

        mvc.perform(get("/api/instances/{id}/timeline", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type", is("INSTANCE_STARTED")));
    }

    @Test
    void unknownDefinitionIsNotFound() throws Exception {
        mvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"does-not-exist\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Not found")));
    }

    @Test
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registersYamlDefinitionAndRejectsInvalidOnes() throws Exception {
        String yaml = """
                id: api-registered
                stages:
                  - id: only
                    equipmentType: MIXER
                    durationMs: 10
                """;
        mvc.perform(post("/api/definitions").contentType("application/yaml").content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is("api-registered")));

        String invalid = """
                id: broken
                stages:
                  - id: only
                    equipmentType: TELEPORTER
                """;
        mvc.perform(post("/api/definitions").contentType("application/yaml").content(invalid))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems[0]").exists());
    }

    @Test
    void evaluatesRulesForDryRuns() throws Exception {
        mvc.perform(post("/api/rules/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expression\":\"sensors['x'] > 5 && vars['ok'] == true\","
                                + "\"vars\":{\"ok\":true},\"sensors\":{\"x\":7.5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is(true)));
    }

    @Test
    void reportsEquipmentAndDiagnostics() throws Exception {
        mvc.perform(get("/api/equipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units[*].id", hasItem("MIXER-1")));

        mvc.perform(post("/api/equipment/{id}/maintenance", "MIXER-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("MIXER-2")));

        mvc.perform(get("/api/monitoring/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceLimit", is(500)));

        mvc.perform(get("/actuator/health/engine"))
                .andExpect(status().isOk());
    }
}
