package com.ricodevvv.codinject;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void comparesLikeMaven() {
        assertTrue(Versions.compare("2.11.0", "2.9") > 0);
        assertTrue(Versions.compare("1.0", "1.0-SNAPSHOT") > 0);
        assertTrue(Versions.compare("1.0-rc1", "1.0-beta2") > 0);
        assertTrue(Versions.compare("1.0-alpha", "1.0") < 0);
        assertTrue(Versions.compare("1.0.1", "1.0-sp") > 0);
        assertTrue(Versions.compare("4.1.100.Final", "4.1.99.Final") > 0);
        assertTrue(Versions.compare("20240303", "20211205") > 0);
        assertEquals(0, Versions.compare("1.0", "1.0.0"));
        assertEquals(0, Versions.compare("1.0.Final", "1.0"));
    }

    @Test
    void checksRanges() {
        assertTrue(Versions.inRange("[1.0,2.0)", "1.0"));
        assertTrue(Versions.inRange("[1.0,2.0)", "1.9.9"));
        assertFalse(Versions.inRange("[1.0,2.0)", "2.0"));
        assertTrue(Versions.inRange("(,1.5]", "1.5"));
        assertFalse(Versions.inRange("(1.0,)", "1.0"));
        assertTrue(Versions.inRange("[1.2]", "1.2"));
        assertTrue(Versions.inRange("[1.0,1.2],[1.5,)", "1.6"));
        assertFalse(Versions.inRange("[1.0,1.2],[1.5,)", "1.3"));
    }

    @Test
    void picksTheHighestVersionInRange() {
        List<String> available = Arrays.asList("1.0", "1.5", "2.0-beta", "2.0", "2.1");
        assertEquals("1.5", Versions.pick("[1.0,2.0-beta)", available));
        assertEquals("2.1", Versions.pick("[2.0,)", available));
        assertNull(Versions.pick("[3.0,)", available));
        assertEquals("1.0", Versions.lowerBound("[1.0,2.0)"));
        assertNull(Versions.lowerBound("(1.0,2.0)"));
    }
}
