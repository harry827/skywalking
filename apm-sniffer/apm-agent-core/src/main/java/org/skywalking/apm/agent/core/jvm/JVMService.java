/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.agent.core.jvm;

import io.grpc.ManagedChannel;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.skywalking.apm.agent.core.boot.BootService;
import org.skywalking.apm.agent.core.boot.ServiceManager;
import org.skywalking.apm.agent.core.conf.Config;
import org.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.skywalking.apm.agent.core.jvm.cpu.CPUProvider;
import org.skywalking.apm.agent.core.jvm.gc.GCProvider;
import org.skywalking.apm.agent.core.jvm.memory.MemoryProvider;
import org.skywalking.apm.agent.core.jvm.memorypool.MemoryPoolProvider;
import org.skywalking.apm.agent.core.remote.GRPCChannelListener;
import org.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.skywalking.apm.logging.ILog;
import org.skywalking.apm.logging.LogManager;
import org.skywalking.apm.network.proto.JVMMetric;
import org.skywalking.apm.network.proto.JVMMetrics;
import org.skywalking.apm.network.proto.JVMMetricsServiceGrpc;

import static org.skywalking.apm.agent.core.remote.GRPCChannelStatus.CONNECTED;

/**
 * The <code>JVMService</code> represents a timer,
 * which collectors JVM cpu, memory, memorypool and gc info,
 * and send the collected info to Collector through the channel provided by {@link GRPCChannelManager}
 *
 * @author wusheng
 */
public class JVMService implements BootService, Runnable {
    private static final ILog logger = LogManager.getLogger(JVMService.class);
    private LinkedBlockingQueue<JVMMetric> queue;
    private volatile ScheduledFuture<?> collectMetricFuture;
    private volatile ScheduledFuture<?> sendMetricFuture;
    private Sender sender;

    @Override
    public void beforeBoot() throws Throwable {
        queue = new LinkedBlockingQueue(Config.Jvm.BUFFER_SIZE);
        sender = new Sender();
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(sender);
    }

    @Override
    public void boot() throws Throwable {
        collectMetricFuture = Executors
            .newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(this, 0, 1, TimeUnit.SECONDS);
        sendMetricFuture = Executors
            .newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(sender, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterBoot() throws Throwable {

    }

    @Override
    public void shutdown() throws Throwable {
        collectMetricFuture.cancel(true);
        sendMetricFuture.cancel(true);
    }

    @Override
    public void run() {
        if (RemoteDownstreamConfig.Agent.APPLICATION_ID != DictionaryUtil.nullValue()
            && RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID != DictionaryUtil.nullValue()
            ) {
            long currentTimeMillis = System.currentTimeMillis();
            try {
                JVMMetric.Builder jvmBuilder = JVMMetric.newBuilder();
                jvmBuilder.setTime(currentTimeMillis);
                jvmBuilder.setCpu(CPUProvider.INSTANCE.getCpuMetric());
                jvmBuilder.addAllMemory(MemoryProvider.INSTANCE.getMemoryMetricList());
                jvmBuilder.addAllMemoryPool(MemoryPoolProvider.INSTANCE.getMemoryPoolMetricList());
                jvmBuilder.addAllGc(GCProvider.INSTANCE.getGCList());

                JVMMetric jvmMetric = jvmBuilder.build();
                if (!queue.offer(jvmMetric)) {
                    queue.poll();
                    queue.offer(jvmMetric);
                }
            } catch (Exception e) {
                logger.error(e, "Collect JVM info fail.");
            }
        }
    }

    private class Sender implements Runnable, GRPCChannelListener {
        private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;
        private volatile JVMMetricsServiceGrpc.JVMMetricsServiceBlockingStub stub = null;

        @Override
        public void run() {
            if (RemoteDownstreamConfig.Agent.APPLICATION_ID != DictionaryUtil.nullValue()
                && RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID != DictionaryUtil.nullValue()
                ) {
                if (status == GRPCChannelStatus.CONNECTED) {
                    try {
                        JVMMetrics.Builder builder = JVMMetrics.newBuilder();
                        LinkedList<JVMMetric> buffer = new LinkedList<JVMMetric>();
                        queue.drainTo(buffer);
                        if (buffer.size() > 0) {
                            builder.addAllMetrics(buffer);
                            builder.setApplicationInstanceId(RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID);
                            stub.collect(builder.build());
                        }
                    } catch (Throwable t) {
                        logger.error(t, "send JVM metrics to Collector fail.");
                    }
                }
            }
        }

        @Override
        public void statusChanged(GRPCChannelStatus status) {
            if (CONNECTED.equals(status)) {
                ManagedChannel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getManagedChannel();
                stub = JVMMetricsServiceGrpc.newBlockingStub(channel);
            }
            this.status = status;
        }
    }
}
