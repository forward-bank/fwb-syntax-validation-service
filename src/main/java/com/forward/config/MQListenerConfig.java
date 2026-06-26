package com.forward.config;

import com.forward.mq.MQConfig;
import com.forward.mq.listener.SyntaxValidationRequestListener;
import com.forward.s3.S3FileDownloader;
import com.forward.validator.SyntaxValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the IBM MQ listener as a Spring bean.
 * Spring calls start() after the application context is fully ready
 * and stop() on graceful shutdown.
 */
@Configuration
public class MQListenerConfig {

    @Value("${mq.host:localhost}")
    private String host;

    @Value("${mq.port:1414}")
    private int port;

    @Value("${mq.channel:SYSTEM.DEF.SVRCONN}")
    private String channel;

    @Value("${mq.queueManager:MY.TEST.QMNGR}")
    private String queueManager;

    @Bean
    public MQConfig mqConfig() {
        return new MQConfig(host, port, channel, queueManager);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SyntaxValidationRequestListener syntaxValidationRequestListener(
            MQConfig mqConfig,
            S3FileDownloader s3FileDownloader,
            SyntaxValidator syntaxValidator) {
        return new SyntaxValidationRequestListener(mqConfig, s3FileDownloader, syntaxValidator);
    }
}
