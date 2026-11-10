package com.ipreporter.server;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ddns")
public class DdnsController {
    private final DdnsRuleService service;
    public DdnsController(DdnsRuleService service) { this.service=service; }
    @GetMapping("/rules") List<DdnsRuleService.View> rules() { return service.list(); }
    @PostMapping("/rules") @ResponseStatus(HttpStatus.CREATED) DdnsRuleService.View create(@Valid @RequestBody DdnsRuleService.Input input) { return service.create(input); }
    @PutMapping("/rules/{id}") DdnsRuleService.View update(@PathVariable long id,@Valid @RequestBody DdnsRuleService.Input input) { return service.update(id,input); }
    record EnabledInput(@NotNull Boolean enabled) {}
    @PatchMapping("/rules/{id}/enabled")
    DdnsRuleService.View setEnabled(@PathVariable long id, @Valid @RequestBody EnabledInput input) {
        return service.setEnabled(id, input.enabled());
    }
    @DeleteMapping("/rules/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable long id) { service.delete(id); }
    // 手动同步是一次明确的用户操作，允许对暂停的规则试运行；定时任务只运行 enabled 规则。
    @PostMapping("/rules/{id}/sync") DdnsRuleService.View sync(@PathVariable long id) { return service.sync(id); }
    @GetMapping("/logs") List<DdnsRuleService.Log> logs(@RequestParam(defaultValue="100") int limit) { return service.logs(limit); }
}
