package io.mesalabs.rezoss.ai;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Tests callback ownership and cleanup using the production request-state helper. */
public final class ReplyRequestsTest {
    private static int checked;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checked++;
    }
    public static final class Scope implements kotlinx.coroutines.x {
        volatile boolean running = true;
        public boolean active() { return running; }
    }
    public static class Resource {
        final AtomicInteger closed = new AtomicInteger(), released = new AtomicInteger();
        public void close() { closed.incrementAndGet(); }
        public void release() { released.incrementAndGet(); }
    }
    public static final class BrokenResource extends Resource {
        public void close() { super.close(); throw new IllegalStateException("simulated close failure"); }
    }
    private static final class Request {
        final AtomicReference<Object> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Scope scope = new Scope();
        final Resource processor;
        Request() { this(new Resource()); }
        Request(Resource processor) {
            this.processor = processor;
            ReplyRequests.begin(result, latch, scope);
            ReplyRequests.processor(result, processor);
        }
        CountDownLatch finish() { return ReplyRequests.finish(result); }
    }
    private static void overlap() throws Exception {
        Request a = new Request(), b = new Request();
        ReplyRequests.publish(a.result, "A");
        check(a.latch.getCount() == 0 && b.latch.getCount() == 1, "A must wake only A");
        check("A".equals(a.result.get()) && b.result.get() == null, "A must write only A");
        check(a.finish() == a.latch, "A completion remains deliverable");
        check(a.processor.closed.get() == 1 && b.processor.closed.get() == 0, "A closes only its processor");
        check(ReplyRequests.processor(b.result) == b.processor, "B processor remains available");
        ReplyRequests.publish(b.result, "B");
        ReplyRequests.await(b.result, 1);
        check(b.finish() == b.latch && "B".equals(b.result.get()), "B finishes independently");
        check(a.finish() == null && a.processor.closed.get() == 1, "cleanup is idempotent");
    }
    private static void orders() {
        for (int[] order : new int[][]{{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}}) {
            Request[] requests = {new Request(), new Request(), new Request()};
            for (int i : order) {
                ReplyRequests.publish(requests[i].result, "reply-" + i);
                requests[i].finish();
                for (int j=0; j<3; j++) {
                    Object value=requests[j].result.get();
                    check(value == null || value.equals("reply-" + j), "crossed response");
                }
            }
            for (Request r : requests) check(r.processor.closed.get() == 1, "permutation cleanup");
        }
    }
    private static void timeoutAndLateCallback() throws Exception {
        Request a = new Request(), b = new Request();
        ReplyRequests.await(a.result, 0);
        check(a.finish() == a.latch && a.result.get() == null, "timeout retains original empty-result failure path");
        ReplyRequests.publish(a.result, "late");
        check(a.result.get() == null && b.latch.getCount() == 1, "late callback ignored");
        ReplyRequests.publish(b.result, "first");
        ReplyRequests.publish(b.result, "duplicate");
        check("first".equals(b.result.get()), "duplicate completion ignored");
        b.finish();
    }
    private static void cancellation() throws Exception {
        Request a = new Request(), b = new Request();
        a.scope.running = false;
        long start = System.nanoTime();
        ReplyRequests.await(a.result, 45);
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 1000, "cancelled scope must not wait 45 seconds");
        check(a.finish() == null && a.processor.closed.get() == 1, "cancelled result suppressed and closed");
        check(b.latch.getCount() == 1 && b.processor.closed.get() == 0, "exit must not affect other scope");
        ReplyRequests.publish(b.result, "ready");
        b.scope.running = false;
        check(b.finish() == null, "cancellation at delivery boundary suppresses stale result");
        Request c = new Request();
        c.scope.running = false;
        check(ReplyRequests.processor(c.result) == null, "cancel before dispatch skips inference");
        ReplyRequests.publish(c.result, "cancelled callback");
        check(c.result.get() == null, "cancelled publish ignored");
        c.finish();
    }
    private static void liveCancellation() throws Exception {
        Request a = new Request();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { started.countDown(); ReplyRequests.await(a.result, 45); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.start(); started.await(); a.scope.running = false;
        worker.join(2000);
        check(!worker.isAlive() && failure.get() == null, "running wait observes coroutine cancellation");
        check(a.finish() == null, "running cancellation suppresses delivery");
    }
    private static void exceptionalCleanup() throws Exception {
        Request a = new Request();
        Thread.currentThread().interrupt();
        try { ReplyRequests.await(a.result, 45); throw new AssertionError("interrupt ignored"); }
        catch (InterruptedException expected) { check(a.finish() == null, "interruption cancels owned request"); }
        finally { Thread.interrupted(); }
        Resource memory = new Resource();
        Request b = new Request(new BrokenResource());
        ReplyRequests.memory(b.result, memory);
        b.finish();
        check(b.processor.closed.get() == 1 && memory.released.get() == 1, "failed close still releases memory");
        check(b.finish() == null, "failed close still removes state");
        AtomicReference<Object> setupFailure = new AtomicReference<>();
        ReplyRequests.begin(setupFailure, new CountDownLatch(1), null);
        try { throw new IllegalStateException("factory failed"); }
        catch (IllegalStateException expected) { ReplyRequests.finish(setupFailure); }
        ReplyRequests.publish(setupFailure, "late");
        check(setupFailure.get() == null, "exception before processor attachment removes registration");
    }
    private static void sharedMemory() {
        Resource memory = new Resource();
        Request a = new Request(), b = new Request();
        ReplyRequests.memory(a.result, memory); ReplyRequests.memory(b.result, memory);
        a.finish();
        check(memory.released.get() == 0, "first request must retain shared memory lease");
        b.finish();
        check(memory.released.get() == 1, "last request releases shared memory once");
    }
    private static void concurrentCompletion() throws Exception {
        for (int i=0;i<100;i++) {
            Request a=new Request();
            CountDownLatch go=new CountDownLatch(1);
            Thread callback=new Thread(() -> {
                try { go.await(); ReplyRequests.publish(a.result, "complete"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
            callback.start(); go.countDown(); a.finish(); callback.join();
            Object previous=a.result.get(); ReplyRequests.publish(a.result, "late");
            check(previous == a.result.get() && a.processor.closed.get() == 1, "completion/cleanup race");
        }
    }
    public static void main(String[] args) throws Exception {
        overlap(); orders(); timeoutAndLateCallback(); cancellation(); liveCancellation();
        exceptionalCleanup(); sharedMemory(); concurrentCompletion();
        System.out.println("PASS " + checked + " reply request ownership assertions");
    }
}
