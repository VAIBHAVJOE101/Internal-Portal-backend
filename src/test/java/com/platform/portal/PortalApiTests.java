package com.platform.portal;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** End-to-end API tests against the mock profile (H2 + simulated integrations). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class PortalApiTests {

    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final RequestPostProcessor READER = user("reader").roles("READER");

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/info")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("mock"));
    }

    @Test
    void readerCanReadButNotWrite() throws Exception {
        mvc.perform(get("/api/inventory/pages").with(READER)).andExpect(status().isOk());
        mvc.perform(post("/api/inventory/pages").with(READER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemKafkaPageCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/inventory/pages/kafka-instances").with(ADMIN).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void dynamicPageLifecycle() throws Exception {
        mvc.perform(post("/api/inventory/pages").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Load Balancers","columns":[{"label":"Name","type":"TEXT","required":true},
                         {"label":"VIP","type":"IP"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("load-balancers"));
        mvc.perform(post("/api/inventory/pages/load-balancers/columns").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Expires\",\"type\":\"DATE\",\"expiryTracking\":true}"))
                .andExpect(jsonPath("$.columns[*].key", hasItem("expires")));
        mvc.perform(post("/api/inventory/pages/load-balancers/records").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"name\":\"lb-1\",\"vip\":\"not-an-ip\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/inventory/pages/load-balancers/records").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"name\":\"lb-1\",\"vip\":\"10.0.0.10\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/inventory/pages/load-balancers/columns/expires").with(ADMIN).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/inventory/pages/load-balancers").with(ADMIN).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/audit").param("action", "INVENTORY_PAGE_DELETE").with(ADMIN))
                .andExpect(jsonPath("$.items[0].targetId").value("load-balancers"));
    }

    @Test
    void kafkaTopicManagementUsesInventoryInstances() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/kafka/instances").with(ADMIN)).andReturn().getResponse().getContentAsString(),
                "$[0].instance.id").toString();
        String base = "/api/kafka/instances/" + id + "/topics";
        mvc.perform(post(base).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"it.topic\",\"partitions\":3,\"replicationFactor\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(put(base + "/it.topic/configs").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"set\":{\"retention.ms\":\"3600000\"}}"))
                .andExpect(jsonPath("$.configs[?(@.name=='retention.ms')].value", hasItem("3600000")));
        mvc.perform(post(base + "/it.topic/partitions").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalCount\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(base + "/it.topic").with(ADMIN).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void bulkRemapPreviewsBeforeApplying() throws Exception {
        String body = """
                {"rowIds":["route-orders-api#0","route-orders-api#1"],"field":"kafka.topic","mode":"SET","value":"orders.v2"}""";
        mvc.perform(post("/api/app-kafka/preview").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post("/api/app-kafka/apply").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.succeeded").value(2));
    }
}
