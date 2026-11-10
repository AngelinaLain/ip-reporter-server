package com.ipreporter.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class MachineController {
    private final MachineService machines;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    public MachineController(MachineService machines,JdbcTemplate jdbc,AppProperties properties) { this.machines=machines;this.jdbc=jdbc;this.properties=properties; }
    @PostMapping(value="/api/report",consumes="application/json") @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@Valid @RequestBody ReportInput input,HttpServletRequest request) { machines.accept(input,peerIp(request,properties.trustProxyHeaders())); }
    // 仅在后端不对外暴露、上游代理会重写此头时启用；拒绝逗号列表和主机名。
    static String peerIp(HttpServletRequest request,boolean trustProxyHeaders) {
        String header=request.getHeader("X-Real-IP");
        if(trustProxyHeaders && header!=null && header.length()<=64) {
            try { IpAddresses.parse(header,header.contains(":")?"AAAA":"A");return header; }
            catch(ApiException ignored) { /* 无效转发头回退到真实连接对端。 */ }
        }
        return request.getRemoteAddr();
    }
    @GetMapping("/api/machines") List<MachineService.MachineView> machines() { return machines.list(); }
    @GetMapping("/api/machines/{id}") MachineService.MachineView machine(@PathVariable String id) { return machines.get(id); }
    @DeleteMapping("/api/machines/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable String id) { machines.delete(id); }
    @GetMapping("/api/dashboard") Map<String,Long> dashboard() {
        var all=machines.list();long online=all.stream().filter(MachineService.MachineView::online).count();
        return Map.of("totalMachines",(long)all.size(),"onlineMachines",online,"offlineMachines",all.size()-online,
            "enabledRules",jdbc.queryForObject("SELECT COUNT(*) FROM ddns_rules WHERE enabled=TRUE",Long.class),
            "failedRules",jdbc.queryForObject("SELECT COUNT(*) FROM ddns_rules WHERE last_status='ERROR'",Long.class));
    }
}
