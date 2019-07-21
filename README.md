This is a fork of https://github.com/papertrail/profiler/ version 1.0.3-SNAPSHOT

* Requires Java 8 and therefore no dependency on joda-time anymore (since v1.1.0)
* Now as OSGI bundle (since v1.1.1)

It's meant to be a drop-in replacement using the package name as the original

# JVM cpu profiler

A pure-java implementation of the [twitter/util](https://github.com/twitter/util) project's `CpuProfile` and related
classes. 

Original Scala sources:

  * [CpuProfile.scala](https://github.com/twitter/util/blob/develop/util-jvm/src/main/scala/com/twitter/jvm/CpuProfile.scala)
  * [CpuProfileTest.scala](https://github.com/twitter/util/blob/develop/util-jvm/src/test/scala/com/twitter/jvm/CpuProfileTest.scala)

## Usage

The `CpuProfile.record` method will record samples of stacktrace elements and return a `CpuProfile` object. That object
can then be written into a [`pprof`](https://github.com/gperftools/gperftools)-parseable format using
`CpuProfile.writeGoogleProfile`.

There is a provided JAX-RS resource that makes this simple to use with an http service. For example, with a
[Dropwizard](http://dropwizard.io/) application:

```java 
environment.jersey().register(CpuProfileResource.class);
```

Which exposes the URLs `/pprof/contention` that detects blocked threads, and `/pprof/profile` that detects runnable
threads. Here is an example of using `curl` to retrieve a profile and turn it into a PDF:

```bash
curl http://localhost:8181/pprof/contention > prof
pprof --pdf prof > profile.pdf
```

## Maven usage

```xml
<dependency>
  <groupId>com.helger</groupId>
  <artifactId>profiler</artifactId>
  <version>1.1.1</version>
</dependency>
```
