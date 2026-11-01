package org.example.workersafety_server.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;

@Converter(autoApply = true)
public class InstantConverter implements AttributeConverter<Instant, Long> {
    public Long convertToDatabaseColumn(Instant value) { return value == null ? null : value.toEpochMilli(); }
    public Instant convertToEntityAttribute(Long value) { return value == null ? null : Instant.ofEpochMilli(value); }
}
