package com.yiyu.mergebot.config;

import com.lark.oapi.Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书 API 客户端（发消息、查群列表用）。
 * 注意：长连接用的是另一个类 {@link com.lark.oapi.ws.Client}，见 FeishuWsRunner。
 */
@Configuration
public class FeishuConfig {

    @Bean
    public Client feishuApiClient(FeishuProperties props) {
        return new Client.Builder(props.getAppId(), props.getAppSecret()).build();
    }
}
