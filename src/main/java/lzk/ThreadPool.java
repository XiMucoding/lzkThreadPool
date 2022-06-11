package lzk;

import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @Author lzk
 * @Email 1801290586@qq.com
 * @Description <类说明>手写线程池子实现类
 * @Date 1:10 2022/5/3
 */
public class ThreadPool {
    // 核心线程数
    private int corePoolSize;
    // 最大线程数
    private int maximumPoolSize;
    // 救急线程无使用时的最长时间
    private long keepAliveTime;
    // 时间单位
    private TimeUnit unit;
    // 任务队列
    private BlockingQueue<Runnable> workQueue;
    // 线程工厂
    private LzkThreadFactory threadFactory;
    // 拒绝策列
    private RejectedHandler handler;
    //当前线程数,和源代码不同，这里是只记录线程数量
    private AtomicInteger ctl=new AtomicInteger(0);
    //活跃的救急线程
    private AtomicInteger other=new AtomicInteger(0);
    //线程组，只有获取锁才能操作
    private HashSet<Thread> threadSets=new HashSet<Thread>();
    //是否停止向任务队列添加任务
    private AtomicBoolean isShutDown=new AtomicBoolean(false);
    //救急线程等待区
    private ReentrantLock lock=new ReentrantLock();
    private Condition waitSets=lock.newCondition();

    //get&set
    public BlockingQueue<Runnable> getWorkQueue() {
        return workQueue;
    }
    public AtomicBoolean getIsShutDown() {
        return isShutDown;
    }

