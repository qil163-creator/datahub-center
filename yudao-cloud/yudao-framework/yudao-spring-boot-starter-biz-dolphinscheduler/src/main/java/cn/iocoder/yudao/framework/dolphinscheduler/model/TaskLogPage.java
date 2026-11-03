package cn.iocoder.yudao.framework.dolphinscheduler.model;

/**
 * A bounded page of physical task-log lines. An empty page is an observation,
 * not proof that a running task has finished writing its log.
 *
 * @param message log body without the synthetic DS host/path header; callers still own redaction
 * @param readLines physical file lines consumed, independent of DS response lineNum
 * @param nextSkipLineNum cursor for the next request against the same task instance
 * @param mayHaveMore true after any non-empty page; DS can truncate a page before the requested limit
 * @param truncated true when the client response byte/line bound clipped content
 */
public record TaskLogPage(String message, int readLines, int nextSkipLineNum,
                          boolean mayHaveMore, boolean truncated) {

}
