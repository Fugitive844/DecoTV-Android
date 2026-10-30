package io.github.fugitive844.decotv.net;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 后台执行阻塞调用，结果回到主线程。 */
public final class Tasks {
    public interface Work<T> { T run() throws Exception; }
    public interface Done<T> { void ok(T value); }
    public interface Fail { void fail(Exception error); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private Tasks() {}

    public static <T> void run(Work<T> work, Done<T> done, Fail fail) {
        POOL.execute(() -> {
            try {
                T value = work.run();
                MAIN.post(() -> done.ok(value));
            } catch (Exception e) {
                MAIN.post(() -> fail.fail(e));
            }
        });
    }

    public static void post(Runnable r) { MAIN.post(r); }
}
