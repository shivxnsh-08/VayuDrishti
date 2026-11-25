package com.saanscheck.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only user management (read-only). Authorisation is enforced by the security filter chain
 * ({@code /api/v1/admin/**} requires the ADMIN role from the signed JWT): anonymous callers get 401, normal users
 * 403. There is deliberately no endpoint that changes roles or deletes accounts, so nobody - including a
 * compromised browser - can promote a user or demote/delete the admin through this API.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    public AdminUserService.Page list(@RequestParam(required = false) String search,
                                      @RequestParam(required = false) String role,
                                      @RequestParam(required = false) AdminUserService.Status status,
                                      @RequestParam(defaultValue = "createdAt") String sort,
                                      @RequestParam(defaultValue = "desc") String dir,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return users.list(search, role, status, sort, dir, page, size);
    }

    @GetMapping("/stats")
    public AdminUserService.Stats stats() {
        return users.stats();
    }

    @GetMapping("/{id}")
    public AdminUserService.Detail detail(@PathVariable long id) {
        return users.detail(id);
    }
}
