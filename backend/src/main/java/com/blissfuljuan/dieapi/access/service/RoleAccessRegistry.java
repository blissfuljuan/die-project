package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.account.model.UserRole;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Selects the {@link RoleAccess} strategy for an account role; startup fails if a role has none. */
@Component
class RoleAccessRegistry {

    private final Map<UserRole, RoleAccess> byRole = new EnumMap<>(UserRole.class);

    RoleAccessRegistry(List<RoleAccess> strategies) {
        for (RoleAccess strategy : strategies) {
            if (byRole.put(strategy.role(), strategy) != null) {
                throw new IllegalStateException("More than one access strategy for role " + strategy.role());
            }
        }
        for (UserRole role : UserRole.values()) {
            if (!byRole.containsKey(role)) {
                throw new IllegalStateException("No access strategy for role " + role);
            }
        }
    }

    RoleAccess forRole(UserRole role) {
        return byRole.get(role);
    }
}
