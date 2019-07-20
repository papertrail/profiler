package com.papertrail.profiler.jaxrs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import com.papertrail.profiler.CpuProfile;

@Path ("/pprof")
public class CpuProfileResource
{
  private final Lock lock = new ReentrantLock ();

  @Produces ("pprof/raw")
  @GET
  @Path ("profile")
  public byte [] profile (@QueryParam ("duration") @DefaultValue ("10") final Integer duration,
                          @QueryParam ("frequency") @DefaultValue ("100") final Integer frequency) throws IOException
  {
    return doProfile (duration.intValue (), frequency.intValue (), Thread.State.RUNNABLE);
  }

  @Produces ("pprof/raw")
  @GET
  @Path ("contention")
  public byte [] contention (@QueryParam ("duration") @DefaultValue ("10") final Integer duration,
                             @QueryParam ("frequency") @DefaultValue ("100") final Integer frequency) throws IOException
  {
    return doProfile (duration.intValue (), frequency.intValue (), Thread.State.BLOCKED);
  }

  protected byte [] doProfile (final int duration, final int frequency, final Thread.State state) throws IOException
  {
    if (lock.tryLock ())
    {
      try (final ByteArrayOutputStream stream = new ByteArrayOutputStream ())
      {
        final CpuProfile profile = CpuProfile.record (Duration.ofSeconds (duration), frequency, state);
        if (profile == null)
        {
          throw new RuntimeException ("could not create CpuProfile");
        }
        profile.writeGoogleProfile (stream);
        return stream.toByteArray ();
      }
      finally
      {
        lock.unlock ();
      }
    }
    throw new RuntimeException ("Only one profile request may be active at a time");
  }
}
