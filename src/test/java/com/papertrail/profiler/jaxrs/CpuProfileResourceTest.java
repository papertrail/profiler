package com.papertrail.profiler.jaxrs;

import static junit.framework.TestCase.assertEquals;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class CpuProfileResourceTest
{
  @Test
  public void testDoProfile () throws Exception
  {
    final CpuProfileResource resource = new CpuProfileResource ();
    final CountDownLatch latch = new CountDownLatch (1);
    final Thread t1 = new Thread ()
    {
      @Override
      public void run ()
      {
        latch.countDown ();
        try
        {
          resource.doProfile (1, 100, State.BLOCKED);
        }
        catch (final IOException e)
        {
          throw new RuntimeException (e);
        }
      }
    };
    t1.setDaemon (true);
    t1.start ();
    latch.await ();
    // Even though we await the latch to start the thread, we can still race, so
    // mitigate with a short sleep
    Thread.sleep (10);
    try
    {
      resource.doProfile (1, 100, Thread.State.BLOCKED);
    }
    catch (final RuntimeException ex)
    {
      assertEquals ("Only one profile request may be active at a time", ex.getMessage ());
    }
  }
}
