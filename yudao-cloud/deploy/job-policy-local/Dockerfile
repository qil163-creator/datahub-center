FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates curl python3 sudo procps psmisc util-linux libstdc++6 zlib1g libzstd1 libudev1 \
    && rm -rf /var/lib/apt/lists/*

# 运行归档只包含已核对的 DS 3.4.2、DataX 与 PostgreSQL 插件，不携带业务元数据或凭据。
ADD runtime.tar.gz /
ADD datax-upsert.tar.gz /
COPY start-upsert-worker.sh /opt/dolphinscheduler/start-upsert-worker.sh
# JVM作为PID1接收正常停止信号，允许JDBC registry关闭钩子释放自身注册与锁。
RUN sed -i 's|^[$]JAVA_HOME/bin/java |exec "$JAVA_HOME/bin/java" |' /opt/dolphinscheduler/standalone-server/bin/start.sh \
    && grep -q '^exec ' /opt/dolphinscheduler/standalone-server/bin/start.sh \
    && useradd -m -u 10001 -s /bin/bash datahub \
    && mkdir -p /opt/datax/log /opt/dolphinscheduler/logs /tmp/dolphinscheduler \
    && chown -R datahub:datahub /opt/datax/log /opt/dolphinscheduler/logs /tmp/dolphinscheduler \
    && printf '%s\n' 'datahub ALL=(datahub) NOPASSWD: ALL' > /etc/sudoers.d/datahub-job-policy \
    && chmod 0440 /etc/sudoers.d/datahub-job-policy

ENV LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DATAX_HOME=/opt/datax \
    DATAX_LAUNCHER=/opt/datax/bin/datax.py \
    PYTHON_LAUNCHER=/usr/bin/python3 \
    TZ=Asia/Shanghai
WORKDIR /opt/dolphinscheduler
USER datahub
CMD ["/bin/bash", "/opt/dolphinscheduler/standalone-server/bin/start.sh"]
