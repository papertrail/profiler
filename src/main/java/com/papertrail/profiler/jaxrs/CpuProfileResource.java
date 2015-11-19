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

@Path("/pprof")
public class CpuProfileResource {
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

    private byte[] doProfile(int duration, int frequency, Thread.State state) throws IOException {
        CpuProfile profile = CpuProfile.record(Duration.standardSeconds(duration), frequency, state);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        if (profile == null) {
            throw new RuntimeException("could not create CpuProfile");
        }
        profile.writeGoogleProfile(stream);
        return stream.toByteArray();
    }
}