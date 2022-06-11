package lzk;


import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author lzk
 * @Email 1801290586@qq.com
 * @Description <类说明>测试类
 * @Date 15:22 2022/5/2
 */
public class Demo {
    public static void main(String[] args) throws InterruptedException {
        ThreadPool threadPool=new ThreadPool(2, 4, 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10), new LzkThreadFactory() {
            AtomicInteger tid=new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable task) {
                return new Thread(task,"t"+tid.incrementAndGet());
            }
        },new ThreadPool.DiscardPolicy());
        AtomicInteger atomicInteger=new AtomicInteger(0);
        CountDownLatch countDownLatch=new CountDownLatch(10);
        for (int j=0;j<14;j++){
            threadPool.execute(()->{
                for (int i=0;i<100;i++){

                    atomicInteger.incrementAndGet();
                }
                System.out.println(Thread.currentThread().getName()+" "+atomicInteger.get());
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
            for (int j=0;j<4;j++){
                threadPool.execute(()->{
                    for (int i=0;i<100;i++){
                        atomicInteger.incrementAndGet();
                    }
                    System.out.println(Thread.currentThread().getName()+" "+atomicInteger.get());
                });
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        threadPool.shutdown();
        System.out.println("结束");
    }
}
