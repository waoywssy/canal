package com.alibaba.otter.canal.server.netty.handler;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.util.CollectionUtils;

import com.alibaba.erosa.protocol.protobuf.ErosaEntry.Entry;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningMonitor;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningMonitors;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.protocol.E3;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.E3.ClientAck;
import com.alibaba.otter.canal.protocol.E3.ClientRollback;
import com.alibaba.otter.canal.protocol.E3.E3Packet;
import com.alibaba.otter.canal.protocol.E3.Get;
import com.alibaba.otter.canal.protocol.E3.Messages;
import com.alibaba.otter.canal.protocol.E3.PacketType;
import com.alibaba.otter.canal.protocol.E3.Sub;
import com.alibaba.otter.canal.protocol.E3.Unsub;
import com.alibaba.otter.canal.server.embeded.CanalServerWithEmbeded;
import com.alibaba.otter.canal.server.netty.NettyUtils;
import com.alibaba.otter.canal.sink.AbstractCanalEventSink;
import com.alibaba.otter.canal.sink.filter.AviaterRegexFilter;

/**
 * 处理具体的客户端请求
 * 
 * @author jianghang 2012-10-24 下午02:21:13
 * @version 1.0.0
 */
public class SessionHandler extends SimpleChannelHandler {

    private static final Logger    logger = LoggerFactory.getLogger(SessionHandler.class);
    private CanalServerWithEmbeded embededServer;

    public SessionHandler(){

    }

