package com.papertrail.profiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import org.joda.time.Duration;
import org.junit.Test;

/**
 * Ported from scala
 */
public class CpuProfileTest
{
  @Test
  public void testRecord () throws Exception
  {
    final Thread t = new Thread ("CpuProfileTest")
    {
      @Override
      public void run ()
      {
        try
        {
          Thread.sleep (10_000);
        }
        catch (final InterruptedException ignored)
        {}
      }
    };
    t.setDaemon (true);
    t.start ();

    // Profile for 1000ms at 10 Hz => 100ms period; produces 10 samples.
    final CpuProfile profile = CpuProfile.record (Duration.standardSeconds (1), 10, Thread.State.TIMED_WAITING);
    assertNotNull (profile);
    assertEquals (10, profile.m_count);
    assertEquals (0, profile.m_missed);
    final OutputStream baos = new ByteArrayOutputStream ();
    profile.writeGoogleProfile (baos);
    assertTrue (baos.toString ().contains ("CpuProfileTest"));
    assertTrue (baos.toString ().contains ("Thread.sleep"));
  }

  @Test
  public void testisRunnable ()
  {
    assertTrue (CpuProfile.isRunnable (newElem ("foo", "bar")));
    assertFalse (CpuProfile.isRunnable (newElem ("sun.nio.ch.EPollArrayWrapper", "epollWait")));
    assertFalse (CpuProfile.isRunnable (newElem ("sun.nio.ch.KQueueArrayWrapper", "kevent0")));
    assertFalse (CpuProfile.isRunnable (newElem ("sun.nio.ch.ServerSocketChannelImpl", "accept0")));
  }

  public static StackTraceElement newElem (final String className, final String methodName)
  {
    return new StackTraceElement (className, methodName, "SomeFile.java", 1);
  }
}
