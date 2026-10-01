package com.chineselearning.modules.admin.controller;
import com.chineselearning.modules.admin.dto.RoleRequests.*;
import com.chineselearning.modules.admin.service.AdminRoleService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/roles")
public class AdminRoleController {
    private final AdminRoleService service;
    public AdminRoleController(AdminRoleService service){this.service=service;}
    @GetMapping public List<AdminRoleService.Role> list(){return service.list();}
    @GetMapping("/permissions") public List<AdminRoleService.Permission> permissions(){return service.catalog();}
    @GetMapping("/options") public List<AdminRoleService.Role> options(Principal actor){return service.options(actor.getName());}
    @GetMapping("/{code}") public AdminRoleService.Role get(@PathVariable String code){return service.get(code);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public AdminRoleService.Role create(Principal actor,@Valid @RequestBody CreateRole body){return service.create(actor.getName(),body);}
    @PutMapping("/{code}") public AdminRoleService.Role update(Principal actor,@PathVariable String code,@Valid @RequestBody UpdateRole body){return service.update(actor.getName(),code,body);}
    @DeleteMapping("/{code}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(Principal actor,@PathVariable String code){service.delete(actor.getName(),code);}
}
