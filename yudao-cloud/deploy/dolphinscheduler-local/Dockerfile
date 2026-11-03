FROM flink:1.20.3-scala_2.12-java17@sha256:6a2b771c073b523e43d72b46e338ce6cef25e969965444f6afe1b0bb15fa9772 AS flink-runtime
FROM apache/spark:3.5.6-java17-scala@sha256:c7622e2bad3454cb9a70863d1fed9c08c73b19920e50f0a2799ec496ab0cd316 AS spark-runtime
FROM apache/dolphinscheduler-standalone-server:3.4.2@sha256:485a1b37dd1c4088c8c8335f9fccbd229e5e703c32e21f318eb00cbb60b1af9d AS datax-runtime

COPY runtime/datax-202308.tar.gz /tmp/datax.tar.gz
RUN tar -xzf /tmp/datax.tar.gz -C /opt \
    && rm /tmp/datax.tar.gz

FROM apache/dolphinscheduler-standalone-server:3.4.2@sha256:485a1b37dd1c4088c8c8335f9fccbd229e5e703c32e21f318eb00cbb60b1af9d

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends psmisc python3 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=flink-runtime /opt/flink /opt/flink
COPY --from=flink-runtime /opt/java/openjdk /opt/java17
COPY --from=spark-runtime /opt/spark /opt/spark
COPY --from=spark-runtime /usr/bin/rev /usr/bin/rev
COPY --from=datax-runtime /opt/datax /opt/datax
RUN mv /opt/flink/bin/sql-client.sh /opt/flink/bin/sql-client-original.sh
COPY --chmod=755 sql-client.sh /opt/flink/bin/sql-client.sh
COPY runtime/postgresql-42.7.13.jar /opt/spark/jars/postgresql-42.7.13.jar
COPY runtime/postgresql-42.7.13.jar /opt/flink/lib/postgresql-42.7.13.jar
COPY runtime/flink-connector-jdbc-3.3.0-1.20.jar /opt/flink/lib/flink-connector-jdbc-3.3.0-1.20.jar
COPY runtime/flink-sql-connector-kafka-3.3.0-1.20.jar /opt/flink/lib/flink-sql-connector-kafka-3.3.0-1.20.jar
COPY runtime/mongo-spark/*.jar /opt/spark/jars/
COPY runtime/hdfs/ /opt/spark/jars/
COPY runtime/redis/ /opt/spark/jars/
COPY runtime/odps/ /opt/spark/jars/
COPY runtime/datax-upsert/ /
COPY --chmod=755 start-local.sh /opt/dolphinscheduler/start-local.sh
RUN mv /opt/dolphinscheduler/standalone-server/bin/start.sh /opt/dolphinscheduler/standalone-server/bin/start-upstream.sh
COPY --chmod=755 start-standalone.sh /opt/dolphinscheduler/standalone-server/bin/start.sh

RUN useradd -m -u 10001 -s /bin/bash datahub \
    && mkdir -p /opt/datax/log /opt/flink/log /opt/spark/logs \
    && chown -R datahub:datahub /opt/datax/log /opt/flink/log /opt/spark/logs \
    && printf '%s\n' 'datahub ALL=(datahub) NOPASSWD: ALL' > /etc/sudoers.d/datahub-local \
    && chmod 0440 /etc/sudoers.d/datahub-local

ENV FLINK_HOME=/opt/flink \
    SPARK_HOME=/opt/spark \
    SPARK_HOME1=/opt/spark \
    SPARK_HOME2=/opt/spark \
    DATAX_HOME=/opt/datax \
    DATAX_LAUNCHER=/opt/datax/bin/datax.py \
    PYTHON_LAUNCHER=/usr/bin/python3
