package com.hudscustomitems.customitems.attribute;

/**
 * Readable definition for one supported custom item attribute.
 *
 * @param id machine id used in commands and config
 * @param displayName readable name shown in commands
 * @param category list category
 * @param valueType expected value type
 * @param description short player-facing description
 */
public record AttributeDefinition(
    String id,
    String displayName,
    AttributeCategory category,
    AttributeValueType valueType,
    String description) {
}
