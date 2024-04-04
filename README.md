# Suck

A simple parallelized file downloader using Virtual Threads.  

[Example](./src/main/java/SuckCli.java)

```java
Suck.ofDefault().downloadParallelized(
   HttpRequest.newBuilder(URI.create(".....")),
   Path.of("....."),
   new ProgressAwareDownloadListener(retries));
```

# Feature

 - This is a PoC, or toy. Not intended for production use.
 - Uses mmap() and virtual threads.
 - Can be used as a library
 - Believe or not, it's quite fast, even comparable to aria2c.
 - Needs to be optimised.

A native image is also included in the release, but it's slower than running in hotspot. (SerialGC is to blame, I think)

# Roadmap
Probably won't do any of it.

 - Replace ModernHTTP which stresses the GC
 - Try regular FileChannel and see if mmap is any faster
 - Replace virtual threads?
