package th.ac.mfu.doctracking.identity;

import java.util.*;
import th.ac.mfu.doctracking.common.ApiException;

public record Actor(UUID id, String email, String displayName, boolean admin, List<Membership> memberships,
                    Set<UUID> stationIds, Set<String> capabilities, long sessionVersion) {
    public record Membership(UUID unitId, String role) {}
    public boolean hasUnit(UUID unit) { return memberships.stream().anyMatch(m -> m.unitId().equals(unit)); }
    public boolean unitAdmin(UUID unit) { return admin || memberships.stream().anyMatch(m -> m.unitId().equals(unit) && m.role().equals("UNIT_ADMIN")); }
    public boolean hasStation(UUID station) { return stationIds.contains(station); }
    public void requireAdmin() { if (!admin) throw new ApiException(403,"FORBIDDEN","Central Admin required"); }
    public void requireCapability(String capability) { if (!capabilities.contains(capability)) throw new ApiException(403,"FORBIDDEN","Capability required"); }
    public void requireStation(UUID station) { if (!hasStation(station)) throw new ApiException(404,"NOT_FOUND","Station not found"); }
}
