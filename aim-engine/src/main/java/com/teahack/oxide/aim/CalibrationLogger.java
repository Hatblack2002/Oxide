package com.teahack.oxide.aim;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
/** Bounded asynchronous logger; hot-path producers never wait for disk or sink I/O. */
public final class CalibrationLogger implements AutoCloseable {
    private final BlockingQueue<String> queue;
    private final Consumer<String> sink;
    private final AtomicBoolean running=new AtomicBoolean(true);
    private final LongAdder dropped=new LongAdder();
    private final Thread worker;
    public CalibrationLogger(Consumer<String> sink) { this(256,sink); }
    public CalibrationLogger(int capacity,Consumer<String> sink) {
        if(capacity<=0||sink==null) throw new IllegalArgumentException("Invalid logger configuration");
        queue=new ArrayBlockingQueue<>(capacity); this.sink=sink;
        worker=new Thread(this::consume,"oxide-aim-calibration-log"); worker.setDaemon(true); worker.start();
    }
    public boolean log(String event) {
        if(event==null||!running.get()) { dropped.increment(); return false; }
        boolean ok=queue.offer(System.nanoTime()+" | "+event); if(!ok) dropped.increment(); return ok;
    }
    public long droppedCount() { return dropped.sum(); }
    public int queuedCount() { return queue.size(); }
    private void consume() {
        while(running.get()||!queue.isEmpty()) {
            try { String item=queue.poll(100,TimeUnit.MILLISECONDS); if(item!=null) sink.accept(item); }
            catch(InterruptedException e) { if(running.get()) continue; }
            catch(RuntimeException e) { dropped.increment(); }
        }
    }
    public void close() {
        if(running.compareAndSet(true,false)) {
            worker.interrupt();
            try { worker.join(500); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
