package com.futuresriskops.risk.margin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 설정에서 증거금률을 읽어 계산기를 만든다. 증거금률이 없거나 범위를 벗어나면 시작할 때 실패한다. */
@Configuration
@EnableConfigurationProperties(MarginProperties.class)
class MarginConfig {

    @Bean
    MarginCalculator marginCalculator(MarginProperties props) {
        return props.calculator();
    }
}
