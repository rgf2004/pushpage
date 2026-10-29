package me.projects.pushpage.controller;

import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
import me.projects.pushpage.repository.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class ViewerControllerIT extends PostgresTestSupport {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pages-dir", tempDir::toString);
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    ApiKeyAuthFilter apiKeyAuthFilter;

    @Autowired
    PageRepository pageRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter).build();
    }

    @Test
    void viewer_isPublicAndEmbedsThePageInAnIframe() throws Exception {
        pageRepository.save("viewer01", "Quarterly report", null, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/viewer/viewer01"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<iframe class=\"viewer-frame\" src=\"/pages/viewer01.html\"")))
                .andExpect(content().string(containsString("<title>Quarterly report</title>")))
                .andExpect(content().string(not(containsString("allow-same-origin"))));
    }

    @Test
    void viewer_escapesTheTitle() throws Exception {
        pageRepository.save("viewer02", "<script>alert(1)</script>", null, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/viewer/viewer02"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)"))))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
    }

    @Test
    void viewer_copiesThePagesOwnPreviewMetaIntoTheShell() throws Exception {
        pageRepository.save("viewer03", "Shared title", null, Instant.now().plus(1, ChronoUnit.HOURS));
        Files.writeString(tempDir.resolve("viewer03.html"), """
                <html><head><title>Shared title</title>
                <meta property="og:title" content="Custom OG title">
                <meta property="og:image" content="https://example.com/card.png">
                <meta name="twitter:card" content="summary_large_image">
                <meta name="description" content="A \"quoted\" <b>summary</b>">
                <meta name="viewport" content="width=device-width">
                <meta http-equiv="refresh" content="0;url=https://evil.example">
                <script>alert(1)</script></head><body>x</body></html>
                """);

        mockMvc.perform(get("/viewer/viewer03"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta property=\"og:title\" content=\"Custom OG title\">")))
                .andExpect(content().string(containsString("content=\"https://example.com/card.png\"")))
                .andExpect(content().string(containsString("name=\"twitter:card\"")))
                .andExpect(content().string(containsString("name=\"description\"")))
                .andExpect(content().string(not(containsString("<b>summary</b>"))))
                // page defaults fill the gaps, and unrelated or dangerous tags are not copied
                .andExpect(content().string(containsString("property=\"og:type\"")))
                .andExpect(content().string(not(containsString("http-equiv"))))
                .andExpect(content().string(not(containsString("evil.example"))))
                .andExpect(content().string(not(containsString("alert(1)"))));
    }

    @Test
    void viewer_usesPushpageDefaultsWhenThePageHasNoPreviewMeta() throws Exception {
        pageRepository.save("viewer04", "Plain page", null, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/viewer/viewer04"))
                .andExpect(content().string(containsString("<meta property=\"og:title\" content=\"Plain page\">")))
                .andExpect(content().string(containsString("/static/og-image.png")));
    }

    @Test
    void viewer_returns404ForUnknownPage() throws Exception {
        mockMvc.perform(get("/viewer/doesnotexist"))
                .andExpect(status().isNotFound());
    }
}