    public SessionHandler(CanalServerWithEmbeded embededServer){
        this.embededServer = embededServer;
    }

    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        logger.info("message receives in session handler...");
        ChannelBuffer buffer = (ChannelBuffer) e.getMessage();
        E3Packet e3packet = E3Packet.parseFrom(buffer.readBytes(buffer.readableBytes()).array());
        ClientIdentity clientIdentity = null;
        try {
            switch (e3packet.getType()) {
                case SUBSCRIPTION:
                    Sub sub = Sub.parseFrom(e3packet.getBody());
                    if (StringUtils.isNotEmpty(sub.getDestination()) && StringUtils.isNotEmpty(sub.getClientId())) {
                        clientIdentity = new ClientIdentity(sub.getDestination(), Short.valueOf(sub.getClientId()),
                                                            sub.getFilter());
                        MDC.put("destination", clientIdentity.getDestination());
                        embededServer.subscribe(clientIdentity);
                        // 注册过滤条件
                        if (StringUtils.isNotEmpty(clientIdentity.getFilter())) {
                            AviaterRegexFilter aviaterFilter = new AviaterRegexFilter(clientIdentity.getFilter());
                            AbstractCanalEventSink eventSink = (AbstractCanalEventSink) embededServer.getCanalInstances().get(
                                                                                                                              clientIdentity.getDestination()).getEventSink();
                            eventSink.setFilter(aviaterFilter);
                        }

                        // 尝试启动，如果已经启动，忽略
                        if (!embededServer.isStart(clientIdentity.getDestination())) {
                            ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(clientIdentity.getDestination());
                            if (!runningMonitor.isStart()) {
                                runningMonitor.start();
                            }
                        }

                        ctx.setAttachment(clientIdentity);// 设置状态数据
                        NettyUtils.ack(ctx.getChannel(), null);
                    } else {
                        NettyUtils.error(
                                         401,
                                         MessageFormatter.format("destination or clientId is null", sub.toString()).getMessage(),
                                         ctx.getChannel(), null);
                    }
                    break;
                case UNSUBSCRIPTION:
                    Unsub unsub = Unsub.parseFrom(e3packet.getBody());
                    if (StringUtils.isNotEmpty(unsub.getDestination()) && StringUtils.isNotEmpty(unsub.getClientId())) {
                        clientIdentity = new ClientIdentity(unsub.getDestination(), Short.valueOf(unsub.getClientId()),
                                                            unsub.getFilter());
                        MDC.put("destination", clientIdentity.getDestination());
                        embededServer.unsubscribe(clientIdentity);
                        stopCanalInstanceIfNecessary(clientIdentity);// 尝试关闭
                        NettyUtils.ack(ctx.getChannel(), null);
                    } else {
                        NettyUtils.error(401, MessageFormatter.format("destination or clientId is null",
                                                                      unsub.toString()).getMessage(), ctx.getChannel(),
                                         null);
                    }
                    break;
                case GET:
                    Get get = E3.Get.parseFrom(e3packet.getBody());
                    if (StringUtils.isNotEmpty(get.getDestination()) && StringUtils.isNotEmpty(get.getClientId())) {
                        clientIdentity = new ClientIdentity(get.getDestination(), Short.valueOf(get.getClientId()));
                        MDC.put("destination", clientIdentity.getDestination());
                        Message message = embededServer.getWithoutAck(clientIdentity, get.getFetchSize());

                        E3Packet.Builder packetBuilder = E3.E3Packet.newBuilder();
                        packetBuilder.setType(PacketType.MESSAGES);

                        Messages.Builder messageBuilder = E3.Messages.newBuilder();
                        messageBuilder.setBatchId(message.getId());
                        if (message.getId() != -1 && !CollectionUtils.isEmpty(message.getEntries())) {
                            for (Entry entry : message.getEntries()) {
                                messageBuilder.addMessages(entry.toByteString());
                            }
                        }
                        packetBuilder.setBody(messageBuilder.build().toByteString());
                        NettyUtils.write(ctx.getChannel(), packetBuilder.build().toByteArray(), null);// 输出数据
                    } else {
                        NettyUtils.error(
                                         401,
                                         MessageFormatter.format("destination or clientId is null", get.toString()).getMessage(),
                                         ctx.getChannel(), null);
                    }
                    break;
                case CLIENTACK:
                    ClientAck ack = E3.ClientAck.parseFrom(e3packet.getBody());
                    MDC.put("destination", ack.getDestination());
                    if (StringUtils.isNotEmpty(ack.getDestination()) && StringUtils.isNotEmpty(ack.getClientId())) {
                        if (ack.getBatchId() == 0L) {
                            NettyUtils.error(
                                             402,
                                             MessageFormatter.format("batchId should assign value", ack.toString()).getMessage(),
                                             ctx.getChannel(), null);
                        } else if (ack.getBatchId() == -1L) { // -1代表上一次get没有数据，直接忽略之
                            // donothing
                        } else {
                            clientIdentity = new ClientIdentity(ack.getDestination(), Short.valueOf(ack.getClientId()));
                            embededServer.ack(clientIdentity, ack.getBatchId());
                        }
                    } else {
                        NettyUtils.error(
                                         401,
                                         MessageFormatter.format("destination or clientId is null", ack.toString()).getMessage(),
                                         ctx.getChannel(), null);
                    }
                    break;
                case CLIENTROLLBACK:
                    ClientRollback rollback = E3.ClientRollback.parseFrom(e3packet.getBody());
                    MDC.put("destination", rollback.getDestination());
                    if (StringUtils.isNotEmpty(rollback.getDestination())
                        && StringUtils.isNotEmpty(rollback.getClientId())) {
                        clientIdentity = new ClientIdentity(rollback.getDestination(),
                                                            Short.valueOf(rollback.getClientId()));
                        if (rollback.getBatchId() == 0L) {
                            embededServer.rollback(clientIdentity);// 回滚所有批次
                        } else {
                            embededServer.rollback(clientIdentity, rollback.getBatchId()); // 只回滚单个批次
                        }
                    } else {
                        NettyUtils.error(401, MessageFormatter.format("destination or clientId is null",
                                                                      rollback.toString()).getMessage(),
                                         ctx.getChannel(), null);
                    }
                    break;
                default:
                    NettyUtils.error(400, MessageFormatter.format("packet type={} is NOT supported!",
                                                                  e3packet.getType()).getMessage(), ctx.getChannel(),
                                     null);
                    break;
            }
        } catch (Throwable exception) {
            NettyUtils.error(
                             400,
                             MessageFormatter.format("something goes wrong with channel:{}, exception={}",
                                                     ctx.getChannel(), ExceptionUtils.getStackTrace(exception)).getMessage(),
                             ctx.getChannel(), null);
        } finally {
            MDC.remove("destination");
        }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        logger.error("something goes wrong with channel:{}, exception={}", ctx.getChannel(),
                     ExceptionUtils.getStackTrace(e.getCause()));

        ctx.getChannel().close();
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        logger.info("remove binding subscription value object if any...");
        ClientIdentity clientIdentity = (ClientIdentity) ctx.getAttachment();
        // 如果唯一的订阅者都取消了订阅，直接关闭服务，针对内部版本模式下可以减少资源浪费
        if (clientIdentity != null) {
            stopCanalInstanceIfNecessary(clientIdentity);
        }

    }

    private void stopCanalInstanceIfNecessary(ClientIdentity clientIdentity) {
        List<ClientIdentity> clientIdentitys = embededServer.listAllSubscribe(clientIdentity.getDestination());
        if (clientIdentitys != null && clientIdentitys.size() == 1 && clientIdentitys.contains(clientIdentity)) {
            if (embededServer.isStart(clientIdentity.getDestination())) {
                embededServer.stop(clientIdentity.getDestination());
                ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(clientIdentity.getDestination());
                if (runningMonitor.isStart()) {
                    runningMonitor.stop();
                }
            }
        }
    }

    public void setEmbededServer(CanalServerWithEmbeded embededServer) {
        this.embededServer = embededServer;
    }

}