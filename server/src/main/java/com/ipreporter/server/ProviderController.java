package com.ipreporter.server;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {
    private final ProviderService service;
    public ProviderController(ProviderService service) { this.service=service; }
    @GetMapping List<ProviderService.View> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) ProviderService.View create(@Valid @RequestBody ProviderService.Input input) { return service.create(input); }
    @PutMapping("/{id}") ProviderService.View update(@PathVariable long id,@Valid @RequestBody ProviderService.Input input) { return service.update(id,input); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable long id) { service.delete(id); }
}
