package io.github.sanskar121543.payments.activity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ActivityController {

    private final ActivityService activity;

    ActivityController(ActivityService activity) {
        this.activity = activity;
    }

    @GetMapping("/v1/activity")
    List<ActivityItem> latest(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long transferId) {
        return transferId == null ? activity.latest(limit) : activity.forTransfer(transferId);
    }
}
