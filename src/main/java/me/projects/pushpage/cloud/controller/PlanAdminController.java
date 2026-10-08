package me.projects.pushpage.cloud.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.cloud.config.ConditionalOnPlansEnabled;
import me.projects.pushpage.cloud.model.Plan;
import me.projects.pushpage.cloud.repository.PlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
@ConditionalOnPlansEnabled
@Tag(name = "Admin", description = "Subscription plan admin actions (PLANS_ENABLED)")
public class PlanAdminController {

    private final PlanRepository planRepository;

    public PlanAdminController(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @Operation(summary = "Change a user's subscription plan", description = "Sets the plan for the specified user. Valid values: tier1, tier2, tier3.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Plan updated", content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid plan value", content = @Content),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    @PatchMapping("/users/{id}/plan")
    public ResponseEntity<Void> changePlan(
            @Parameter(description = "8-character user ID") @PathVariable String id,
            @Parameter(description = "Plan name: tier1, tier2, or tier3") @RequestParam String plan) {
        try {
            Plan.valueOf(plan);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid plan '%s'. Valid values: tier1, tier2, tier3".formatted(plan));
        }
        if (!planRepository.changePlan(id, plan)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
