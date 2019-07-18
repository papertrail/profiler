package com.papertrail.profiler;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * A CPU profile.
 */
public class CpuProfile
{
  private final Map <List <StackTraceElement>, Long> m_counts;
  public final Duration m_duration;
  public final long m_count;
  public final long m_missed;

  public CpuProfile (final Map <List <StackTraceElement>, Long> counts,
                     final Duration duration,
                     final long count,
                     final long missed)
  {
    this.m_counts = counts;
    this.m_duration = duration;
    this.m_count = count;
    this.m_missed = missed;
  }

  private static class Word
  {
    final ByteBuffer m_buf;
    final OutputStream m_os;

    public Word (final OutputStream os)
    {
      this (createBuffer (), os);
    }

    private Word (final ByteBuffer buf, final OutputStream os)
    {
      this.m_buf = buf;
      this.m_os = os;
    }

    public void putWord (final long n) throws IOException
    {
      m_buf.clear ();
      m_buf.putLong (n);
      m_os.write (m_buf.array ());
    }

    public void putString (final String s) throws IOException
    {
      m_os.write (s.getBytes ());
    }

    private static ByteBuffer createBuffer ()
    {
      final ByteBuffer buf = ByteBuffer.allocate (8);
      buf.order (ByteOrder.LITTLE_ENDIAN);
      return buf;
    }

    public void flush () throws IOException
    {
      m_os.flush ();
    }
  }

  /**
   * Write a Google pprof-compatible profile to `out`. The format is documented
   * here:
   * http://google-perftools.googlecode.com/svn/trunk/doc/cpuprofile-fileformat.html
   *
   * @param out
   *        And OutputStream to which the pprof output will be written
   * @throws IOException
   *         if any operation on the OutputStream fails
   */
  public void writeGoogleProfile (final OutputStream out) throws IOException
  {
    int next = 1;
    final Map <StackTraceElement, Integer> uniq = new HashMap <> ();
    final Word word = new Word (out);
    word.putString (String.format ("--- symbol\nbinary=%s\n", mainClassName ()));
    for (final Map.Entry <List <StackTraceElement>, Long> stack : m_counts.entrySet ())
    {
      for (final StackTraceElement frame : stack.getKey ())
      {
        if (!uniq.containsKey (frame))
        {
          word.putString (String.format ("0x%016x %s\n", Integer.valueOf (next), frame.toString ()));
          uniq.put (frame, Integer.valueOf (next));
          next += 1;
        }
      }
    }
    word.putString ("---\n--- profile\n");
    for (final int w : new int [] { 0, 3, 0, 1, 0 })
    {
      word.putWord (w);
    }
    for (final Map.Entry <List <StackTraceElement>, Long> entry : m_counts.entrySet ())
    {
      final List <StackTraceElement> stack = entry.getKey ();
      final long n = entry.getValue ().longValue ();
      if (!stack.isEmpty ())
      {
        word.putWord (n);
        word.putWord (stack.size ());
      }
      for (final StackTraceElement frame : stack)
      {
        word.putWord (uniq.get (frame).longValue ());
      }
    }
    word.putWord (0);
    word.putWord (1);
    word.putWord (0);
    word.flush ();
  }

  static class StringPair
  {
    final String m_a;
    final String m_b;

    StringPair (final String a, final String b)
    {
      this.m_a = a;
      this.m_b = b;
    }

    @Override
    public boolean equals (final Object o)
    {
      if (this == o)
        return true;
      if (o == null || getClass () != o.getClass ())
        return false;
      final StringPair that = (StringPair) o;
      return Objects.equals (m_a, that.m_a) && Objects.equals (m_b, that.m_b);
    }

    @Override
    public int hashCode ()
    {
      return Objects.hash (m_a, m_b);
    }
  }

  // (class name, method names) that say they are runnable, but are actually
  // doing nothing.
  private static final Set <StringPair> idleClassAndMethod = new HashSet <> ();

  static
  {
    idleClassAndMethod.add (new StringPair ("sun.nio.ch.EPollArrayWrapper", "epollWait"));
    idleClassAndMethod.add (new StringPair ("sun.nio.ch.KQueueArrayWrapper", "kevent0"));
    idleClassAndMethod.add (new StringPair ("java.net.SocketInputStream", "socketRead0"));
    idleClassAndMethod.add (new StringPair ("java.net.SocketOutputStream", "socketWrite0"));
    idleClassAndMethod.add (new StringPair ("java.net.PlainSocketImpl", "socketAvailable"));
    idleClassAndMethod.add (new StringPair ("java.net.PlainSocketImpl", "socketAccept"));
    idleClassAndMethod.add (new StringPair ("sun.nio.ch.ServerSocketChannelImpl", "accept0"));
  }

  /**
   * When looking for RUNNABLEs, the JVM's notion of runnable differs from the
   * from kernel's definition and for some well known cases, we can filter out
   * threads that are actually asleep. See
   * http://www.brendangregg.com/blog/2014-06-09/java-cpu-sampling-using-hprof.html
   *
   * @param elem
   *        StackTraceElement to check
   * @return true if it's not a known idle method
   */
  protected static boolean isRunnable (final StackTraceElement elem)
  {
    return !idleClassAndMethod.contains (new StringPair (elem.getClassName (), elem.getMethodName ()));
  }

