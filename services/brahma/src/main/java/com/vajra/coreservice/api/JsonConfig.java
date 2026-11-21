package com.vajra.coreservice.api;

import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/** Money and price values leave the API as plain strings ("0.1", never 0.1 or "1E-1"), so no client loses precision. */
@Configuration
class JsonConfig {

    @Bean
    JacksonModule bigDecimalAsPlainString() {
        return new SimpleModule("bigDecimalAsPlainString").addSerializer(BigDecimal.class, new StdSerializer<>(BigDecimal.class) {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(value.toPlainString());
            }
        });
    }
}
