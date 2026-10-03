package com.platform.portal.alerts;

import java.util.List;
import java.util.Map;

import com.platform.portal.common.PageResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AlertStream alertStream;

    public AlertController(AlertService alertService, AlertStream alertStream) {
        this.alertService = alertService;
        this.alertStream = alertStream;
    }

    @GetMapping
    public PageResult<AlertService.AlertDto> search(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String severity,
                                                    @RequestParam(required = false) String source,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return alertService.search(status, severity, source, q, page, size);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return alertService.summary();
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "14") int days) {
        return alertService.trend(Math.min(Math.max(days, 1), 90));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return alertStream.subscribe();
    }

    @PostMapping("/{id}/ack")
    public AlertService.AlertDto acknowledge(@PathVariable Long id) {
        return alertService.acknowledge(id);
    }

    @PostMapping("/{id}/resolve")
    public AlertService.AlertDto resolve(@PathVariable Long id) {
        return alertService.resolveById(id);
    }
}