  /**
   * Profile CPU usage of threads in `state` for `howlong`, sampling stacks at
   * `frequency` Hz. As an example, using Nyquist's sampling theorem, we see
   * that sampling at 100Hz will accurately represent components 50Hz or less;
   * ie. any stack that contributes 2% or more to the total CPU time. Note that
   * the maximum sampling frequency is set to 1000Hz. Anything greater than this
   * is likely to consume considerable amounts of CPU while sampling. The
   * profiler will discount its own stacks. TODO: - Should we synthesize GC
   * frames? GC has significant runtime impact, so it seems nonfaithful to
   * exlude them. - Limit stack depth?
   *
   * @param howlong
   *        Duration of profile
   * @param frequency
   *        polling interval
   * @param state
   *        Thread.State to match against
   * @return CpuProfile results
   */
  public static CpuProfile record (final Duration howlong, final int frequency, final Thread.State state)
  {
    /*
     * PLEASE NOTE: I modified this code to use millisecond precision as the
     * original code that used microsecond precision was incorrect. Each time it
     * looked at the clock or slept, it was using millis under the hood.
     */
    if (frequency > 1000)
    {
      throw new RuntimeException ("frequency must be < 1000");
    }

    // TODO: it may make sense to write a custom hash function here
    // that needn't traverse the all stack trace elems. Usually, the
    // top handful of frames are distinguishing.
    final Map <List <StackTraceElement>, Long> counts = new HashMap <> ();
    final ThreadMXBean bean = ManagementFactory.getThreadMXBean ();
    final Instant start = Instant.now ();
    final Instant end = start.plus (howlong);
    final int periodMillis = 1000 / frequency;
    final long myId = Thread.currentThread ().getId ();
    Instant next = Instant.now ();

    long n = 0;
    long nmissed = 0;

    while (Instant.now ().isBefore (end))
    {
      for (final ThreadInfo thread : bean.dumpAllThreads (false, false))
      {
        if (thread.getThreadState () == state && thread.getThreadId () != myId)
        {
          final List <StackTraceElement> s = Arrays.asList (thread.getStackTrace ());
          if (!s.isEmpty ())
          {
            final boolean include = state != Thread.State.RUNNABLE || isRunnable (s.get (0));
            if (include)
            {
              final Long count = counts.get (s);
              if (count == null)
              {
                counts.put (s, Long.valueOf (1L));
              }
              else
              {
                counts.put (s, Long.valueOf (count.longValue () + 1));
              }
            }
          }
        }
      }
      n += 1;
      next = next.plus (periodMillis);

      while (next.isBefore (Instant.now ()) && next.isBefore (end))
      {
        nmissed += 1;
        next = next.plus (periodMillis);
      }

      final long sleep = Math.max ((next.getMillis () - Instant.now ().getMillis ()), 0);
      try
      {
        Thread.sleep (sleep);
      }
      catch (final InterruptedException e)
      {
        System.out.print ("CpuProfile interrupted.");
        Thread.currentThread ().interrupt ();
        return null;
      }
    }

    return new CpuProfile (counts, new Duration (start, Instant.now ()), n, nmissed);
  }

  public CpuProfile record (final Duration howlong, final int frequency)
  {
    return record (howlong, frequency, Thread.State.RUNNABLE);
  }

  /**
   * Call `record` in a thread with the given parameters, returning a `Future`
   * representing the completion of the profile.
   *
   * @param howlong
   *        Duration of profile
   * @param frequency
   *        polling interval
   * @param state
   *        Thread.State to match against
   * @return Future containing CpuProfile results
   */
  public static Future <CpuProfile> recordInThread (final Duration howlong,
                                                    final int frequency,
                                                    final Thread.State state)
  {
    final FutureTask <CpuProfile> future = new FutureTask <> ( () -> record (howlong, frequency, state));
    final Thread t = new Thread (future, "CpuProfile");
    t.start ();
    return future;
  }

  public static Future <CpuProfile> recordInThread (final Duration howlong, final int frequency)
  {
    return recordInThread (howlong, frequency, Thread.State.RUNNABLE);
  }

  // Ripped and ported from Twitter's Jvm trait

  /**
   * Get the main class name for the currently running application. Note that
   * this works only by heuristic, and may not be accurate. TODO: take into
   * account the standard callstack around scala invocations better.
   *
   * @return main class name
   */
  public static String mainClassName ()
  {
    for (final Map.Entry <Thread, StackTraceElement []> entry : Thread.getAllStackTraces ().entrySet ())
    {
      final Thread t = entry.getKey ();
      final StackTraceElement [] elements = entry.getValue ();
      if ("main".equals (t.getName ()))
      {
        for (int i = elements.length - 1; i >= 0; i--)
        {
          final StackTraceElement elem = elements[i];
          if (!elem.getClassName ().startsWith ("scala.tools.nsc.MainGenericRunner"))
          {
            return elem.getClassName ();
          }
        }
      }
    }
    return "unknown";
  }
}
