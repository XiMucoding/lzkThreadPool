package lzk;

/**
 * @Author lzk
 * @Email 1801290586@qq.com
 * @Description <接口说明>线程工厂接口
 * @Date 16:20 2022/5/3
 */
@FunctionalInterface
public interface LzkThreadFactory {
    /**
     *  返回创建的线程
     * @param task
     * @return
     */
    Thread newThread(Runnable task);
}
