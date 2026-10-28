package com.hyper.podsrx.hook;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReloadResourcesTest {
    @Test public void busySessionKeepsResourcesAndAllowsLaterRetirement() {
        ReloadResources resources = new ReloadResources();
        AtomicBoolean busy = new AtomicBoolean(true);
        List<Integer> closed = new ArrayList<>();
        resources.rejectWhile(() -> {
            assertTrue(resources.isActive());
            assertFalse(resources.canStartOperation());
            return busy.get();
        });
        resources.add(() -> closed.add(1));
        assertFalse(resources.retire());
        assertTrue(resources.isActive());
        assertTrue(resources.canStartOperation());
        assertTrue(closed.isEmpty());
        busy.set(false);
        assertTrue(resources.retire());
        assertEquals(List.of(1), closed);
        assertFalse(resources.isActive());
        assertFalse(resources.canStartOperation());
    }
    @Test public void failedCleanupStillReleasesOtherResourcesAndCannotBeRetried() {
        ReloadResources resources = new ReloadResources();
        List<Integer> closed = new ArrayList<>();
        resources.add(() -> closed.add(1));
        resources.add(() -> { closed.add(2); throw new IllegalStateException("failed"); });
        resources.add(() -> {
            assertFalse(resources.isActive());
            assertFalse(resources.canStartOperation());
            closed.add(3);
        });
        assertThrows(IllegalStateException.class, resources::retire);
        assertEquals(List.of(3, 2, 1), closed);
        assertFalse(resources.isActive());
        assertFalse(resources.canStartOperation());
        assertThrows(IllegalStateException.class, resources::retire);
        assertThrows(IllegalStateException.class, () -> resources.add(() -> {}));
    }
    @Test public void failedPreflightLeavesOldGenerationUsable() {
        ReloadResources resources = new ReloadResources();
        resources.rejectWhile(() -> {
            assertTrue(resources.isActive());
            assertFalse(resources.canStartOperation());
            throw new IllegalStateException("worker unavailable");
        });
        AtomicBoolean closed = new AtomicBoolean();
        resources.add(() -> closed.set(true));
        assertThrows(IllegalStateException.class, resources::retire);
        assertTrue(resources.isActive());
        assertTrue(resources.canStartOperation());
        assertFalse(closed.get());
    }
}
