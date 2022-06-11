package lzk;


/**
 * @Author lzk
 * @Email 1801290586@qq.com
 * @Description <接口说明>拒绝策略接口
 * @Date 16:47 2022/5/3
 */
public interface RejectedHandler {
    void rejectedExecution(Runnable r, ThreadPool executor);
}
