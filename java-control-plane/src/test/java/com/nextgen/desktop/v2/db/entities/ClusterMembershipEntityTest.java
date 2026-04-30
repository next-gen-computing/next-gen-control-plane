package com.nextgen.desktop.v2.db.entities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ClusterMembershipEntityTest {
    private ClusterMembershipEntity membership;

    @BeforeEach
    void setUp() {
        membership = new ClusterMembershipEntity();
        membership.setId("membership-123");
        membership.setServerId("server-123");
        membership.setNodeId("node-123");
        membership.setStatus(ClusterMembershipEntity.MembershipStatus.PENDING);
        membership.setCpuUsagePercent(45.5);
        membership.setMemoryUsageMb(8192.0);
        membership.setLastHeartbeat(LocalDateTime.now());
        membership.setJoinedAt(LocalDateTime.now());
    }

    @Test
    void testGettersAndSetters() {
        assertEquals("membership-123", membership.getId());
        assertEquals("server-123", membership.getServerId());
        assertEquals("node-123", membership.getNodeId());
        assertEquals(ClusterMembershipEntity.MembershipStatus.PENDING, membership.getStatus());
        assertEquals(45.5, membership.getCpuUsagePercent());
        assertEquals(8192.0, membership.getMemoryUsageMb());
        assertNotNull(membership.getLastHeartbeat());
        assertNotNull(membership.getJoinedAt());
    }

    @Test
    void testStatusTransitions() {
        assertEquals(ClusterMembershipEntity.MembershipStatus.PENDING, membership.getStatus());
        membership.setStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
        assertEquals(ClusterMembershipEntity.MembershipStatus.APPROVED, membership.getStatus());
        membership.setStatus(ClusterMembershipEntity.MembershipStatus.REJECTED);
        assertEquals(ClusterMembershipEntity.MembershipStatus.REJECTED, membership.getStatus());
    }

    @Test
    void testMetricUpdates() {
        membership.setCpuUsagePercent(75.0);
        membership.setMemoryUsageMb(16384.0);
        assertEquals(75.0, membership.getCpuUsagePercent());
        assertEquals(16384.0, membership.getMemoryUsageMb());
    }

    @Test
    void testLastHeartbeatUpdate() {
        LocalDateTime newHeartbeat = LocalDateTime.now();
        membership.setLastHeartbeat(newHeartbeat);
        assertEquals(newHeartbeat, membership.getLastHeartbeat());
    }
}
