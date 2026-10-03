package com.platform.portal.appkafka;

import java.util.List;
import java.util.Map;

import com.platform.portal.appkafka.RoutesModels.ApplyResult;
import com.platform.portal.appkafka.RoutesModels.BulkChangeRequest;
import com.platform.portal.appkafka.RoutesModels.Change;
import com.platform.portal.appkafka.RoutesModels.RouteRow;
import com.platform.portal.appkafka.RoutesModels.RoutesConfig;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app-kafka")
public class AppKafkaController {

    private final AppKafkaService service;

    public AppKafkaController(AppKafkaService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public RoutesConfig config() {
        return service.config();
    }

    @GetMapping("/routes")
    public List<RouteRow> routes(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) String api,
                                 @RequestParam(required = false) String method,
                                 @RequestParam(required = false) String field,
                                 @RequestParam(required = false) String value) {
        return service.routes(q, api, method, field, value);
    }

    @GetMapping("/values")
    public Map<String, List<String>> values() {
        return service.values();
    }

    /** Dry run: returns the changes that would be applied. Allowed for ADMIN only because it is a POST. */
    @PostMapping("/preview")
    public List<Change> preview(@Valid @RequestBody BulkChangeRequest request) {
        return service.preview(request);
    }

    @PostMapping("/apply")
    public ApplyResult apply(@Valid @RequestBody BulkChangeRequest request) {
        return service.apply(request);
    }
}
