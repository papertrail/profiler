package com.papertrail.profiler;

import org.joda.time.Duration;
import org.joda.time.Instant;

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
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;


/**
 * A CPU profile.
 */
public class CpuProfile {
    private final Map<List<StackTraceElement>, Long> counts;
    public final Duration duration;
    public final long count;
    public final long missed;

    public CpuProfile(Map<List<StackTraceElement>, Long> counts, Duration duration, long count, long missed) {
        this.counts = counts;
        this.duration = duration;
        this.count = count;
        this.missed = missed;
    }

    private static class Word {
        final ByteBuffer buf;
        final OutputStream os;

        public Word(OutputStream os) {
            this(createBuffer(), os);
        }

        private Word(ByteBuffer buf, OutputStream os) {
            this.buf = buf;
            this.os = os;
        }

        public void putWord(long n) throws IOException {
            buf.clear();
            buf.putLong(n);
            os.write(buf.array());
        }

        public void putString(String s) throws IOException {
            os.write(s.getBytes());
        }

        private static ByteBuffer createBuffer() {
            final ByteBuffer buf = ByteBuffer.allocate(8);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            return buf;
        }

        public void flush() throws IOException {
            os.flush();
        }
    }

    /**
     * Write a Google pprof-compatible profile to `out`. The format is
     * documented here:
     * http://google-perftools.googlecode.com/svn/trunk/doc/cpuprofile-fileformat.html
     *
     * @param out And OutputStream to which the pprof output will be written
     * @throws IOException if any operation on the OutputStream fails
     */
    public void writeGoogleProfile(OutputStream out) throws IOException {
        int next = 1;
        Map<StackTraceElement, Integer> uniq = new HashMap<>();
        Word word = new Word(out);
        word.putString(String.format("--- symbol\nbinary=%s\n", mainClassName()));
        for (Map.Entry<List<StackTraceElement>, Long> stack : counts.entrySet()) {
            for (StackTraceElement frame : stack.getKey()) {
                if (!uniq.containsKey(frame)) {
                    word.putString(String.format("0x%016x %s\n", next, frame.toString()));
                    uniq.put(frame, next);
                    next += 1;
                }
            }
        }
        word.putString("---\n--- profile\n");
        for (int w : new int[]{0, 3, 0, 1, 0}) {
            word.putWord(w);
        }
        for (Map.Entry<List<StackTraceElement>, Long> entry : counts.entrySet()) {
            List<StackTraceElement> stack = entry.getKey();
            long n = entry.getValue();
            if (!stack.isEmpty()) {
                word.putWord(n);
                word.putWord(stack.size());
            }
            for (StackTraceElement frame : stack) {
                word.putWord(uniq.get(frame));
            }
        }
        word.putWord(0);
        word.putWord(1);
        word.putWord(0);
        word.flush();
    }

    static class StringPair {
        final String a;
        final String b;

        StringPair(String a, String b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StringPair that = (StringPair) o;
            return Objects.equals(a, that.a) &&
                    Objects.equals(b, that.b);
        }

        @Override
        public int hashCode() {
            return Objects.hash(a, b);
        }
    }

    // (class name, method names) that say they are runnable, but are actually doing nothing.
    private static final Set<StringPair> idleClassAndMethod = new HashSet<>();

    static {
        idleClassAndMethod.add(new StringPair("sun.nio.ch.EPollArrayWrapper", "epollWait"));
        idleClassAndMethod.add(new StringPair("sun.nio.ch.KQueueArrayWrapper", "kevent0"));
        idleClassAndMethod.add(new StringPair("java.net.SocketInputStream", "socketRead0"));
        idleClassAndMethod.add(new StringPair("java.net.SocketOutputStream", "socketWrite0"));
        idleClassAndMethod.add(new StringPair("java.net.PlainSocketImpl", "socketAvailable"));
        idleClassAndMethod.add(new StringPair("java.net.PlainSocketImpl", "socketAccept"));
        idleClassAndMethod.add(new StringPair("sun.nio.ch.ServerSocketChannelImpl", "accept0"));
    }

    /**
     * When looking for RUNNABLEs, the JVM's notion of runnable differs from the
     * from kernel's definition and for some well known cases, we can filter
     * out threads that are actually asleep.
     * See http://www.brendangregg.com/blog/2014-06-09/java-cpu-sampling-using-hprof.html
     *
     * @param elem StackTraceElement to check
     * @return true if it's not a known idle method
     */
    protected static boolean isRunnable(StackTraceElement elem) {
        return !idleClassAndMethod.contains(
                new StringPair(elem.getClassName(), elem.getMethodName()));
    }

