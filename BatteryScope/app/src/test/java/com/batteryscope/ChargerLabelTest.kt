package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class ChargerLabelTest {
    @Test fun labelsOnlyFutureSamplesAndClearOnObservedBoundaries() {
        val tracker = ChargerLabelTracker()
        assertNull(tracker.assign("A",100))
        tracker.observeSource("AC")
        assertNull(tracker.assign("bad\nname",100))
        val a = checkNotNull(tracker.assign(" Charger A ",100))
        assertEquals("Charger A",a.name)
        assertNull(tracker.snapshot("AC",99))
        assertEquals(a,tracker.snapshot("AC",100))
        tracker.observeSource("AC")
        assertEquals(a,tracker.snapshot("AC",101))
        assertNull(tracker.snapshot("USB",101))
        tracker.boundary()
        assertNull(tracker.current())
        assertNull(tracker.assign("Charger A",102))
        tracker.observeSource("AC")
        val b = checkNotNull(tracker.assign("Charger A",103))
        assertNotEquals(a.assignment,b.assignment)
        tracker.observeSource("USB")
        assertNull(tracker.current())
        tracker.observeSource("NONE")
        assertNull(tracker.assign("B",104))
    }

    @Test fun invalidLabelMetadataDoesNotBecomeAnIdentity() {
        val label = ChargerLabel("A","AC","not-a-uuid",100)
        assertFalse(label.validAt("AC",100))
        assertNull(ChargerLabel.normalizedName("a".repeat(41)))
        assertNull(ChargerLabel.normalizedName(" "))
        assertNull(ChargerLabel.normalizedName("https://example.com"))
    }
}
