package com.papertrail.profiler;

import org.joda.time.Duration;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

/**
 * Ported from scala
 */
public class CpuProfileTest {
    @Test
    public void testRecord() throws Exception {
        Thread t = new Thread("CpuProfileTest") {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) {
                }
            }
        };
        t.setDaemon(true);
        t.start();

        // Profile for 1000ms at 10 Hz => 100ms period; produces 10 samples.
        CpuProfile profile = CpuProfile.record(Duration.standardSeconds(1), 10, Thread.State.TIMED_WAITING);
        assertNotNull(profile);
        assertEquals(10, profile.count);
        assertEquals(0, profile.missed);
        OutputStream baos = new ByteArrayOutputStream();
        profile.writeGoogleProfile(baos);
        assertTrue(baos.toString().contains("CpuProfileTest"));
        assertTrue(baos.toString().contains("Thread.sleep"));
    }

    @Test
    public void testisRunnable() {
        assertTrue(CpuProfile.isRunnable(newElem("foo", "bar")));
        assertFalse(CpuProfile.isRunnable(newElem("sun.nio.ch.EPollArrayWrapper", "epollWait")));
        assertFalse(CpuProfile.isRunnable(newElem("sun.nio.ch.KQueueArrayWrapper", "kevent0")));
    }

    public static StackTraceElement newElem(String className, String methodName) {
        return new StackTraceElement(className, methodName, "SomeFile.java", 1);
    }
}