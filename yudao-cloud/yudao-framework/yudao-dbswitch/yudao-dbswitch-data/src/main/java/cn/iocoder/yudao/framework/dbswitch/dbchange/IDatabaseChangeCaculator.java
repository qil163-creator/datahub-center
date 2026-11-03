package cn.iocoder.yudao.framework.dbswitch.dbchange;

/**
 * 变化量计算器接口定义
 *
 * @author jrl
 */
public interface IDatabaseChangeCaculator {

    /**
     * 是否记录无变化的数据
     *
     * @return 是否记录无变化的数据
     */
    boolean isRecordIdentical();

    /**
     * 设置是否记录无变化的数据
     *
     * @param recordOrNot 是否记录无变化的数据
     */
    void setRecordIdentical(boolean recordOrNot);

    /**
     * 是否进行Jdbc的数据类型检查
     *
     * @return 是否进行检查
     */
    boolean isCheckJdbcType();

    /**
     * 设置是否进行Jdbc的数据类型检查
     *
     * @param checkOrNot 是否进行检查
     */
    void setCheckJdbcType(boolean checkOrNot);

    /**
     * 获取JDBC驱动批量读取数据的行数大小
     *
     * @return 批量行数大小
     */
    int getFetchSize();

    /**
     * 设置JDBC驱动批量读取数据的行数大小
     *
     * @param size 批量行数大小
     */
    void setFetchSize(int size);

    /**
     * 设置中断检查回调（对齐 dbswitch 2.0.2 的 setInterruptCheck）：
     * 归并比对循环的每次迭代都会执行该回调，回调内部应在任务被中断时抛出异常终止比对。
     * 不设置时不做循环级中断检查（仅靠行处理回调内的检查，大段无变化记录期间无法及时中断）
     *
     * @param interruptCheck 中断检查回调
     */
    default void setInterruptCheck(Runnable interruptCheck) {
    }

    /**
     * 执行变化量计算任务
     *
     * @param task    任务描述实体对象
     * @param handler 计算结果回调处理器
     */
    void executeCalculate(TaskParamEntity task, IDatabaseRowHandler handler);


}
