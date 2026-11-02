package com.teahack.oxide.aim;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
public final class LatencyMonitor {
    private final LongAdder count=new LongAdder(),total=new LongAdder();
    private final AtomicLong maximum=new AtomicLong();
    public void record(long n) { if(n<0) throw new IllegalArgumentException("Negative latency"); count.increment(); total.add(n); maximum.accumulateAndGet(n,Math::max); }
    public long averageNanos() { long c=count.sum(); return c==0?0:total.sum()/c; }
    public long maximumNanos() { return maximum.get(); }
    public void reset() { count.reset(); total.reset(); maximum.set(0); }
}
