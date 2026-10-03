package com.platform.portal.connectivity;

import java.util.List;
import java.util.Map;

import com.platform.portal.connectivity.ConnectivityService.AdhocRequest;
import com.platform.portal.connectivity.ConnectivityService.ResultDto;
import com.platform.portal.connectivity.ConnectivityService.TargetDto;
import com.platform.portal.connectivity.ConnectivityService.TargetRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connectivity")
public class ConnectivityController {

    private final ConnectivityService service;

    public ConnectivityController(ConnectivityService service) {
        this.service = service;
    }

    @GetMapping("/targets")
    public List<TargetDto> targets() {
        return service.list();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @GetMapping("/targets/{id}")
    public TargetDto target(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/targets")
    @ResponseStatus(HttpStatus.CREATED)
    public TargetDto create(@Valid @RequestBody TargetRequest request) {
        return service.create(request);
    }

    @PutMapping("/targets/{id}")
    public TargetDto update(@PathVariable Long id, @Valid @RequestBody TargetRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/targets/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/targets/{id}/run")
    public ResultDto run(@PathVariable Long id) {
        return service.runNow(id);
    }

    @PostMapping("/run-all")
    public List<ResultDto> runAll() {
        return service.runAll();
    }

    @GetMapping("/targets/{id}/results")
    public List<ResultDto> results(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit) {
        return service.history(id, limit);
    }

    @PostMapping("/test")
    public ResultDto test(@Valid @RequestBody AdhocRequest request) {
        return service.runAdhoc(request);
    }

    @GetMapping("/adhoc-results")
    public List<ResultDto> adhocResults(@RequestParam(defaultValue = "20") int limit) {
        return service.adhocHistory(limit);
    }
}