    //构造方法
    public ThreadPool(int corePoolSize, int maximumPoolSize,
                      long keepAliveTime, TimeUnit unit,
                      BlockingQueue<Runnable> workQueue
                      ) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.unit = unit;
        this.workQueue = workQueue;
        //将线程工厂、拒绝策略选择默认的构造方法
        this.threadFactory =new LzkDefaultThreadFactory();
        this.handler = new AbortPolicy();
    }
    public ThreadPool(int corePoolSize, int maximumPoolSize,
                      long keepAliveTime, TimeUnit unit,
                      BlockingQueue<Runnable> workQueue,
                      RejectedHandler handler) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.unit = unit;
        this.workQueue = workQueue;
        this.threadFactory = new LzkDefaultThreadFactory();
        this.handler = handler;
    }
    public ThreadPool(int corePoolSize, int maximumPoolSize,
                      long keepAliveTime, TimeUnit unit,
                      BlockingQueue<Runnable> workQueue,
                      LzkThreadFactory threadFactory) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.unit = unit;
        this.workQueue = workQueue;
        this.threadFactory = new LzkDefaultThreadFactory();
        this.handler = new AbortPolicy();
    }
    public ThreadPool(int corePoolSize, int maximumPoolSize,
                      long keepAliveTime, TimeUnit unit,
                      BlockingQueue<Runnable> workQueue,
                      LzkThreadFactory threadFactory,
                      RejectedHandler handler) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.unit = unit;
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    //-------------------任务对象-------------------

    /**
     * 线程的任务，先执行第一个任务，之后一直尝试获取任务队列的任务执行
     */
    class Worker implements Runnable {
        //创建的线程执行的第一个任务
        private final Runnable firstTask;
        private long timeOut;
        private TimeUnit timeUnit;

        public Worker(Runnable firstTask) {
            this.firstTask = firstTask;
            this.timeOut = -1L;
        }

        public Worker(Runnable firstTask, long timeOut, TimeUnit timeUnit) {
            this.firstTask = firstTask;
            this.timeOut = timeOut;
            this.timeUnit = timeUnit;
        }

        @Override
        public void run(){
            //第一个任务
            Runnable task=firstTask;
            //阻塞获取任务队列的任务
            while(true){
                try{
                    //是否被打断
                    if (Thread.currentThread().isInterrupted()){
                        ctl.decrementAndGet();
                        threadSets.remove(this);
                        return;
                    }
                    if(!Thread.currentThread().isInterrupted()&&(task!=null||(task=timeOut>=0?workQueue.poll(timeOut,timeUnit):workQueue.take())!=null)){
                        task.run();
                    }else {
                        if(isShutDown.get()){
                            //线程池已结束，结束当前救急线程
                            return;
                        }
                        lock.lock();
                        //救急线程阻塞timeOut时间获取不到任务就进入等待区
                        try{
                            waitSets.await();
                        }finally {
                            lock.unlock();
                        }
                        other.decrementAndGet();
                    }
                    //如果线程池状态为结束并且无待执行任务，则结束线程
                    if (isShutDown.get()&&workQueue.size()==0){
                        ctl.decrementAndGet();
                        threadSets.remove(this);
                        return;
                    }
                }catch (InterruptedException ie) {
                    //非法打断，重新打断
                    Thread.currentThread().interrupt();
                }catch (Exception e){
                    e.printStackTrace();
                }finally {
                    task=null;
                }
            }
        }
    }

    /**
     * 提交任务
     * @param task
     */
    public void execute(Runnable task){
        //若为空不执行
        if(task==null){
            return;
        }
        if(isShutDown.get()){
            //0.线程池状态已经为shutdown,不向队列添加任务
            return;
        }
        else if(ctl.get()<corePoolSize){
            //1、如果当前的线程数小于核心线程数就增加核心线程,并执行任务，执行完就等待任务队列进入的新任务
            Thread thread = threadFactory.newThread(new Worker(task));
            synchronized (threadSets){
                threadSets.add(thread);
            }
            thread.start();
            ctl.incrementAndGet();
        }else if(workQueue.remainingCapacity()>0){
            //2、如果任务队列的容量大于0,即是还可以存放任务，无需拒绝,直接进入队列（无界队列容量2^31）
            workQueue.add(task);
        }else if(workQueue.remainingCapacity()==0&&ctl.get()<maximumPoolSize){
            //3、创建救急线程(救急线程阻塞keepAliveTime后进入等待区)
            Thread thread = threadFactory.newThread(new Worker(task,keepAliveTime,unit));
            synchronized (threadSets){
                threadSets.add(thread);
            }
            thread.start();
            //记录活跃救急线程和总线程数
            other.incrementAndGet();
            ctl.incrementAndGet();
        }else if(workQueue.remainingCapacity()==0&&ctl.get()==maximumPoolSize&&other.get()<maximumPoolSize-corePoolSize){
            //4、唤醒一个救急线程执行任务
            waitSets.signal();
            other.incrementAndGet();
        }
        else if(workQueue.remainingCapacity()==0&&ctl.get()==maximumPoolSize&&other.get()==maximumPoolSize-corePoolSize){
            //5、执行拒绝策略
            handler.rejectedExecution(task,this);
        }
    }

    /**
     * 停止添加任务，停止线程池
     */
    public void shutdown(){
        //标志状态
        this.isShutDown.compareAndSet(false,true);
        //等待任务被分配到每个线程
        while(workQueue.size()>0){}
        //循环判断是否所以线程都结束了
        while(ctl.get()>0){
            synchronized (threadSets){
                for (Thread t:threadSets){
                    if("TERMINATED".equals(t.getState())){
                        ctl.decrementAndGet();
                        threadSets.remove(t);
                        continue;
                    }
                    //waiting:在阻塞获取任务
                    if("WAITING".equals(t.getState().toString())){
                        ctl.decrementAndGet();
                        //打断在阻塞的线程
                        t.interrupt();
                    }
                }
            }
        }
    }

    /**
     * 现在停止线程池
     */
    public void shutdownNow(){
        //标志状态
        this.isShutDown.compareAndSet(false,true);
        //循环打断线程
        while(ctl.get()>0){
            synchronized (threadSets){
                for (Thread t:threadSets){
                    ctl.decrementAndGet();
                    //打断在阻塞的线程
                    t.interrupt();
                }
            }
        }
    }
    //默认的线程工厂
    public static class LzkDefaultThreadFactory implements LzkThreadFactory {
        private AtomicInteger i=new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable task) {
            return new Thread(task,"[lzkThreadPool-"+i.incrementAndGet()+"] ");
        }
    }

    //----------------------------拒绝策略------------------------------

    /**
     * 抛异常
     */
    public static class AbortPolicy implements RejectedHandler {
        public AbortPolicy() { }
        @Override
        public void rejectedExecution(Runnable r, ThreadPool executor) {
            throw new RejectedExecutionException(executor.toString()+"中任务队列已满,任务【" + r.toString() +"】无法执行！");
        }
    }

    /**
     * 调用者执行线程
     */
    public static class CallerRunsPolicy implements RejectedHandler {
        public CallerRunsPolicy() { }
        @Override
        public void rejectedExecution(Runnable r, ThreadPool executor) {
            r.run();
        }
    }
    /**
     * 丢弃任务队列的旧任务，将任务和新任务进行竞争
     */
    public static class DiscardOldestPolicy implements RejectedHandler {
        public DiscardOldestPolicy() { }
        @Override
        public void rejectedExecution(Runnable r, ThreadPool executor) {
            //是否停止向线程池添加任务
            if(!executor.getIsShutDown().get()){
                //丢弃任务队列的就任务
                executor.getWorkQueue().poll();
                //添加任务和新任务进行竞争
                executor.execute(r);
            }
        }
    }

    /**
     * 直接丢弃任务，不抛异常
     */
    public static class DiscardPolicy implements RejectedHandler {
        public DiscardPolicy() { }
        @Override
        public void rejectedExecution(Runnable r, ThreadPool executor) {
        }
    }
}