    /**
     * Profile CPU usage of threads in `state` for `howlong`, sampling
     * stacks at `frequency` Hz.
     * As an example, using Nyquist's sampling theorem, we see that
     * sampling at 100Hz will accurately represent components 50Hz or
     * less; ie. any stack that contributes 2% or more to the total CPU
     * time.
     * Note that the maximum sampling frequency is set to 1000Hz.
     * Anything greater than this is likely to consume considerable
     * amounts of CPU while sampling.
     * The profiler will discount its own stacks.
     * TODO:
     * - Should we synthesize GC frames? GC has significant runtime
     * impact, so it seems nonfaithful to exlude them.
     * - Limit stack depth?
     *
     * @param howlong Duration of profile
     * @param frequency polling interval
     * @param state Thread.State to match against
     * @return CpuProfile results
     */
    public static CpuProfile record(Duration howlong, int frequency, Thread.State state) {
        /*
        PLEASE NOTE: I modified this code to use millisecond precision as the original code that used microsecond
        precision was incorrect. Each time it looked at the clock or slept, it was using millis under the hood.
        */
        if (frequency > 1000) {
            throw new RuntimeException("frequency must be < 1000");
        }

        // TODO: it may make sense to write a custom hash function here
        // that needn't traverse the all stack trace elems. Usually, the
        // top handful of frames are distinguishing.
        Map<List<StackTraceElement>, Long> counts = new HashMap<>();
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Instant start = Instant.now();
        Instant end = start.plus(howlong);
        int periodMillis = 1000 / frequency;
        long myId = Thread.currentThread().getId();
        Instant next = Instant.now();

        long n = 0;
        long nmissed = 0;

        while (Instant.now().isBefore(end)) {
            for (ThreadInfo thread : bean.dumpAllThreads(false, false)) {
                if (thread.getThreadState() == state && thread.getThreadId() != myId) {
                    List<StackTraceElement> s = Arrays.asList(thread.getStackTrace());
                    if (!s.isEmpty()) {
                        boolean include = state != Thread.State.RUNNABLE || isRunnable(s.get(0));
                        if (include) {
                            if (counts.get(s) == null) {
                                counts.put(s, 1L);
                            } else {
                                long count = counts.get(s);
                                counts.put(s, count + 1L);
                            }
                        }
                    }
                }
            }
            n += 1;
            next = next.plus(periodMillis);

            while (next.isBefore(Instant.now()) && next.isBefore(end)) {
                nmissed += 1;
                next = next.plus(periodMillis);
            }

            long sleep = Math.max((next.getMillis() - Instant.now().getMillis()), 0);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                System.out.print("CpuProfile interrupted.");
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return new CpuProfile(counts, new Duration(start, Instant.now()), n, nmissed);
    }

    public CpuProfile record(Duration howlong, int frequency) {
        return record(howlong, frequency, Thread.State.RUNNABLE);
    }

    /**
     * Call `record` in a thread with the given parameters, returning a
     * `Future` representing the completion of the profile.
     *
     * @param howlong Duration of profile
     * @param frequency polling interval
     * @param state Thread.State to match against
     * @return Future contiaining CpuProfile results
     */
    public static Future<CpuProfile> recordInThread(final Duration howlong, final int frequency, final Thread.State state) {
        final FutureTask<CpuProfile> future = new FutureTask<>(new Callable<CpuProfile>() {
            @Override
            public CpuProfile call() throws Exception {
                return record(howlong, frequency, state);
            }
        });
        Thread t = new Thread(future, "CpuProfile");
        t.start();
        return future;
    }

    public static Future<CpuProfile> recordInThread(Duration howlong, int frequency) {
        return recordInThread(howlong, frequency, Thread.State.RUNNABLE);
    }

    // Ripped and ported from Twitter's Jvm trait

    /**
     * Get the main class name for the currently running application.
     * Note that this works only by heuristic, and may not be accurate.
     * TODO: take into account the standard callstack around scala
     * invocations better.
     *
     * @return main class name
     */
    public static String mainClassName() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            StackTraceElement[] elements = entry.getValue();
            if ("main".equals(t.getName())) {
                for (int i = elements.length - 1; i >= 0; i--) {
                    StackTraceElement elem = elements[i];
                    if (!elem.getClassName().startsWith("scala.tools.nsc.MainGenericRunner")) {
                        return elem.getClassName();
                    }
                }
            }
        }
        return "unknown";
    }
}