package me.projects.pushpage.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class PublishControllerIT {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pages-dir", tempDir::toString);
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        jdbc.execute("DELETE FROM pages");
    }

    @Test
    void publishPage_withValidHtml_returns200WithUrlAndId() throws Exception {
        mockMvc.perform(post("/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void publishPage_withEmptyHtml_returns400() throws Exception {
        mockMvc.perform(post("/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "", "title": "Test"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishPage_withOversizedHtml_returns413() throws Exception {
        String oversized = "a".repeat(2 * 1024 * 1024); // 2 MB
        mockMvc.perform(post("/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\": \"" + oversized + "\", \"title\": \"Test\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void publishPage_withValidHtml_returnsXMaxFileSizeHeader() throws Exception {
        mockMvc.perform(post("/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Max-File-Size"));
    }

    @Test
    void listPages_returns200WithJsonArray() throws Exception {
        mockMvc.perform(get("/pages"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void deletePage_whenPageExists_returns204() throws Exception {
        String response = mockMvc.perform(post("/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Delete me</h1>", "title": "Temp"}
                                """))
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(delete("/pages/" + id))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePage_whenIdNotFound_returns404() throws Exception {
        mockMvc.perform(delete("/pages/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void health_returns200WithStatusOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
