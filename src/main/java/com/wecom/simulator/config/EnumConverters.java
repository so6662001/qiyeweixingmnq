package com.wecom.simulator.config;

import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.SenderRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

public final class EnumConverters {

    private EnumConverters() {
    }

    @Component
    public static class StringToMessageType implements Converter<String, MessageType> {
        @Override
        public MessageType convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            String value = source.trim().toLowerCase();
            for (MessageType type : MessageType.values()) {
                if (type.getValue().equals(value) || type.name().equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("未知 msgtype: " + source);
        }
    }

    @Component
    public static class StringToSenderRole implements Converter<String, SenderRole> {
        @Override
        public SenderRole convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            String value = source.trim().toLowerCase();
            for (SenderRole role : SenderRole.values()) {
                if (role.getValue().equals(value) || role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("未知 role: " + source);
        }
    }
}
