package com.papertrail.profiler.jaxrs;

import com.papertrail.profiler.CpuProfile;
import org.joda.time.Duration;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Path("/pprof")
public class CpuProfileResource {
    final Lock lock = new ReentrantLock();

    @Produces("pprof/raw")
    @GET
    @Path("profile")
    public byte[] profile(
            @QueryParam("duration") @DefaultValue("10") Integer duration,
            @QueryParam("frequency") @DefaultValue("100") Integer frequency) throws IOException {
        return doProfile(duration, frequency, Thread.State.RUNNABLE);
    }

    @Produces("pprof/raw")
    @GET
    @Path("contention")
    public byte[] contention(
            @QueryParam("duration") @DefaultValue("10") Integer duration,
            @QueryParam("frequency") @DefaultValue("100") Integer frequency) throws IOException {
        return doProfile(duration, frequency, Thread.State.BLOCKED);
    }

    protected byte[] doProfile(int duration, int frequency, Thread.State state) throws IOException {
        if (lock.tryLock()) {
            try {
                CpuProfile profile = CpuProfile.record(Duration.standardSeconds(duration), frequency, state);
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                if (profile == null) {
                    throw new RuntimeException("could not create CpuProfile");
                }
                profile.writeGoogleProfile(stream);
                return stream.toByteArray();
            } finally {
                lock.unlock();
            }
        }
        throw new RuntimeException("Only one profile request may be active at a time");
    }
}