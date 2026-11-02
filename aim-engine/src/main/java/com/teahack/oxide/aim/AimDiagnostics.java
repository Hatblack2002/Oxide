package com.teahack.oxide.aim;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
public final class AimDiagnostics {
    private final LongAdder frames=new LongAdder(),frameNanos=new LongAdder(),errors=new LongAdder(),updates=new LongAdder();
    private final AtomicLong maxFrame=new AtomicLong();
    private final LatencyMonitor latency=new LatencyMonitor();
    public void recordFrame(long n) { if(n<0) throw new IllegalArgumentException("Negative frame duration"); frames.increment(); frameNanos.add(n); maxFrame.accumulateAndGet(n,Math::max); }
    public void recordLatency(long n) { latency.record(n); }
    public void recordInputError() { errors.increment(); }
    public void recordConfigurationUpdate() { updates.increment(); }
    public FrameMetrics snapshot() { long c=frames.sum(); return new FrameMetrics(c,c==0?0:frameNanos.sum()/c,maxFrame.get(),latency.averageNanos(),latency.maximumNanos(),errors.sum(),updates.sum()); }
    public void reset() { frames.reset(); frameNanos.reset(); maxFrame.set(0); errors.reset(); updates.reset(); latency.reset(); }
}
