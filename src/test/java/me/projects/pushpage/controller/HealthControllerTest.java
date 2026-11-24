package me.projects.pushpage.controller;

import me.projects.pushpage.model.HealthResponse;
import me.projects.pushpage.service.HealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    static final HealthResponse UP = new HealthResponse("UP", "9.9.9", 123, 12, 3,
            "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z",
            new HealthResponse.StorageInfo(1000, "1000 B", 5000, "4 KB"));
    static final HealthResponse DOWN = new HealthResponse("DOWN", "9.9.9", 123, 0, 0, null, null, null);

    @Mock
    HealthService healthService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(healthService)).build();
    }

    @Test
    void publicHealth_whenUp_is200AndOnlyHasTheStatus() throws Exception {
        when(healthService.getHealth()).thenReturn(UP);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void publicHealth_whenDown_is503AndOnlyHasTheStatus() throws Exception {
        when(healthService.getHealth()).thenReturn(DOWN);

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void adminHealth_whenUp_returnsEverything() throws Exception {
        when(healthService.getHealth()).thenReturn(UP);

        mockMvc.perform(get("/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("9.9.9"))
                .andExpect(jsonPath("$.livePages").value(12))
                .andExpect(jsonPath("$.storage.freeBytes").value(5000));
    }

    @Test
    void adminHealth_whenDown_is503() throws Exception {
        when(healthService.getHealth()).thenReturn(DOWN);

        mockMvc.perform(get("/admin/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
