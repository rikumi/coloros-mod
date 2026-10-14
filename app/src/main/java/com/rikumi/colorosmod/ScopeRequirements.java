package com.rikumi.colorosmod;

import java.util.Set;

final class ScopeRequirements {
    static boolean hasMissing(Set<String> recommended, Set<String> installed, Set<String> selected) {
        for (String scope : recommended) {
            // API 102 uses "system" for system_server; "android" is a different scope.
            if (("system".equals(scope) || installed.contains(scope)) && !selected.contains(scope)) return true;
        }
        return false;
    }
}
